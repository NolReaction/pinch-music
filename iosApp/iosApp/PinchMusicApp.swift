import ComposeApp
import SwiftUI

private struct GameScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(factory: AppleMediaFactory())
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
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
