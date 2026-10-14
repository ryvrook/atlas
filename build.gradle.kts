plugins {
    java
}

group = "dev.ryvrook"
version = "1.5.0"

val hytaleChannel = (project.findProperty("hytale_channel") as String?) ?: "release"
val hytaleVersion = (project.findProperty("hytale_version") as String?) ?: "0.7.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "hytale-$hytaleChannel"
        url = uri("https://maven.hytale.com/$hytaleChannel")
    }
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:$hytaleVersion")
    testImplementation("com.hypixel.hytale:Server:$hytaleVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The version above is the only place it is set. The manifest gets it through processResources and
// AtlasVersion reads it from a generated resource.
val generatedResources = layout.buildDirectory.dir("generated/atlas-resources")
val generateVersionResource by tasks.registering {
    val versionText = project.version.toString()
    val outDir = generatedResources
    inputs.property("version", versionText)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("dev/ryvrook/atlas/http/version.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(versionText)
    }
}

sourceSets {
    main {
        resources.srcDir(generateVersionResource)
    }
}

tasks.processResources {
    val versionText = project.version.toString()
    inputs.property("version", versionText)
    filesMatching("manifest.json") {
        filter { line -> line.replace("@version@", versionText) }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.jar {
    archiveBaseName.set("Atlas")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val installMod by tasks.registering(Copy::class) {
    group = "hytale"
    description = "Builds the mod and copies it into the local Hytale UserData/Mods folder."
    dependsOn(tasks.jar)
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val defaultMods = when {
        os.contains("mac") -> "$home/Library/Application Support/Hytale/UserData/Mods"
        os.contains("win") -> (System.getenv("APPDATA") ?: "$home/AppData/Roaming") + "/Hytale/UserData/Mods"
        else -> "$home/.local/share/Hytale/UserData/Mods"
    }
    val target = (project.findProperty("modsDir") as String?) ?: defaultMods
    from(tasks.jar.flatMap { it.archiveFile })
    into(target)
}
