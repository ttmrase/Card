# kotlinx.serialization keeps generated serializers reachable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.cardforge.** {
    *** Companion;
}
-keepclasseswithmembers class com.cardforge.** {
    kotlinx.serialization.KSerializer serializer(...);
}
