plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "LSS02DatabasesLib"

include(
    "db-core",
    "db-migration"
)
