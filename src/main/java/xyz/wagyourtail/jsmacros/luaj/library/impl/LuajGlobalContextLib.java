package xyz.wagyourtail.jsmacros.luaj.library.impl;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.library.Library;
import xyz.wagyourtail.jsmacros.core.library.PerExecLanguageLibrary;
import xyz.wagyourtail.jsmacros.luaj.language.impl.LuajLanguageDefinition;
import xyz.wagyourtail.jsmacros.luaj.language.impl.LuajScriptContext;

@Library(value = "GlobalContext", languages = LuajLanguageDefinition.class)
public class LuajGlobalContextLib extends PerExecLanguageLibrary<Globals, LuajScriptContext> {

    public LuajGlobalContextLib(LuajScriptContext context, Class<? extends BaseLanguage<Globals, LuajScriptContext>> language) {
        super(context, language);
    }

    public String getPool() {
        String active = LuajLanguageDefinition.activeLoadscriptPool.get();
        if (active != null) return active;
        return LuajLanguageDefinition.getOwnedPool(ctx);
    }
    public boolean isGlobal() {
        return LuajLanguageDefinition.getOwnedPool(ctx) != null;
    }
    public void release() {
        LuajLanguageDefinition.releaseGlobal(ctx);
    }
    public boolean destroyPool(String poolName) {
        return LuajLanguageDefinition.destroyPool(poolName);
    }

    /**
     * Loads and returns a callable chunk that runs inside the named global pool.
     * If the pool doesn't exist yet it is created automatically.
     * Usage from Lua: GlobalContext:loadscript(script, "mypool")()
     */
    public LuaValue loadscript(String script, String poolName) {
        Globals poolGlobals = LuajLanguageDefinition.namedGlobals
            .computeIfAbsent(poolName, k -> ctx.getContext());
        LuaValue chunk = poolGlobals.load(script);
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuajLanguageDefinition.activeLoadscriptPool.set(poolName);
                try {
                    return chunk.invoke(args);
                } finally {
                    LuajLanguageDefinition.activeLoadscriptPool.remove();
                }
            }
        };
    }
}
