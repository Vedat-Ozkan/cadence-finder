plugins {
    java
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
    implementation(project(":search-core"))
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

val lambdaZip by tasks.registering(Zip::class) {
    archiveFileName = "search-lambda.zip"
    from(tasks.compileJava)
    from(tasks.processResources)
    into("lib") { from(configurations.runtimeClasspath) }
}

tasks.build { dependsOn(lambdaZip) }
