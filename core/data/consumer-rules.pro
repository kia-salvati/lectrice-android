# JNA finds native methods, structures and callbacks by reflection.
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
# The uniffi bindings register their native functions with JNA by name.
-keep class uniffi.lectrice_ffi.** { *; }
