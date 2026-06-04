# Add project specific ProGuard rules here.

# ---------------------------------------------------------------------------
# Crash reporting: keep source file names and line numbers in stack traces
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# #17: Strip verbose/debug log calls in release builds.
# R8 treats these methods as having no side effects and removes all call sites.
# Log.e / Log.w are retained so production errors are still visible in logcat.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ---------------------------------------------------------------------------
# Backup serialization (kotlinx.serialization): keep the backup data classes used
# for JSON import/export. The kotlinx-serialization runtime ships its own consumer
# rules to retain the generated serializers; these keeps guard the model classes.
# ---------------------------------------------------------------------------
-keep class com.Bible3650.www.data.ProgressBackup { *; }
-keep class com.Bible3650.www.data.ReadingListBackup { *; }
-keep class com.Bible3650.www.data.AudioSourceBackup { *; }
-keep class com.Bible3650.www.data.TextTranslationBackup { *; }
-keep class com.Bible3650.www.data.local.ReadingListEntity { *; }
-keep class com.Bible3650.www.data.local.ListBookEntity { *; }
-keep class com.Bible3650.www.data.local.AudioSourceEntity { *; }
-keep class com.Bible3650.www.data.local.BookMappingEntity { *; }
-keep class com.Bible3650.www.data.local.BibleTranslationEntity { *; }
-keep class com.Bible3650.www.data.local.BibleTextEntity { *; }