import java.io.File

val sqliteTmpDir = File(rootDir, "sqlite_tmp")
if (!sqliteTmpDir.exists()) {
    sqliteTmpDir.mkdirs()
}
System.setProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bible3650"
include(":app")
