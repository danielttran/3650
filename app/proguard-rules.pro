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
# Gson: keep data classes used for backup serialization so field names are not
# renamed by R8 and JSON import/export continues to work after minification.
# ---------------------------------------------------------------------------
-keep class com.Bible3650.www.data.ProgressBackup { *; }
-keep class com.Bible3650.www.data.ReadingListBackup { *; }
-keep class com.Bible3650.www.data.AudioSourceBackup { *; }
-keep class com.Bible3650.www.data.local.ReadingListEntity { *; }
-keep class com.Bible3650.www.data.local.ListBookEntity { *; }
-keep class com.Bible3650.www.data.local.AudioSourceEntity { *; }
-keep class com.Bible3650.www.data.local.BookMappingEntity { *; }