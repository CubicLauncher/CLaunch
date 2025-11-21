@file:Suppress("SpellCheckingInspection")

plugins {
    id("java")
}

group = "com.cubiclauncher.claunch"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("ch.qos.logback:logback-classic:1.5.21")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.google.code.gson:gson:2.13.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("claunch-" + version)
    from(sourceSets.main.get().output)
}
