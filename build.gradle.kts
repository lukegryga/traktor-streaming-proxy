plugins {
    kotlin("jvm") version "2.4.20"
    application
    kotlin("plugin.serialization").version("2.4.20")
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    val ktor_version = "3.1.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-xml:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-cbor:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-protobuf:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("io.ktor:ktor-network-tls-certificates:$ktor_version")
    implementation("org.slf4j:slf4j-log4j12:2.0.6")

    // protobuf-javalite ships the same com.google.protobuf.* classes as protobuf-java but without
    // descriptor support, and the two cannot coexist: whichever lands first on the classpath wins.
    // When the lite one does, librespot dies with NoSuchMethodError on AnyProto.getDescriptor().
    // The full runtime is a superset, so it serves both.
    implementation("com.github.teamnewpipe.NewPipeExtractor:extractor:v0.24.8") {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    implementation("com.google.protobuf:protobuf-java:4.31.1")
    implementation("com.github.librespot-org.librespot-java:librespot-lib:52a8c24215")
    implementation("com.github.0xf4b1:spotify-kt:275f290e64")
    implementation("com.github.0xf4b1:tidal-kt:v0.3.1")

    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("MainKt")
}

kotlin {
    // Pinned rather than inherited: the build runs on JDK 18 in Docker and JDK 27 on Windows,
    // and an unpinned target follows whichever JDK is present.
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The generated .bat lists every jar inline, which overruns cmd.exe's 8191 character command
// line and fails with "The input line is too long." A wildcard classpath stays short.
tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readText()
                .replace(Regex("set CLASSPATH=.*")) { """set CLASSPATH=%APP_HOME%\lib\*""" }
                // Config, data, logs and cert are all resolved relative to the working directory,
                // which is wherever the user happened to launch from - the bin folder when the
                // .bat is double clicked. Pin it to the install root.
                .replace(Regex("""for %%i in \("%APP_HOME%"\) do set APP_HOME=%%~fi""")) {
                    """for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi""" + System.lineSeparator() +
                        """cd /d "%APP_HOME%""""
                }
        )
    }
}
