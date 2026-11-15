import ComposeApp
import SwiftUI

private struct GameScreen: UIViewControllerRepresentable {
    @Environment(\.scenePhase) private var phase

    func makeCoordinator() -> AppleMediaFactory { AppleMediaFactory() }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.setSceneActive(phase == .active)
        return MainViewControllerKt.MainViewController(factory: context.coordinator)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        context.coordinator.setSceneActive(phase == .active)
    }

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: AppleMediaFactory) {
        coordinator.setSceneActive(false)
    }
}

@main
struct PinchMusicApp: App {
    var body: some SwiftUI.Scene {
        WindowGroup {
            GameScreen()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}
