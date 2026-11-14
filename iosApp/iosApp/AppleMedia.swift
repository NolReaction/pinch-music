import AVFoundation
import ComposeApp
import UIKit

@MainActor
final class AppleMediaFactory: NSObject, @preconcurrency AppleMediaFactoryProtocol {
    func readChart() -> String? {
        guard let url = resource("demo-chart", "json") else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    func createMedia(preview: Bool, observer: AppleInterruptListener) -> AppleMediaSession {
        NativeMedia(preview: preview, observer: observer)
    }
}

private func resource(_ name: String, _ ext: String, folder: String = "media") -> URL? {
    Bundle.main.url(forResource: name, withExtension: ext, subdirectory: folder)
}

private final class PlayerSurface: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

private struct MediaFailure: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

// The shared Kotlin controller calls this object on the main thread. Notification delivery
// is also serialized there, including the audio session's interruption and route callbacks.
@MainActor
private final class NativeMedia: NSObject, @preconcurrency AppleMediaSession {
    private let preview: Bool
    private var observer: AppleInterruptListener?
    private let surface = PlayerSurface()
    private let player = AVPlayer()
    private let audioSession = AVAudioSession.sharedInstance()
    private var voices: SampleVoices?
    private var pitches = Set<Int32>()
    private var tokens = [NSObjectProtocol]()
    private var prepared = false
    private var ready = false
    private var ended = false
    private var playbackRequested = false
    private var lastPlayingSample: (position: Double, uptime: Double)?
    private var closed = false
    private var prerollRequested = false
    private var sessionWasActivated = false
    private var failure: String?
    private var previewVolume: Float = 0.8

    var videoView: UIView { surface }

    init(preview: Bool, observer: AppleInterruptListener) {
        self.preview = preview
        self.observer = observer
        super.init()
        surface.backgroundColor = .black
        surface.isUserInteractionEnabled = false
        surface.playerLayer.videoGravity = .resizeAspect
        surface.playerLayer.player = player
        player.isMuted = !preview
        player.actionAtItemEnd = .pause
        observeSession()
    }

    func includeSample(midi: Int32) { pitches.insert(midi) }

    func prepare() {
        guard !closed, !prepared, failure == nil else { return }
        let filename = preview ? "demo-preview" : "demo-ball-muted"
        do {
            guard let url = resource(filename, "mp4") else {
                throw MediaFailure("Видео \(filename).mp4 отсутствует в приложении.")
            }
            let item = AVPlayerItem(url: url)
            observe(AVPlayerItem.didPlayToEndTimeNotification, object: item) { media, _ in
                media.ended = true
            }
            observe(AVPlayerItem.failedToPlayToEndTimeNotification, object: item) { media, notification in
                let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                media.fail(error?.localizedDescription ?? "Не удалось дочитать видео.")
            }
            observe(AVPlayerItem.timeJumpedNotification, object: item) { media, _ in
                media.checkTimeJump()
            }
            observe(AVPlayerItem.playbackStalledNotification, object: item) { media, _ in
                if media.ready { media.interrupt() }
            }
            player.replaceCurrentItem(with: item)
            player.volume = previewVolume

            // No audio-session activation while the application is in the background. Decoding
            // the small bundled PCM bank has no dependency on a currently connected output.
            if !preview { voices = try SampleVoices(pitches: pitches) }
            if UIApplication.shared.applicationState == .active {
                try activateAudio()
            } else {
                interrupt()
            }
            prepared = true
        } catch {
            fail(error.localizedDescription)
        }
    }

    func snapshot() -> Playback {
        let item = player.currentItem
        if failure == nil, item?.status == .failed || player.status == .failed {
            fail(item?.error?.localizedDescription ?? player.error?.localizedDescription ?? "Ошибка декодирования видео.")
        }

        if prepared, !ready, failure == nil, item?.status == .readyToPlay {
            if surface.playerLayer.isReadyForDisplay {
                ready = true
            } else if !prerollRequested, player.status == .readyToPlay,
                      UIApplication.shared.applicationState == .active {
                // Decode the first frame without advancing the timeline.
                prerollRequested = true
                player.preroll(atRate: 1) { _ in }
            }
        }

        let seconds = player.currentTime().seconds
        let uptime = ProcessInfo.processInfo.systemUptime
        let playing = player.timeControlStatus == .playing && player.rate > 0
        if playbackRequested, playing, seconds.isFinite {
            lastPlayingSample = (position: seconds, uptime: uptime)
        } else {
            lastPlayingSample = nil
        }
        // AVPlayer can report a paused rate just before the item-end notification is delivered.
        if ready, playbackRequested, player.timeControlStatus == .paused,
           let duration = item?.duration.seconds, duration.isFinite, duration > 0,
           seconds.isFinite, seconds >= duration - 0.001 {
            ended = true
        }
        return Playback(
            ready: ready, playing: playing,
            ended: ended, positionMs: seconds.isFinite ? max(0, Int64((seconds * 1_000).rounded())) : 0,
            observedUptimeMs: Int64(uptime * 1_000), error: failure
        )
    }

    func play() {
        guard ready, !ended, !closed, failure == nil else { return }
        guard UIApplication.shared.applicationState == .active else {
            interrupt()
            return
        }
        do {
            try activateAudio()
            playbackRequested = true
            player.play()
        } catch {
            fail(error.localizedDescription)
        }
    }

    func pause() {
        playbackRequested = false
        lastPlayingSample = nil
        player.pause()
        voices?.silence()
    }

    func note(midi: Int32, gain: Float) {
        guard !closed, !preview, player.timeControlStatus == .playing else { return }
        voices?.play(midi, gain: gain)
    }

    func setPreviewVolume(volume: Float) {
        previewVolume = min(1, max(0, volume))
        if preview { player.volume = previewVolume }
    }

    func close() {
        guard !closed else { return }
        closed = true
        observer = nil
        tokens.forEach { NotificationCenter.default.removeObserver($0) }
        tokens.removeAll()
        pause()
        player.cancelPendingPrerolls()
        player.replaceCurrentItem(with: nil)
        surface.playerLayer.player = nil
        voices?.shutdown()
        voices = nil
        if sessionWasActivated {
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private func activateAudio() throws {
        if audioSession.category != .playback {
            try audioSession.setCategory(.playback, mode: .default)
        }
        // A hardware route may ignore this preferred duration.
        try? audioSession.setPreferredIOBufferDuration(0.005)
        try audioSession.setActive(true)
        sessionWasActivated = true
        try voices?.start()
    }

    private func checkTimeJump() {
        guard !preview, ready, playbackRequested, !ended, failure == nil,
              player.timeControlStatus == .playing, player.rate == 1,
              let previous = lastPlayingSample else { return }
        let position = player.currentTime().seconds
        guard position.isFinite else { return }
        // The notification contains neither endpoint. Accept any forward progress physically
        // possible since the last observation (including a stall) and one judgement window
        // of timestamp correction. Only a notified jump outside that envelope invalidates play.
        let elapsed = max(0, ProcessInfo.processInfo.systemUptime - previous.uptime)
        let delta = position - previous.position
        if delta < -0.120 || delta > elapsed + 0.120 {
            fail("Позиция видео изменилась скачком. Начните попытку заново.")
        }
    }

    private func fail(_ message: String) {
        if failure == nil { failure = message }
        pause()
    }

    private func interrupt() {
        guard !closed else { return }
        pause()
        observer?.interrupted()
    }

    private func observe(_ name: Notification.Name, object: AnyObject? = nil,
                         action: @escaping @MainActor (NativeMedia, Notification) -> Void) {
        let token = NotificationCenter.default.addObserver(forName: name, object: object, queue: .main) {
            [weak self] notification in
            MainActor.assumeIsolated {
                guard let self = self, !self.closed else { return }
                action(self, notification)
            }
        }
        tokens.append(token)
    }

    private func observeSession() {
        observe(UIApplication.willResignActiveNotification) { media, _ in media.interrupt() }
        observe(AVAudioSession.interruptionNotification, object: audioSession) { media, notification in
            let raw = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            if raw == AVAudioSession.InterruptionType.began.rawValue { media.interrupt() }
        }
        observe(AVAudioSession.routeChangeNotification, object: audioSession) { media, notification in
            guard let raw = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
            let previous = notification.userInfo?[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription
            let changed = previous.map { $0.outputs.map(\.uid) != media.audioSession.currentRoute.outputs.map(\.uid) } ?? false
            if reason == .oldDeviceUnavailable || reason == .newDeviceAvailable || changed {
                media.interrupt()
            }
        }
        observe(AVAudioSession.mediaServicesWereResetNotification, object: nil) { media, _ in
            media.fail("Система перезапустила звук. Начните попытку заново.")
        }
    }
}

// Each WAV is decoded once per attempt. Eight independent nodes preserve the decay of
// neighboring hits, including two hits that use the very same buffer.
private final class SampleVoices {
    private let engine = AVAudioEngine()
    private var buffers = [Int32: AVAudioPCMBuffer]()
    private var nodes = [AVAudioPlayerNode]()
    private var next = 0

    init(pitches: Set<Int32>) throws {
        for pitch in pitches {
            guard let url = resource("midi-\(pitch)", "wav", folder: "media/samples") else {
                throw MediaFailure("Отсутствует сэмпл MIDI \(pitch).")
            }
            let file = try AVAudioFile(forReading: url)
            guard file.length > 0, file.length <= Int64(UInt32.max),
                  let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat,
                                                frameCapacity: AVAudioFrameCount(file.length)) else {
                throw MediaFailure("Не удалось загрузить сэмпл MIDI \(pitch).")
            }
            try file.read(into: buffer)
            guard buffer.frameLength > 0 else { throw MediaFailure("Пустой сэмпл MIDI \(pitch).") }
            buffers[pitch] = buffer
        }
        guard let format = buffers.values.first?.format else { throw MediaFailure("В мелодии нет сэмплов.") }
        guard buffers.values.allSatisfy({ $0.format.isEqual(format) }) else {
            throw MediaFailure("Форматы сэмплов мелодии различаются.")
        }
        for _ in 0..<8 {
            let node = AVAudioPlayerNode()
            engine.attach(node)
            engine.connect(node, to: engine.mainMixerNode, format: format)
            nodes.append(node)
        }
    }

    func start() throws {
        if !engine.isRunning {
            engine.prepare()
            try engine.start()
        }
    }

    func play(_ pitch: Int32, gain: Float) {
        guard engine.isRunning, let buffer = buffers[pitch] else { return }
        let node = nodes[next]
        next = (next + 1) % nodes.count
        node.stop()
        node.volume = min(1, max(0, gain))
        node.scheduleBuffer(buffer, completionHandler: nil)
        node.play()
    }

    func silence() {
        nodes.forEach { $0.stop() }
    }

    func shutdown() {
        silence()
        engine.stop()
    }
}
