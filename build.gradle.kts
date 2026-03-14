import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.math.BigDecimal
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

val kotlinxSerializationVersion = "1.6.3"
val junitJupiterVersion = "5.10.2"

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.sonarqube") version "7.2.3.7755"
    jacoco
}

group = "com.sonarpromptstudio"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
}

intellij {
    version.set(providers.gradleProperty("intellijVersion"))
    type.set("IC")
    plugins.set(listOf("com.intellij.java"))
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

tasks {
    val coveredClasses = fileTree(layout.buildDirectory.dir("instrumented/instrumentCode")) {
        exclude(
            "com/sonarpromptstudio/actions/**",
            "com/sonarpromptstudio/startup/**",
            "com/sonarpromptstudio/ui/**",
            "com/sonarpromptstudio/util/UiUtils*",
        )
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("")
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

    named("buildSearchableOptions") {
        enabled = false
    }

    named("check") {
        dependsOn(named("jacocoTestCoverageVerification"))
    }

    runIde {
        jvmArgs("-Didea.is.internal=true")
    }
}
