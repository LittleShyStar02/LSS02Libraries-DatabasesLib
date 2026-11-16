apply(plugin = "com.vanniktech.maven.publish")

description = "Cluster-based MyBatis configuration, HikariCP-backed connection pooling, and idempotent schema bootstrap per named database, on top of LSS02ConfigurationLib."

dependencies {
    api(libs.config.core)
    api(libs.mybatis)
    // Public API exposes HikariCP types (MetricsTrackerFactory), so it is an api dependency.
    api(libs.hikaricp)
    // Logging facade only: the consumer picks the implementation (Paper, Velocity and Bukkit already provide one).
    api(libs.slf4j.api)

    // Schema migrations: flyway-core already ships the H2 and SQLite support, the modules add MySQL/MariaDB and PostgreSQL.
    api(libs.flyway.core)
    runtimeOnly(libs.flyway.mysql)
    runtimeOnly(libs.flyway.postgresql)

    // JDBC drivers are NOT shipped: the library never references a driver class at compile time, so each
    // consumer declares (runtimeOnly) the driver of the engines it actually uses. See the README.

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.mysql.connector)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.mariadb.client)

    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"("org.testcontainers:junit-jupiter")
    "integrationTestImplementation"("org.testcontainers:mysql")
    "integrationTestImplementation"("org.testcontainers:postgresql")
    "integrationTestImplementation"("org.testcontainers:mariadb")
    "integrationTestRuntimeOnly"(libs.mysql.connector)
    "integrationTestRuntimeOnly"(libs.postgresql)
    "integrationTestRuntimeOnly"(libs.mariadb.client)
}
