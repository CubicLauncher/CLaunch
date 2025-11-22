plugins {
    id("java")
    id("maven-publish")
}

group = "com.cubiclauncher.claunch"
version = "1.0.0"

repositories {
    mavenCentral()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.cubiclauncher.claunch"
            artifactId = "claunch"
            version = "1.0.0"
        }
    }

    repositories {
        maven {
            val githubActor = System.getenv("USERNAME")
            val githubToken = System.getenv("TOKEN")

            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/CubicLauncher/CLaunch")

            credentials {
                username = githubActor
                password = githubToken
            }
        }
    }
}
