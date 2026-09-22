/*
 * Copyright (c) 2024, LlemonDuck <napkinorton@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.ByteArrayOutputStream
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    java
    `java-library`
    `maven-publish`
    pmd
    alias(libs.plugins.lombok)

    id("net.runelite.runelite-gradle-plugin.assemble")
    id("net.runelite.runelite-gradle-plugin.index")
    id("net.runelite.runelite-gradle-plugin.jarsign")
}

lombok.version = libs.versions.lombok.get()

java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    api("net.runelite:runelite-api:${project.version}")
    implementation(project(":jshell"))
    runtimeOnly("net.runelite:injected-client:${project.version}")

    api(libs.rl.http.api)
    api(libs.rl.discord)
    api(libs.rl.awt)
    compileOnly(libs.rl.orange)

    api(libs.slf4j.api)
    api(libs.logback.classic) {
        exclude("org.slf4j", "slf4j-api")
    }
    api(libs.jopt)
    api(libs.guava) {
        exclude("com.google.code.findbugs", "jsr305")
        exclude("com.google.errorprone", "error_prone_annotations")
        exclude("com.google.j2objc", "j2objc-annotations")
        exclude("org.codehaus.mojo", "animal-sniffer-annotations")
    }
    api(variantOf(libs.guice.core) { classifier("no_aop") }) {
        exclude("com.google.guava", "guava")
    }
    api(libs.gson)
    api(libs.flatlaf.core)
    implementation(libs.flatlaf.extras)
    api(libs.commons.text)
    api(libs.jna.core)
    api(libs.jna.platform)
    api(libs.findbugs)
    compileOnly(libs.jetbrains.annotations)
    api(libs.protobuf)
    api(libs.lwjgl.core)
    api(libs.lwjgl.opengl)
    api(libs.lwjgl.opencl)

    for (platform in listOf(
        "linux",
        "linux-arm64",
        "macos",
        "macos-arm64",
        "windows-x86",
        "windows",
        "windows-arm64",
    )) {
        runtimeOnly(variantOf(libs.lwjgl.core) { classifier("natives-$platform") })
        runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier("natives-$platform") })
    }

    testImplementation(libs.junit)
    testImplementation(libs.hamcrest)
    testImplementation(libs.mockito)
    testImplementation(libs.guice.testlib)
    testImplementation(libs.guice.grapher)
    testImplementation(libs.okhttp.mockserver)
}

val shadowJar = tasks.register<Jar>("shadowJar") {
    dependsOn(configurations.runtimeClasspath)
    manifest {
        attributes["Main-Class"] = "net.runelite.client.RuneLite"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { it.map { if (it.isDirectory) it else zipTree(it) } })

    exclude(
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "**/module-info.class"
    )

    group = BasePlugin.BUILD_GROUP
    archiveClassifier = "shadow"
    archiveFileName = project.name + "-" + project.version + "-shaded.jar"
}
tasks.assemble { dependsOn(shadowJar) }

// --- Native packaging (jpackage) ---
// For someone who just wants to download-and-run this fork rather than build it themselves:
// jpackage bundles the shaded jar with a private JRE into a real native app, so the recipient
// never installs Java separately. jpackage itself needs a JDK 14+ to run (the app is still built
// against Java 11) - the toolchain launcher below auto-downloads and caches one under
// ~/.gradle/jdks the first time these tasks run (see the foojay-resolver-convention plugin in
// settings.gradle.kts), no sudo/system-wide install needed.
//
// jpackage cannot cross-compile: it only ever produces an artifact for the OS it's running on
// (Linux -> .deb here). A Windows .exe/.msi or macOS .dmg/.pkg needs this same task run ON that
// OS - e.g. a GitHub Actions build matrix - see CLAUDE.md's packaging section.
val jpackageToolchains = extensions.getByType<JavaToolchainService>()
val jpackageJdk = jpackageToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(17)
}

val jpackageAppName = (findProperty("jpackage.appName") as String?) ?: "RuneLite PvP Tools"
val jpackageVendor = (findProperty("jpackage.vendor") as String?) ?: "RuneLite PvP Tools"
// jpackage (and Debian) require a strictly numeric x.y.z version - drop the "-SNAPSHOT" suffix.
val jpackageAppVersion = project.version.toString().substringBefore("-")
val jpackageIcon = file("src/main/resources/net/runelite/client/ui/runelite_128.png")

fun jpackageExecutable(): String
{
    val javaExecutable = jpackageJdk.get().executablePath.asFile
    val jpackageName = if (OperatingSystem.current().isWindows) "jpackage.exe" else "jpackage"
    return javaExecutable.resolveSibling(jpackageName).absolutePath
}

val jpackageInputDir = layout.buildDirectory.dir("jpackage/input")

// jpackage bundles every jar in --input, not just --main-jar - staging the shaded jar alone into
// its own directory (rather than pointing --input at build/libs, which also holds the plain,
// -sources and -javadoc jars) keeps the packaged app from carrying ~15MB of dead weight it'll
// never load.
fun stageJpackageInput(jarFile: java.io.File): java.io.File
{
    val dir = jpackageInputDir.get().asFile
    dir.deleteRecursively()
    dir.mkdirs()
    jarFile.copyTo(dir.resolve(jarFile.name))
    return dir
}

val jpackageImageDir = layout.buildDirectory.dir("jpackage/image")
tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Builds a self-contained app image (bundled JRE, no installer, no separate Java needed) via jpackage."
    dependsOn(shadowJar)

    val jarFile = shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    outputs.dir(jpackageImageDir)

    doFirst {
        val dest = jpackageImageDir.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()
        val inputDir = stageJpackageInput(jarFile.get().asFile)

        commandLine(
            jpackageExecutable(),
            "--type", "app-image",
            "--input", inputDir.absolutePath,
            "--main-jar", jarFile.get().asFile.name,
            "--main-class", "net.runelite.client.RuneLite",
            "--name", jpackageAppName,
            "--app-version", jpackageAppVersion,
            "--vendor", jpackageVendor,
            "--dest", dest.absolutePath,
            "--icon", jpackageIcon.absolutePath
        )
    }
}

val jpackageInstallerDir = layout.buildDirectory.dir("jpackage/installer")
tasks.register<Exec>("jpackageInstaller") {
    group = "distribution"
    description = "Builds a native installer (.deb on Linux; run on Windows/macOS for .exe/.msi or .dmg/.pkg) via jpackage - double-click to install, no Java required."
    dependsOn(shadowJar)

    val jarFile = shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    outputs.dir(jpackageInstallerDir)

    doFirst {
        val dest = jpackageInstallerDir.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()

        val os = OperatingSystem.current()
        val installerType = (findProperty("jpackage.type") as String?) ?: when
        {
            os.isLinux -> "deb"
            os.isWindows -> "exe"
            os.isMacOsX -> "dmg"
            else -> throw GradleException("jpackageInstaller doesn't know a default installer type for ${os.name} - pass -Pjpackage.type=...")
        }
        val inputDir = stageJpackageInput(jarFile.get().asFile)

        val args = mutableListOf(
            jpackageExecutable(),
            "--type", installerType,
            "--input", inputDir.absolutePath,
            "--main-jar", jarFile.get().asFile.name,
            "--main-class", "net.runelite.client.RuneLite",
            "--name", jpackageAppName,
            "--app-version", jpackageAppVersion,
            "--vendor", jpackageVendor,
            "--dest", dest.absolutePath
        )
        // Only .png is valid input to --icon; a Windows/macOS build needs its own .ico/.icns
        // (not provided yet) and will fall back to jpackage's generic default icon without one.
        if (os.isLinux)
        {
            args += listOf("--icon", jpackageIcon.absolutePath, "--linux-shortcut")
        }
        commandLine(args)
    }
}

publishing {
    publications {
        create<MavenPublication>("runelite-client") {
            from(components["java"])
            artifact(shadowJar) { classifier = "shaded" }
        }
    }
}

val assemble = tasks.withType<net.runelite.gradle.assemble.AssembleTask> {
    scriptDirectory = file("src/main/scripts")
    outputDirectory = sourceSets.main.map { File(it.output.resourcesDir, "runelite") }
    longSupport = true
}

tasks.withType<net.runelite.gradle.index.IndexTask> {
    archiveOverlayDirectory = assemble.single().outputDirectory
    indexFile = archiveOverlayDirectory.file("index")
}

tasks.processResources {
    inputs.property("projectVersion", project.version)

    val commit = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        standardOutput = commit
    }

    val dirty = ByteArrayOutputStream()
    exec {
        commandLine("git", "status", "--short")
        standardOutput = dirty
    }

    filesMatching("net/runelite/client/runelite.properties") {
        filter { it.replace("\${project.version}", project.version.toString()) }
        filter { it.replace("\${git.commit.id.abbrev}", commit.toString().trim()) }
        filter { it.replace("\${git.dirty}", dirty.toString().isNotBlank().toString()) }
    }
}

tasks.compileJava {
    options.isFork = true
}

tasks.jar {
    exclude("**/.clang-format")
}

pmd {
    toolVersion = "7.2.0"
    ruleSetFiles("./pmd-ruleset.xml")
    isConsoleOutput = true
    incrementalAnalysis = true
    isIgnoreFailures = false
    threads = Runtime.getRuntime().availableProcessors()
}
tasks.pmdMain {
    exclude("**/RuntimeTypeAdapterFactory.java")
    exclude("**/net/runelite/client/party/Party.java")
}
tasks.pmdTest { enabled = false }

tasks.checkstyleMain {
    exclude("net/runelite/client/util/RuntimeTypeAdapterFactory.java") // vendored
    exclude("net/runelite/client/party/Party.java") // generated by protobuf
}

tasks.withType<Test> {
    systemProperty("glslang.path", providers.gradleProperty("glslangPath").getOrElse(""))
}

tasks.javadoc {
    title = "RuneLite Client ${project.version} API"
}
