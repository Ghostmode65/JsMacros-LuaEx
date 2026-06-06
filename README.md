# JsMacros-LuaEx

## Fork of JsMacros providing some extra lua functionality

This extension adds `lua 5.2` support to 
- [JsMacros](https://github.com/wagyourtail/JsMacros) `1.2.2+`
- Soon [JsMacros CE](https://github.com/JsMacrosCE/JsMacros) `beta.5 +`

# Notes

##
All libraries provided by JsMacros need a `:` rather than a `.` because of how methods of classes are accessed in lua.
ie. `Chat:log("test")`
