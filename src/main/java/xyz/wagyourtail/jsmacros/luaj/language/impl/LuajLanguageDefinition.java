package xyz.wagyourtail.jsmacros.luaj.language.impl;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.config.ScriptTrigger;
import xyz.wagyourtail.jsmacros.core.event.BaseEvent;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseScriptContext;
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException;
import xyz.wagyourtail.jsmacros.core.language.EventContainer;
import xyz.wagyourtail.jsmacros.luaj.LuajExtension;
import xyz.wagyourtail.jsmacros.luaj.config.LuajConfig;

import java.io.File;
import java.util.Map;

public class LuajLanguageDefinition extends BaseLanguage<Globals, LuajScriptContext> {

    // global pools.
    public static final Map<String, Globals> namedGlobals = new java.util.concurrent.ConcurrentHashMap<>();

    public static final ThreadLocal<String> activeLoadscriptPool = new ThreadLocal<>();

    public static final Map<String, LuajScriptContext> globalOwners = new java.util.concurrent.ConcurrentHashMap<>();

    //ensure only one script occupies a pool.
    private static final Map<String, Object> poolLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private static Object getLockFor(String poolName) {
        return poolLocks.computeIfAbsent(poolName, k -> new Object());
    }

    public LuajLanguageDefinition(LuajExtension extension, Core runner) {
        super(extension, runner);
    }

    // Pool names returned with this prefix signal a force-takeover.
    private static final String FORCE_PREFIX = "force:";

    private String getGlobalName(LuajScriptContext ctx) {
        File file = ctx.getFile();
        if (file == null) return null;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = br.readLine();
            if (line == null) return null;
            line = line.trim();
            if (line.equals("--@global")) return "default";
            if (line.startsWith("--@global ")) return line.substring("--@global ".length()).trim();
            if (line.equals("--@global-force")) return FORCE_PREFIX + "default";
            if (line.startsWith("--@global-force ")) return FORCE_PREFIX + line.substring("--@global-force ".length()).trim();
        } catch (java.io.IOException ignored) {}
        return null;
    }

    protected void execContext(EventContainer<LuajScriptContext> ctx, Executor e) throws Exception {
        LuajConfig cfg = runner.config.getOptions(LuajConfig.class);
        Globals globals;
        String claimedPool = null;

        if (cfg.useGlobalContext) {
            if (cfg.splitGlobalContext) {
                String rawPoolName = getGlobalName(ctx.getCtx());
                if (rawPoolName != null) {
                    boolean forceMode = rawPoolName.startsWith(FORCE_PREFIX);
                    String poolName = forceMode ? rawPoolName.substring(FORCE_PREFIX.length()) : rawPoolName;
                    synchronized (getLockFor(poolName)) {
                        LuajScriptContext currentOwner = globalOwners.get(poolName);
                        boolean slotFree = currentOwner == null || currentOwner.isContextClosed();
                        if (slotFree || forceMode) {
                            if (!slotFree) {
                                // kill the current owner so its events stop firing
                                currentOwner.closeContext();
                            }
                            globals = namedGlobals.computeIfAbsent(poolName, k -> JsePlatform.debugGlobals());
                            globalOwners.put(poolName, ctx.getCtx());
                            claimedPool = poolName;
                        } else {
                            globals = JsePlatform.debugGlobals();
                        }
                    }
                } else {
                    globals = JsePlatform.debugGlobals();
                }
            } else {
                //If split context is off fallback to useGlobalContext
                globals = namedGlobals.computeIfAbsent("default", k -> JsePlatform.debugGlobals());
            }
        } else {
            globals = JsePlatform.debugGlobals();
        }

        ctx.getCtx().setContext(globals);
        retrieveLibs(ctx.getCtx()).forEach((name, lib) -> globals.set(name, CoerceJavaToLua.coerce(lib)));

        final String finalClaimedPool = claimedPool;
        try {
            e.accept(globals);
        } catch (LuaError le) {
            // LuaJ wraps InterruptedException (from waitTick/wrapSleep) in a LuaError via
            // reflection. Re-throw as InterruptedException so the framework treats it as a
            // clean script termination rather than logging it as a crash.
            Throwable cause = le.getCause();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw (InterruptedException) cause;
            }
            throw le;
        } finally {
            if (finalClaimedPool != null) {
                synchronized (getLockFor(finalClaimedPool)) {
                    globalOwners.remove(finalClaimedPool, ctx.getCtx());
                }
            }
        }
    }

    /**
     * Releases the current script from the pool, the global stays but is now just local to the script.
     */
    public static void releaseGlobal(LuajScriptContext ctx) {
        for (Map.Entry<String, LuajScriptContext> entry : globalOwners.entrySet()) {
            if (entry.getValue() == ctx) {
                String poolName = entry.getKey();
                synchronized (getLockFor(poolName)) {
                    if (globalOwners.get(poolName) == ctx) {
                        Globals current = namedGlobals.get(poolName);
                        if (current != null) {
                            namedGlobals.put(poolName, snapshotGlobals(current));
                        }
                        globalOwners.remove(poolName);
                    }
                }
                break;
            }
        }
    }

    /**
     * Copies the global table form a released script and will pass it to a new script.
     */
    private static Globals snapshotGlobals(Globals source) {
        Globals fresh = JsePlatform.debugGlobals();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs entry = source.next(k);
            k = entry.arg1();
            if (k.isnil()) break;
            fresh.rawset(k, entry.arg(2));
        }
        return fresh;
    }

    public static boolean destroyPool(String poolName) {
        synchronized (getLockFor(poolName)) {
            LuajScriptContext owner = globalOwners.get(poolName);
            if (owner != null && !owner.isContextClosed()) {
                return false;
            }
            globalOwners.remove(poolName);
            namedGlobals.remove(poolName);
            poolLocks.remove(poolName);
            return true;
        }
    }

    public static String getOwnedPool(LuajScriptContext ctx) {
        return globalOwners.entrySet().stream()
            .filter(e -> e.getValue() == ctx)
            .map(Map.Entry::getKey)
            .findFirst().orElse(null);
    }
    
    private void setPerExecVar(BaseScriptContext<?> ctx, Globals globals, String name, LuaValue val) {
        boolean put = globals.rawget(name) instanceof PerContextLuaValue;
        PerContextLuaValue pclv = put ? (PerContextLuaValue) globals.rawget(name) : new PerContextLuaValue();
        pclv.addContext(ctx, val);
        if (!put) globals.rawset(name, pclv);
    }
    
    @Override
    protected void exec(EventContainer<LuajScriptContext> ctx, ScriptTrigger macro, BaseEvent event) throws Exception {
        execContext(ctx, (globals) -> {
            setPerExecVar(ctx.getCtx(), globals, "event", CoerceJavaToLua.coerce(event));
            setPerExecVar(ctx.getCtx(), globals, "file", CoerceJavaToLua.coerce(ctx.getCtx().getFile()));
            setPerExecVar(ctx.getCtx(), globals, "context", CoerceJavaToLua.coerce(ctx));
            
            retrieveOnceLibs().forEach((name, lib) -> globals.set(name, CoerceJavaToLua.coerce(lib)));
            
            retrievePerExecLibs(ctx.getCtx()).forEach((name, lib) -> setPerExecVar(ctx.getCtx(), globals, name, CoerceJavaToLua.coerce(lib)));
            
            LuaClosure current = (LuaClosure) globals.loadfile(ctx.getCtx().getFile().getCanonicalPath());
            current.call();
        });
    }

    @Override
    protected void exec(EventContainer<LuajScriptContext> ctx, String lang, String script, BaseEvent event) throws Exception {
        execContext(ctx, (globals) -> {
            setPerExecVar(ctx.getCtx(), globals, "event", CoerceJavaToLua.coerce(event));
            setPerExecVar(ctx.getCtx(), globals, "file", CoerceJavaToLua.coerce(ctx.getCtx().getFile()));
            setPerExecVar(ctx.getCtx(), globals, "context", CoerceJavaToLua.coerce(ctx));

            LuaValue current = globals.load(script);
            current.call();
        });
    }
    
    @Override
    public LuajScriptContext createContext(BaseEvent event, File file) {
        return new LuajScriptContext(event, file);
    }
    
    private interface Executor {
        void accept(Globals globals) throws Exception;
    }
}
