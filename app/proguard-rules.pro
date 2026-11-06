# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.ivynote.**$$serializer { *; }
-keepclassmembers class app.ivynote.** { *** Companion; }
-keepclasseswithmembers class app.ivynote.** { kotlinx.serialization.KSerializer serializer(...); }

# The editor page calls into this bridge by name.
-keepclassmembers class app.ivynote.android.editor.EditorBridge { @android.webkit.JavascriptInterface <methods>; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
