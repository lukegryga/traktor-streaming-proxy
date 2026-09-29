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
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
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
                // Config, data, logs and cert resolve relative to the working directory, which is
                // the bin folder when the .bat is double clicked. It has to be changed on the
                // launch line itself: endlocal restores the directory, undoing an earlier cd, and
                // that line is expanded before endlocal runs.
                .replace("endlocal & \"%JAVA_EXE%\"", "endlocal & cd /d \"%APP_HOME%\" & \"%JAVA_EXE%\"")
        )
    }
}

// Packaging -------------------------------------------------------------------------------------

version = "1.0.0"

val appName = "TraktorProxy"
val appVendor = "traktor-streaming-proxy"
val appDescription = "Stream Spotify, YouTube and Tidal in Traktor DJ"

// The Start menu folder the shortcut goes in, so it reads as a name there rather than as the
// repository slug the vendor field carries.
val appMenuGroup = "Traktor Streaming Proxy"

// Identifies the product across versions: an installer with a different one installs beside the
// old app instead of replacing it, so this must never change.
val upgradeUuid = "8f2e3c14-6b9d-4a57-9e21-0d5c7a8b4f63"

val imageDir = layout.buildDirectory.dir("jpackage/image")
val installerDir = layout.buildDirectory.dir("jpackage/installer")

val jpackageExe: String = File(System.getProperty("java.home"), "bin/jpackage.exe")
    .takeIf { it.isFile }?.absolutePath ?: "jpackage"

/**
 * jpackage shells out to WiX's candle and light to build an installer, and finds them on PATH
 * only. The toolset does not put itself there, so the build locates it instead of asking everyone
 * who clones this to edit their environment.
 */
fun wixBin(): File? = listOf(File("C:/Program Files (x86)"), File("C:/Program Files"))
    .asSequence()
    .flatMap { root ->
        root.listFiles { f: File -> f.isDirectory && f.name.startsWith("WiX Toolset") }
            .orEmpty().asSequence()
    }
    .map { File(it, "bin") }
    .firstOrNull { File(it, "candle.exe").isFile }

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self contained Windows application image with its own runtime."
    dependsOn(tasks.named("installDist"))

    val input = layout.buildDirectory.dir("install/traktor-streaming-proxy/lib")
    val icon = layout.projectDirectory.file("src/main/resources/traktor-forwarder.ico")
    // Read from the jar task rather than spelled out: the name carries the version, so any
    // version bump would otherwise leave jpackage looking for a jar that is no longer there.
    val mainJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }.get()

    inputs.dir(input)
    inputs.file(icon)
    outputs.dir(imageDir.map { it.dir(appName) })

    // jpackage refuses to write over an existing image rather than replacing it.
    doFirst { delete(imageDir) }

    commandLine(
        jpackageExe,
        "--type", "app-image",
        "--name", appName,
        "--app-version", version.toString(),
        "--vendor", appVendor,
        "--description", appDescription,
        "--input", input.get().asFile.absolutePath,
        "--main-jar", mainJar,
        "--main-class", "MainKt",
        "--icon", icon.asFile.absolutePath,
        "--dest", imageDir.get().asFile.absolutePath,
        // No module is dropped here. librespot, Netty and the NewPipe extractor all load classes
        // reflectively, so a module list computed from the bytecode would be missing whatever only
        // reflection reaches, and the failure would come at runtime rather than at build time.
        //
        // --compress is left off deliberately. It takes the installed image from 148MB to 102MB,
        // but a zip compressed runtime is already dense, so the installer cannot squeeze it again
        // and grows from 71MB to 81MB. The download is the thing that ships, so disk loses.
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages"
    )
}

val jpackageInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the single file Windows installer. Needs the WiX Toolset."
    dependsOn(jpackageImage)

    outputs.dir(installerDir)

    doFirst {
        val wix = wixBin() ?: throw GradleException(
            "The WiX Toolset was not found. Install it with: winget install -e --id WiXToolset.WiXToolset"
        )
        environment("PATH", "${wix.absolutePath};${System.getenv("PATH")}")
        delete(installerDir)
        mkdir(installerDir)
    }

    commandLine(
        jpackageExe,
        "--type", "exe",
        "--name", appName,
        "--app-version", version.toString(),
        "--vendor", appVendor,
        "--description", appDescription,
        // Built from the image rather than from the jars again, so what is tested is what ships.
        "--app-image", imageDir.get().asFile.resolve(appName).absolutePath,
        "--dest", installerDir.get().asFile.absolutePath,
        "--win-upgrade-uuid", upgradeUuid,
        // Installs into Program Files, which costs one prompt at install time and buys the
        // separation the app is built around: the install folder is read only and the data folder
        // under %LOCALAPPDATA% survives both an uninstall and an upgrade.
        //
        // A per-user install cannot have that here. It defaults to %LOCALAPPDATA%\TraktorProxy,
        // the data folder itself, so the app would keep its settings, certificate and downloaded
        // library inside its own install directory and lose the lot on uninstall. Moving it to
        // Programs\TraktorProxy is what MSI wants for a per-user install, but WiX then fails
        // validation with ICE64 over the Programs folder it has no instruction to remove.
        //
        // The prompt is not a real cost: patching Traktor already asks for one.
        "--win-menu",
        "--win-menu-group", appMenuGroup,
        "--win-shortcut-prompt"
    )
}
