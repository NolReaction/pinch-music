import ComposeApp
import SwiftUI
import os

#if DEBUG
nonisolated enum PinchTiming {
    static let log = Logger(subsystem: "music.pinch", category: "PinchTiming")
    @MainActor static var launch: TimeInterval?
    static var now: TimeInterval { ProcessInfo.processInfo.systemUptime }
    static func ms(since start: TimeInterval) -> Int { Int(((now - start) * 1000).rounded()) }
}
#endif

private struct GameScreen: UIViewControllerRepresentable {
    @Environment(\.scenePhase) private var phase

    func makeCoordinator() -> AppleMediaFactory { AppleMediaFactory() }

    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG
        let start = PinchTiming.now
        #endif
        context.coordinator.setSceneActive(phase == .active)
        let controller = MainViewControllerKt.MainViewController(factory: context.coordinator)
        controller.view.backgroundColor = UIColor(named: "LaunchInk")
        #if DEBUG
        PinchTiming.log.notice("[PinchTiming] Create Compose controller: \(PinchTiming.ms(since: start)) ms")
        #endif
        return controller
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
    init() {
        #if DEBUG
        PinchTiming.launch = PinchTiming.now
        #endif
    }

    var body: some SwiftUI.Scene {
        WindowGroup {
            GameScreen()
                .background(Color("LaunchInk"))
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}
