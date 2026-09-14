apply(plugin = "com.vanniktech.maven.publish")

description = "Cluster-based MyBatis configuration, HikariCP-backed connection pooling, and idempotent schema bootstrap per named database, on top of LSS02ConfigurationLib."

dependencies {
    api("io.github.littleshystar02:config-core:0.1.0-SNAPSHOT")
    api("org.mybatis:mybatis:3.5.19")
    implementation("com.zaxxer:HikariCP:6.3.0")

    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    testCompileOnly("org.projectlombok:lombok:1.18.38")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.38")

    testImplementation("org.mockito:mockito-core:5.22.0")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")

    "integrationTestImplementation"(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    "integrationTestImplementation"("org.testcontainers:junit-jupiter")
    "integrationTestImplementation"("org.testcontainers:mysql")
    "integrationTestImplementation"("org.testcontainers:postgresql")
    "integrationTestImplementation"("com.mysql:mysql-connector-j:9.3.0")
    "integrationTestImplementation"("org.postgresql:postgresql:42.7.7")
}
