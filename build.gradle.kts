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

    // Worth keeping current: YouTube breaks older extractors, and 0.24.8 had stopped being able to
    // resolve a stream at all, failing every download with "The page needs to be reloaded".
    //
    // The coordinate is the repository root rather than its extractor module. Newer tags build with
    // the Kotlin DSL and JitPack publishes only one artifact for them, which carries the same
    // classes and declares the same dependencies.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
        // protobuf-javalite ships the same com.google.protobuf.* classes as protobuf-java but
        // without descriptor support, and the two cannot coexist: whichever lands first on the
        // classpath wins. When the lite one does, librespot dies with NoSuchMethodError on
        // AnyProto.getDescriptor(). The full runtime is a superset, so it serves both.
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }

    // Matched to the newest generated code on the classpath, not chosen freely. protobuf 4.x
    // gencode refuses a runtime older than itself, and the extractor's playlist continuation
    // classes are generated against 4.35.1; librespot's older gencode is happy on a newer runtime,
    // so this is the one version that satisfies both.
    implementation("com.google.protobuf:protobuf-java:4.35.1")
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
    // Pinned rather than inherited: an unpinned target follows whichever JDK happens to be
    // installed, and the floor is what anyone running the zip needs.
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Packaging -------------------------------------------------------------------------------------

version = "1.0.3"

val appName = "TraktorProxy"

val stagingDir = layout.buildDirectory.dir("staged/$appName")

/**
 * One artifact: a portable zip of the jars plus the .cmd launchers in src/main/dist.
 *
 * No runtime is bundled. A jpackage image carrying ALL-MODULE-PATH came to 188MB installed and an
 * 85MB installer, of which only these 24MB of jars were ours, and it dragged in the WiX Toolset,
 * an administrator prompt and MSI's refusal to reinstall the same version. Requiring an installed
 * Java 17 costs one prerequisite and removes all four.
 */
distributions {
    main {
        contents {
            // Held back for the archive only - see portableZip.
            exclude("portable.txt")
            // For the shortcut the icon has to exist as a file; the tray reads it from the jar.
            from(layout.projectDirectory.file("src/main/resources/traktor-forwarder.ico"))
        }
    }
}

/**
 * The folder that ships, staged so it can be run and tested before it is zipped.
 *
 * `installDist` would be the obvious home for this, but it refuses to write into a folder with no
 * `bin/<project name>` in it, and the start scripts that would put one there are exactly what this
 * packaging drops. A plain Sync has no such opinion.
 *
 * `build/staged` rather than the `build/portable` this used to use: that one was staged with
 * portable.txt in it, so anyone who ran the app from there had the app write its settings,
 * credentials and library into a directory that Sync empties on the next build.
 */
val portableDir = tasks.register<Sync>("portableDir") {
    group = "distribution"
    description = "Stages the portable folder in build/staged, ready to run."
    dependsOn("stagingIsClean")
    into(stagingDir)
    with(distributions.getByName("main").contents)
}

/**
 * Sync deletes whatever it did not put there, which is the right behaviour for build output and the
 * wrong one for a data folder. Nothing staged makes the app write here any more - without
 * portable.txt it keeps its data under %LOCALAPPDATA% - but a config.properties dropped in by hand
 * is enough to change that, so the one case that would cost someone their settings is checked.
 *
 * Its own task rather than a doFirst on the staging: Gradle snapshots a task's destination before
 * running that task's actions, so a check inside it never gets the chance.
 */
tasks.register("stagingIsClean") {
    group = "verification"
    description = "Refuses to stage over a folder that holds someone's data."
    val dir = stagingDir
    doLast {
        val data = listOf("config.properties", "app.lock", "cert", "data", "library", "logs")
            .map { dir.get().asFile.resolve(it) }
            .filter { it.exists() }
        if (data.isNotEmpty()) throw GradleException(
            "${dir.get().asFile} holds what looks like a live install (${data.joinToString { it.name }}). " +
                "Staging would delete it. Quit the app, move that folder somewhere outside build/, " +
                "and build again."
        )
    }
}

/**
 * The one artifact. Zipped from the staged folder rather than assembled again, so what ships is
 * what a `portableDir` run can be tested from.
 *
 * The folder inside the archive carries no version: unzipping a newer release over an existing one
 * then replaces it in place, and the settings, credentials and library sitting in that folder
 * survive. A versioned folder would land beside it and leave all of that behind.
 */
val portableZip = tasks.register<Zip>("portableZip") {
    group = "distribution"
    description = "Builds the portable zip. Needs no runtime, no installer and no WiX."
    from(portableDir) { into(appName) }
    // Added here and not to the staged folder: this marker is what makes the app keep its data
    // beside itself, and build output is the one place that must never accumulate any.
    from(layout.projectDirectory.file("src/main/dist/portable.txt")) { into(appName) }
    archiveFileName = "$appName-$version.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

// All three replaced by the pair above, and nothing here wants a tar.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
tasks.named("installDist") { enabled = false }

// The launchers in src/main/dist replace these. The generated .bat needed patching to survive its
// own classpath length, could not start without a console window, and said nothing useful when Java
// was missing; the .sh has no audience on a Windows only build.
tasks.named("startScripts") { enabled = false }
