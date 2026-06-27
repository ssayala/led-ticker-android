# kotlinx.serialization keeps generated serializers via @Serializable; the
# default rules plus this keep the KnownDevice serializer through R8.
-keepclassmembers class io.github.ssayala.ledticker.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.ssayala.ledticker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
