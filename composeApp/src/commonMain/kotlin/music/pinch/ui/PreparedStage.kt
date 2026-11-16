package music.pinch.ui

/** Preparation finishes before the stage and its geometry caches pass to the UI. */
internal sealed interface PreparedStage {
    class Orbit(val path: MarblePath) : PreparedStage {
        val meshes = OrbitMeshes(path)
    }
    class Cascade(val path: PlanarPath) : PreparedStage {
        val course = path.gpuCourse()
    }
}
