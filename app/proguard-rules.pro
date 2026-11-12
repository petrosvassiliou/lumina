# Lumina ProGuard / R8 Rules

# Preserve source file & line numbers for diagnostics
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep all VideoLAN / libVLC 4.0 JNI classes, callbacks, tools, and native descriptors
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-dontwarn org.videolan.**

# Keep Room entities, DAOs, TypeConverters, and database schema implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep class app.lumina.player.data.local.** { *; }
-dontwarn androidx.room.paging.**

# Keep Lumina enums intact for DataStore String <-> Enum persistence (Enum.valueOf)
-keep enum app.lumina.player.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Google Play Core In-App Updates
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
