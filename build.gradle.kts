/*
 * Copyright 2026 Martin Vysny
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

defaultTasks("clean", "build")

// The doc layer's checks ride on `check`, so `./gradlew` runs them. Skipped on
// Windows, where bash is not a given; the CI job of its own covers it there.
val verifyDesignTripwires by tasks.registering(Exec::class) {
    description = "Checks the doc layer: AGENTS.md and design/"
    group = "verification"
    onlyIf { !System.getProperty("os.name").startsWith("Windows") }
    commandLine("bash", "design/verify_design_tripwires.sh")
}
tasks.named("check") { dependsOn(verifyDesignTripwires) }

allprojects {
    group = "com.github.mvysny.tinymcpserver"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// The type-safe `libs` accessor is not visible inside `subprojects {}`, so the
// catalog is resolved through the public VersionCatalogsExtension instead.
val jspecify = extensions.getByType<VersionCatalogsExtension>()
    .named("libs").findLibrary("jspecify").get()

subprojects {

    apply {
        plugin("maven-publish")
        plugin("java")
        plugin("org.gradle.signing")
    }

    dependencies {
        // JSpecify nullness annotations (@NullMarked / @Nullable). Declared
        // compileOnly on purpose: they are CLASS-retention, so nothing needs
        // them at runtime, and they stay out of the published POM — consumers
        // inherit no transitive dependency.
        "compileOnly"(jspecify)
        "testCompileOnly"(jspecify)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            // Print each failed test by name (not just an aggregate) with full
            // stack trace, so CI logs name the failure inline.
            events = setOf(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showCauses = true
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        // --release (not source/targetCompatibility) is what actually enforces
        // the API floor: it compiles against that JDK's API signatures, so a
        // post-11 method fails the build instead of failing at the customer.
        // Tests are held to the same floor so they can run on a Java 11 JVM;
        // mcp-server's `testOfficial` set is the one documented exception.
        options.release = 11
    }

    // ── The Java 11 run leg ──────────────────────────────────────────────────
    // Compiling with --release 11 proves no post-11 API is *called*. It cannot
    // prove the jars load and run on an 11 VM — a v61 class pulled in by
    // shadowJar, or a newer API reached reflectively, would still pass. So the
    // test suite is re-run on a real Java 11 launcher.
    //
    // The toolchain is never auto-provisioned: if no JDK 11 is installed the
    // task is simply not registered, so a contributor without one still gets a
    // green build. Point Gradle at yours with -Porg.gradle.java.installations.paths=...
    // or the JDK11/JAVA_HOME_11_X64 env vars wired up in gradle.properties.
    val java11Launcher = runCatching {
        the<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(11))
        }.takeIf { it.isPresent }
    }.getOrNull()

    if (java11Launcher != null) {
        plugins.withType<JavaPlugin> {
            val testSources = extensions.getByType<SourceSetContainer>()["test"]
            tasks.register<Test>("testJava11") {
                description = "Re-runs the tests on a Java 11 JVM"
                group = "verification"
                testClassesDirs = testSources.output.classesDirs
                classpath = testSources.runtimeClasspath
                javaLauncher.set(java11Launcher)
                reports.html.outputLocation.set(layout.buildDirectory.dir("reports/testJava11"))
                reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/testJava11"))
            }
            tasks.named("check") {
                dependsOn(tasks.named("testJava11"))
            }
        }
    }
    // creates a reusable function which configures proper deployment to Maven Central
    ext["configureMavenCentral"] = { artifactId: String ->

        java {
            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<Javadoc> {
            isFailOnError = false
            (options as StandardJavadocDocletOptions).apply {
                addStringOption("Xdoclint:none", "-quiet")
                quiet()
            }
        }

        tasks.withType<JavaCompile> {
            options.isDeprecation = true
        }

        publishing {
            publications {
                create("mavenJava", MavenPublication::class.java).apply {
                    groupId = project.group.toString()
                    this.artifactId = artifactId
                    version = project.version.toString()
                    pom {
                        description = "A minimal, embeddable Model Context Protocol server in pure Java"
                        name = artifactId
                        url = "https://github.com/mvysny/tinymcpserver"
                        licenses {
                            license {
                                name = "The Apache License, Version 2.0"
                                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                                distribution = "repo"
                            }
                        }
                        developers {
                            developer {
                                id = "mavi"
                                name = "Martin Vysny"
                                email = "martin@vysny.me"
                            }
                        }
                        scm {
                            url = "https://github.com/mvysny/tinymcpserver"
                        }
                    }

                    from(components["java"])
                }
            }
        }

        signing {
            sign(publishing.publications["mavenJava"])
        }
    }
}

nexusPublishing {
    repositories {
        // see https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
