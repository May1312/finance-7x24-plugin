plugins {
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2024.1.7")
        bundledPlugin("com.intellij.java")
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        version = "1.0.3"

        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
}
