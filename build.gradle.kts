import java.util.Properties
import org.gradle.internal.os.OperatingSystem

plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
}

val libraryProperties = Properties().apply {
    load(rootProject.file("release.properties").inputStream())
}

version = if (project.hasProperty("githubReleaseTag")) {
    project.property("githubReleaseTag").toString().removePrefix("v")
} else {
    libraryProperties.getProperty("prettyVersion")
}
group = "art.datanet"

val libName = "DataNet"
val userHome = System.getProperty("user.home")
val currentOS = OperatingSystem.current()
val sketchbookLocation = when {
    currentOS.isMacOsX -> if (file("$userHome/Documents/Processing/sketchbook").isDirectory) {
        "$userHome/Documents/Processing/sketchbook"
    } else {
        "$userHome/Documents/Processing"
    }
    currentOS.isWindows -> {
        val documents = if (file("$userHome/My Documents").isDirectory) {
            "$userHome/My Documents"
        } else {
            "$userHome/Documents"
        }
        if (file("$documents/Processing/sketchbook").isDirectory) "$documents/Processing/sketchbook" else "$documents/Processing"
    }
    else -> "$userHome/sketchbook"
}

repositories {
    mavenCentral()
    maven { url = uri("https://jogamp.org/deployment/maven/") }
}

dependencies {
    compileOnly("org.processing:core:4.3.1")
    testImplementation("org.processing:core:4.3.1")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set(libName)
    archiveClassifier.set("")
    archiveVersion.set("")
}

tasks.javadoc {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
}

val releaseRoot = layout.projectDirectory.dir("release")
val releaseDirectory = releaseRoot.dir(libName)

val writeLibraryProperties by tasks.registering(WriteProperties::class) {
    group = "processing"
    destinationFile = project.file("library.properties")
    property("name", libraryProperties.getProperty("name"))
    property("version", libraryProperties.getProperty("version"))
    property("prettyVersion", project.version)
    property("authors", libraryProperties.getProperty("authors"))
    property("url", libraryProperties.getProperty("url"))
    property("categories", libraryProperties.getProperty("categories"))
    property("sentence", libraryProperties.getProperty("sentence"))
    property("paragraph", libraryProperties.getProperty("paragraph"))
    property("minRevision", libraryProperties.getProperty("minRevision"))
    property("maxRevision", libraryProperties.getProperty("maxRevision"))
}

val stageRelease by tasks.registering(Sync::class) {
    group = "processing"
    dependsOn(tasks.build, tasks.javadoc, writeLibraryProperties)
    into(releaseDirectory)
    from(tasks.jar) { into("library") }
    from(tasks.javadoc) { into("reference") }
    from("library.properties")
    from("README.md")
    from("LICENSE")
    from("examples") { into("examples") }
    from("src/main/java") { into("src") }
}

val packageRelease by tasks.registering(Zip::class) {
    group = "processing"
    dependsOn(stageRelease)
    archiveFileName.set("$libName.zip")
    destinationDirectory.set(releaseRoot.asFile)
    from(releaseDirectory) { into(libName) }
}

val writeContributionMetadata by tasks.registering {
    group = "processing"
    dependsOn(writeLibraryProperties)
    inputs.file("library.properties")
    outputs.file(releaseRoot.file("$libName.txt"))
    doLast {
        copy {
            from("library.properties")
            into(releaseRoot)
            rename("library.properties", "$libName.txt")
        }
    }
}

val duplicateZipToPdex by tasks.registering {
    group = "processing"
    dependsOn(packageRelease)
    inputs.file(releaseRoot.file("$libName.zip"))
    outputs.file(releaseRoot.file("$libName.pdex"))
    doLast {
        copy {
            from(releaseRoot.file("$libName.zip"))
            into(releaseRoot)
            rename("$libName.zip", "$libName.pdex")
        }
    }
}

tasks.register("buildReleaseArtifacts") {
    group = "processing"
    dependsOn(packageRelease, writeContributionMetadata, duplicateZipToPdex)
}

tasks.register<Sync>("deployToProcessingSketchbook") {
    group = "processing"
    dependsOn(stageRelease)
    from(releaseDirectory)
    into("$sketchbookLocation/libraries/$libName")
}
