apply(plugin = "com.vanniktech.maven.publish")

description = "Cross-database schema and data migration between two named databases in an LSS02DatabasesLib cluster, including different SQL dialects."

dependencies {
    api(project(":db-core"))

    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    testCompileOnly("org.projectlombok:lombok:1.18.38")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.38")

    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")

    "integrationTestImplementation"(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    "integrationTestImplementation"("org.testcontainers:junit-jupiter")
    "integrationTestImplementation"("org.testcontainers:mysql")
    "integrationTestImplementation"("org.testcontainers:postgresql")
    "integrationTestImplementation"("com.mysql:mysql-connector-j:9.3.0")
    "integrationTestImplementation"("org.postgresql:postgresql:42.7.7")
    "integrationTestImplementation"("org.xerial:sqlite-jdbc:3.49.1.0")
}
