-dontobfuscate

# Disable ServiceLoader reproducibility-breaking optimizations
-keep class kotlinx.coroutines.CoroutineExceptionHandler
-keep class kotlinx.coroutines.internal.MainDispatcherFactory

-dontwarn kotlinx.serialization.KSerializer
-dontwarn kotlinx.serialization.Serializable
-dontwarn org.slf4j.impl.StaticLoggerBinder

# On-device translation (ML Kit) registers everything it can do through classes named nowhere in the
# code: they appear only as text inside manifest meta-data, and are built by reflection through a
# no-argument constructor. firebase-components ships a rule that keeps those classes, but not that
# constructor, and R8's full mode (the default here) is free to drop a constructor nothing calls.
# The class then survives with no way to build it, translation finds no engine registered, and every
# translation fails in every build R8 shrinks, debug included.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# Offline translation (translate-kit, Mozilla's engine) ships no R8 rules of its own. Its native code
# reaches back into these classes by name: their native methods, and the constructors of the result
# types it builds. Renamed or dropped, the library fails to link or crashes on the first translation.
-keep class com.duckduckgo.translatekit.** { *; }
