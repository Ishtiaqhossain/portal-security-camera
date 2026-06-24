# Keep WebRTC native bridge classes (accessed via JNI).
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep all JNI entry points (native methods + their declaring classes) so R8
# obfuscation can't break the name-based JNI lookup.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# OkHttp / Okio (ship their own consumer rules; silence optional-dep warnings).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines internals referenced reflectively / via ServiceLoader.
-dontwarn kotlinx.coroutines.**
