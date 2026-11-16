package music.pinch.ui

/** Only immutable route geometry crosses from the preparation worker to the UI. */
internal sealed interface PreparedStage {
    class Orbit(val path: MarblePath) : PreparedStage
    class Cascade(val path: PlanarPath) : PreparedStage {
        val course = path.gpuCourse()
    }
}
