plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir(rootProject.file("composeApp/src/commonMain/kotlin"))
        kotlin.include(
            "music/pinch/BuildArrangements.kt", "music/pinch/LaunchSound.kt", "music/pinch/StereoLoveMaster.kt",
            "music/pinch/ArrangementScore.kt",
            "music/pinch/core/Chart.kt", "music/pinch/StereoLove.kt", "music/pinch/StereoLoveBand.kt",
            "music/pinch/BandArrangement.kt", "music/pinch/SongLibrary.kt",
            "music/pinch/Accompaniment.kt", "music/pinch/SamplePreview.kt",
        )
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

val roots = rootProject.layout.projectDirectory.dir("media/instruments")
val assets = layout.buildDirectory.dir("generated/assets")
val encoder = File("/usr/bin/afconvert").takeIf { it.canExecute() } ?: System.getenv("PATH").orEmpty()
    .split(File.pathSeparatorChar).map { File(it, "ffmpeg") }.firstOrNull { it.isFile && it.canExecute() }

val generateArrangements = tasks.register<JavaExec>("generateArrangements") {
    group = "build"
    description = "Render the bundled band parts and instrument performances from the shared scores."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("music.pinch.BuildArrangementsKt")
    inputs.file(rootProject.file("media/backing/stereo-love.m4a")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(roots).withPathSensitivity(PathSensitivity.RELATIVE)
    if (encoder != null) inputs.file(encoder).withPathSensitivity(PathSensitivity.NAME_ONLY)
    outputs.dir(assets)
    args(roots.asFile, assets.get().asFile, temporaryDir, encoder?.absolutePath.orEmpty())
}

val audioAssets = configurations.create("audioAssets") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(audioAssets.name, assets) { builtBy(generateArrangements) }
