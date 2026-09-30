# kotlinx.serialization: mantém os serializers gerados das classes do protocolo (net/Model.kt)
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class dev.partykit.r0usis.festasync.net.**$$serializer { *; }
-keepclassmembers class dev.partykit.r0usis.festasync.net.** { *** Companion; }
-keepclasseswithmembers class dev.partykit.r0usis.festasync.net.** { kotlinx.serialization.KSerializer serializer(...); }
# o player do YouTube chama métodos Kotlin a partir do JavaScript (@JavascriptInterface)
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
