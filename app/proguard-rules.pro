# ── JNI ──────────────────────────────────────────────────────────────
# Keep LoraJNI and its callback interfaces (called from native C++)
-keep class com.dark.lora.LoraJNI { *; }
-keep class com.dark.lora.LoraJNI$LogCallback { *; }
-keep class com.dark.lora.LoraJNI$StreamCallback { *; }

# ── Kotlinx Serialization ───────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dark.trainer.models.** { *; }
-keepclassmembers class com.dark.trainer.models.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Supabase / Ktor ─────────────────────────────────────────────────
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ── Compose (handled by default rules, but keep ViewModel factories) ─
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ── Debug info ──────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
