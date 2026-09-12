# Keep rules for future R8 enablement (minifyEnabled is still false; nothing here is active yet).

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ir.talayar.app.**$$serializer { *; }
-keepclassmembers class ir.talayar.app.** { *** Companion; }
-keepclasseswithmembers class ir.talayar.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# ---- In-app update channel -------------------------------------------------
# Minification is still off (isMinifyEnabled = false); these rules only matter if
# R8 is ever enabled. Release metadata is parsed reflectively by kotlinx.serialization
# and UpdateErrorKind drives the user-facing copy, so their shapes must survive.
-keep class ir.talayar.app.data.remote.ReleaseDto { *; }
-keep class ir.talayar.app.data.remote.ReleaseAssetDto { *; }
-keepclassmembers enum ir.talayar.app.domain.model.UpdateErrorKind { *; }
-keepclassmembers class ir.talayar.app.domain.model.AppUpdate { *; }

# OkHttp / Okio optional providers
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
