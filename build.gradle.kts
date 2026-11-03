plugins { java }
group = "zypchat"
version = "1.0.0"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
tasks {
    compileJava { options.encoding = "UTF-8" }
    processResources { filteringCharset = "UTF-8" }
}
