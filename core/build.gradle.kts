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
    // X25519 and HKDF. Android 10 does not have X25519 in its public APIs.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Android provides org.json; on the test JVM the Maven artifact is used.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    // Texts follow the device language: the tests expect French, whatever the machine's own language.
    systemProperty("user.language", "fr")
    testLogging { events("failed", "skipped") }
}
