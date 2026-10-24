plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":corpus-core"))
    api(platform(libs.spring.cloud.aws.bom))
    api("software.amazon.awssdk:dynamodb")
}
