# Add project specific ProGuard rules here.

# kotlinx.serialization: keep serializers reachable via reflection-free lookup.
# https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class app.debunkax.**$$serializer { *; }
-keepclassmembers class app.debunkax.** {
    *** Companion;
}
-keepclasseswithmembers class app.debunkax.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.JsonElement {
    *;
}

# sherpa-onnx: the native library reads and writes these classes' fields by name through JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
