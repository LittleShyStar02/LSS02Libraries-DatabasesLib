import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        // Transitive dependency of config-core (Simple-YAML is only distributed via JitPack)
        maven("https://jitpack.io")
    }
}

subprojects {
    apply(plugin = "java-library")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
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
                description.set(project.description)
                url.set("https://github.com/LittleShyStar02/LSS02DatabasesLib")

                licenses {
                    license {
                        name.set("PolyForm Noncommercial License 1.0.0")
                        url.set("https://polyformproject.org/licenses/noncommercial/1.0.0")
                    }
                }

                developers {
                    developer {
                        id.set("LittleShyStar02")
                        name.set("Vincenzo Cutolo")
                        url.set("https://github.com/LittleShyStar02")
                    }
                }

                scm {
                    url.set("https://github.com/LittleShyStar02/LSS02DatabasesLib")
                    connection.set("scm:git:https://github.com/LittleShyStar02/LSS02DatabasesLib.git")
                    developerConnection.set("scm:git:git@github.com:LittleShyStar02/LSS02DatabasesLib.git")
                }
            }
        }
    }
}
