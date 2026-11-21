# Yomic Mobile — ProGuard / R8 Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Kotlinx Serialization (DTOs, FeedbackWebhookPayload, FetchResponseDto, Repo JSON)
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class id.yomic.mobile.**$$serializer { *; }
-keepclassmembers class id.yomic.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class id.yomic.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class id.yomic.mobile.** { *; }

# WorkManager CoroutineWorker reflection constructors (LibraryUpdateWorker, ChapterDownloadWorker)
-keep class id.yomic.mobile.worker.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room Database, generated _Impl classes, DAOs, and Entities
-keep class id.yomic.mobile.data.local.** extends androidx.room.RoomDatabase { *; }
-keep class id.yomic.mobile.data.local.**_Impl { *; }
-keep class id.yomic.mobile.data.local.**Entity { *; }

# Coil 3 custom CBZ Fetcher & Keyer
-keep class id.yomic.mobile.data.image.CbzPageData { *; }
-keep class id.yomic.mobile.data.image.CbzPageFetcher** { *; }
-keep class id.yomic.mobile.data.image.CbzPageKeyer { *; }

# Backup SQLite Codec & Models
-keep class id.yomic.mobile.data.backup.** { *; }

# QuickJS JNI bindings
-keep class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**

# OkHttp, Jsoup & Coroutines optional platform references
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
