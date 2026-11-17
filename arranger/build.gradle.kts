plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir(rootProject.file("composeApp/src/commonMain/kotlin"))
        kotlin.include(
            "music/pinch/BuildArrangements.kt", "music/pinch/LaunchSound.kt", "music/pinch/StereoLoveMaster.kt",
            "music/pinch/OnTheFloorMaster.kt", "music/pinch/OnTheFloor.kt", "music/pinch/ArrangementScore.kt",
            "music/pinch/core/Chart.kt", "music/pinch/StereoLove.kt", "music/pinch/StereoLoveBand.kt",
            "music/pinch/WaveHeader.kt", "music/pinch/GypsyWoman.kt", "music/pinch/Levels.kt",
            "music/pinch/Stay.kt", "music/pinch/StayMaster.kt",
            "music/pinch/TurnDownForWhat.kt", "music/pinch/TurnDownMaster.kt",
            "music/pinch/DanceMaster.kt", "music/pinch/StudioPiano.kt", "music/pinch/StudioInstruments.kt",
        )
    }
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

val performance = rootProject.layout.projectDirectory.file("media/backing/stereo-love.m4a")
val assets = layout.buildDirectory.dir("generated/assets")
val encoder = File("/usr/bin/afconvert").takeIf { it.canExecute() } ?: System.getenv("PATH").orEmpty()
    .split(File.pathSeparatorChar).map { File(it, "ffmpeg") }.firstOrNull { it.isFile && it.canExecute() }

val generateArrangements = tasks.register<JavaExec>("generateArrangements") {
    group = "build"
    description = "Prepare the six full-length recordings and the launch sound."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("music.pinch.BuildArrangementsKt")
    inputs.file(performance).withPathSensitivity(PathSensitivity.RELATIVE)
    if (encoder != null) inputs.file(encoder).withPathSensitivity(PathSensitivity.NAME_ONLY)
    outputs.dir(assets)
    args(performance.asFile, assets.get().asFile, temporaryDir, encoder?.absolutePath.orEmpty())
}

val audioAssets = configurations.create("audioAssets") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(audioAssets.name, assets) { builtBy(generateArrangements) }
