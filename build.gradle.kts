// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Fix for SQLite temp directory issues on Windows (common with Room KSP)
if (System.getProperty("os.name").lowercase().contains("win")) {
    val sqliteTmpDir = file("sqlite_tmp")
    if (!sqliteTmpDir.exists()) {
        sqliteTmpDir.mkdirs()
    }
    System.setProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    tasks.withType<Zip>().configureEach {
        isZip64 = true
    }
}
