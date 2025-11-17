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

    implementation("com.google.code.gson:gson:2.13.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("build:jar") {
    archiveBaseName.set("CLaunch")
    from(sourceSets.main.get().output)
}
