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
# translation fails in release while debug, which shrinks nothing, works perfectly.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
