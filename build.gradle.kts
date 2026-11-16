import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.vanniktech.publish) apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    // Compilation always uses the Java 17 toolchain; -PtestJavaVersion=21 runs the tests on another JDK
    // (CI matrix) to check the library on the Java versions its consumers run.
    val testJavaVersion = providers.gradleProperty("testJavaVersion").map { it.toInt() }
    val toolchains = extensions.getByType<JavaToolchainService>()

    tasks.withType<Test> {
        useJUnitPlatform()
        if (testJavaVersion.isPresent) {
            javaLauncher.set(toolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(testJavaVersion.get()))
            })
        }
    }

    // Unit tests only: the Testcontainers integration tests are reported separately when run.
    tasks.named<Test>("test") {
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    // Same bar as LSS02ConfigurationLib: at least 80% of the lines covered by the unit tests, enforced by
    // `check` (and therefore by `build` and the CI). The Testcontainers tests are not counted here.
    tasks.withType<JacocoCoverageVerification> {
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.withType<JacocoCoverageVerification>())
    }

    tasks.withType<Jar> {
        manifest {
            attributes(
                "Automatic-Module-Name" to "io.github.littleshystar02.lss02." + project.name.replace("-", ".")
            )
        }
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    val sourceSets = extensions.getByType<SourceSetContainer>()
    val integrationTest = sourceSets.create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }

    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

    tasks.register<Test>("integrationTest") {
        description = "Runs Testcontainers-backed integration tests. Requires a running Docker daemon."
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
    }

    afterEvaluate {
        configure<MavenPublishBaseExtension> {
            configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
            publishToMavenCentral(automaticRelease = false)
            signAllPublications()

            pom {
                name.set(project.name)
                // Lazy: modules set their description after applying the plugin.
                description.set(providers.provider {
                    requireNotNull(project.description?.takeIf { it.isNotBlank() }) {
                        "${project.path} must declare a description, Maven Central requires it in the POM"
                    }
                })
                inceptionYear.set("2026")
                url.set("https://github.com/LittleShyStar02/LSS02Libraries-DatabasesLib")

                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }

                developers {
                    developer {
                        id.set("LittleShyStar02")
                        name.set("Vincenzo Cutolo")
                        url.set("https://github.com/LittleShyStar02")
                    }
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/LittleShyStar02/LSS02Libraries-DatabasesLib/issues")
                }

                scm {
                    url.set("https://github.com/LittleShyStar02/LSS02Libraries-DatabasesLib")
                    connection.set("scm:git:https://github.com/LittleShyStar02/LSS02Libraries-DatabasesLib.git")
                    developerConnection.set("scm:git:git@github.com:LittleShyStar02/LSS02Libraries-DatabasesLib.git")
                }
            }
        }
    }
}
