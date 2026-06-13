plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.github.cynicdog"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.azure:azure-storage-blob:12.27.1")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.shadowJar {
    archiveBaseName = "adls-mount"
    archiveClassifier = ""
    archiveVersion = ""
    manifest {
        attributes["Main-Class"] = "io.github.cynicdog.adls.Main"
    }
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
