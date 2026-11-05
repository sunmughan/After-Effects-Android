# M7 release (R8) rules for EffectCraft for Android.
#
# Only the JNI boundary needs explicit keeps: the native library resolves
# these Kotlin members by their JVM names, so R8 must not rename or strip
# them. Everything else is left to R8's defaults (manifest-referenced
# components are kept automatically; Compose does not need extra rules here).
# The app model is parsed with org.json directly, not by reflection over app
# classes, so no serialization keeps are needed. If reflection-based
# serialization is ever added, add its keeps here and re-run the release
# smoke check in docs/M7_RELEASE_GATE_CHECKLIST.md before shipping.

# Keep every native method declaration, in any class, by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the two JNI bridge classes whole: the native side calls into them
# and their public wrappers are the app's only path to the native engine.
-keep class com.codeair.effectcraft.engine.NativeProjectBridge {
    *;
}
-keep class com.codeair.effectcraft.engine.NativeSession {
    *;
}
