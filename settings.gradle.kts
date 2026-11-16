plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "LSS02DatabasesLib"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // SimpleYAML (transitive dependency of config-core) is published only on JitPack: restrict this
        // repository to its group so no other dependency can be resolved from it (dependency confusion).
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupAndSubgroups("com.github.Carleslc") }
        }
    }
}

include(
    "db-core",
    "db-migration"
)
