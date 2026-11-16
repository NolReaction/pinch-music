package music.pinch.ui

/** Preparation finishes before the stage and its geometry caches pass to the UI. */
internal sealed interface PreparedStage {
    class Orbit(val path: MarblePath, theme: StageTheme = StageTheme.Stereo) : PreparedStage {
        val meshes = OrbitMeshes(path, theme)
    }
    class Cascade(val path: PlanarPath) : PreparedStage {
        val course = path.gpuCourse()
    }
}
