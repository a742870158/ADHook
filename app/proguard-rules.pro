# API101 framework entry is instantiated by name from META-INF/xposed/java_init.list.
-keep class com.adhook.AdHookModule { *; }

# Android components named in the manifest; framework callbacks remain reachable via their base types.
-keep class com.adhook.AdHookApplication { public <init>(); }
-keep class com.adhook.config.ConfigProvider { public <init>(); }
-keep class com.adhook.ui.MainActivity { public <init>(); }
-keep class com.adhook.ui.AppDetailActivity { public <init>(); }

# HookRegistration resolves these names on the base class and concrete callback subclasses.
# Preserve only this actual reflection contract, not every module class/member.
-keepclassmembers class com.adhook.runtime.HookCallback {
    protected void beforeHookedMethod(com.adhook.runtime.HookCallback$MethodHookParam);
    protected void afterHookedMethod(com.adhook.runtime.HookCallback$MethodHookParam);
}
-keepclassmembers class * extends com.adhook.runtime.HookCallback {
    protected void beforeHookedMethod(com.adhook.runtime.HookCallback$MethodHookParam);
    protected void afterHookedMethod(com.adhook.runtime.HookCallback$MethodHookParam);
}

# SensorAdBlocker excludes module stack frames by this package prefix.
# Preserve only package identity: classes/members may still shrink and obfuscate.
-keeppackagenames com.adhook,com.adhook.**

# Configuration/subscription models use explicit JSON keys, not reflective field serialization.
# Other module implementation names may shrink/obfuscate; do not add a whole-package keep/dontwarn.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
