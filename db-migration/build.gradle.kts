apply(plugin = "com.vanniktech.maven.publish")

description = "Cross-database schema and data migration between two named databases in an LSS02DatabasesLib cluster, including different SQL dialects."

dependencies {
    api(project(":db-core"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.mysql.connector)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.mariadb.client)

    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"("org.testcontainers:junit-jupiter")
    "integrationTestImplementation"("org.testcontainers:mysql")
    "integrationTestImplementation"("org.testcontainers:postgresql")
    "integrationTestImplementation"("org.testcontainers:mariadb")
    "integrationTestImplementation"(libs.sqlite.jdbc)
    "integrationTestRuntimeOnly"(libs.mysql.connector)
    "integrationTestRuntimeOnly"(libs.postgresql)
    "integrationTestRuntimeOnly"(libs.mariadb.client)
}
