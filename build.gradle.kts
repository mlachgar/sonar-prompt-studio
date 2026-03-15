import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.math.BigDecimal
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val kotlinxSerializationVersion = "1.6.3"
val junit4Version = "4.13.2"
val junitJupiterVersion = "5.10.2"
val junitPlatformVersion = "1.10.2"

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.13.0"
    id("org.sonarqube") version "7.2.3.7755"
    jacoco
}

group = "com.sonarpromptstudio"
version = "1.0.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    testImplementation("junit:junit:$junit4Version")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("intellijVersion"))
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

sonar {
    properties {
        property("sonar.projectKey", "mlachgar_sonar-prompt-studio")
        property("sonar.organization", "mlachgar")
        property("sonar.coverage.jacoco.xmlReportPaths", layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path)
        property(
            "sonar.coverage.exclusions",
            listOf(
                "src/main/kotlin/com/sonarpromptstudio/actions/**",
                "src/main/kotlin/com/sonarpromptstudio/startup/**",
                "src/main/kotlin/com/sonarpromptstudio/ui/**",
                "src/main/kotlin/com/sonarpromptstudio/util/UiUtils.kt",
            ).joinToString(","),
        )
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
        changeNotes = provider {
            """
            <p>Initial public release of Sonar Prompt Studio.</p>
            <ul>
              <li>Connect to SonarQube Cloud and self-hosted SonarQube Server</li>
              <li>Review findings inside IntelliJ IDEA</li>
              <li>Generate remediation prompts for Codex, Claude Code, and Qwen Code</li>
              <li>Discover Sonar project settings from common repository files</li>
            </ul>
            """.trimIndent()
        }
    }
}

tasks {
    val coveredClasses = fileTree(layout.buildDirectory.dir("instrumented/instrumentCode")) {
        exclude(
            "com/sonarpromptstudio/actions/**",
            "com/sonarpromptstudio/startup/**",
            "com/sonarpromptstudio/ui/**",
            "com/sonarpromptstudio/util/UiUtils*",
        )
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }

    withType<Test> {
        useJUnitPlatform()
        extensions.configure(JacocoTaskExtension::class) {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
        finalizedBy(named("jacocoTestReport"))
    }

    withType<JacocoReport> {
        dependsOn(test)
        classDirectories.setFrom(coveredClasses)
        sourceDirectories.setFrom(files("src/main/kotlin"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    jacocoTestCoverageVerification {
        dependsOn(test)
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal("0.90")
                }
            }
        }
        classDirectories.setFrom(coveredClasses)
    }

    named("check") {
        dependsOn(named("jacocoTestCoverageVerification"))
    }

    runIde {
        jvmArgs("-Didea.is.internal=true")
    }
}
