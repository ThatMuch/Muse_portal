import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // X25519 et HKDF. Android 10 n'a pas X25519 dans ses API publiques.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Android fournit org.json ; sur la JVM des tests on prend l'artefact Maven.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    testLogging { events("failed", "skipped") }
}
