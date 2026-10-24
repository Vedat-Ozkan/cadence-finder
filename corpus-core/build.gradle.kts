plugins {
    `java-library`
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

application {
    mainClass = "cadencefinder.corpus.CorpusCli"
}
