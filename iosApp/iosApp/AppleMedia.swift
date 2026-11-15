import AVFoundation
import ComposeApp
import UIKit

@MainActor
final class AppleMediaFactory: NSObject, @preconcurrency AppleMediaFactoryProtocol {
    private let songImport = AppleSongImport()

    func createCascade() -> AppleCascadeSurface { AppleCascadeDisplay() }

    func pickAudio(listener: AppleAudioImportListener) { songImport.pick(listener) }

    func createMedia(instrument: String, observer: AppleInterruptListener) -> AppleMediaSession {
        NativeMedia(instrument: instrument, observer: observer)
    }
}

private struct AudioFailure: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

// The shared controller and UIKit notifications access the audio session on the main actor.
@MainActor
private final class NativeMedia: NSObject, @preconcurrency AppleMediaSession, AVAudioPlayerDelegate {
    private let instrument: String
    private var observer: AppleInterruptListener?
    private let audioSession = AVAudioSession.sharedInstance()
    private var voices: SampleVoices?
    private var backing: AVAudioPlayer?
    private var backingAsset: String?
    private var prepared = false
    private var savedTime: TimeInterval = 0
    private var backingVolume: Float = 1
    private(set) var backingEnded = false
    var backingPlaying: Bool { wantsPlayback && backing?.isPlaying == true }
    var backingPositionMs: Int64 {
        capturePosition()
        return Int64((savedTime * 1000).rounded())
    }
    private var roots = [Int32: Int32]()
    private var tokens = [NSObjectProtocol]()
    private var wantsPlayback = false
    private var sessionActive = false
    private var playbackOutputs: [String] = []
    private var closed = false
    private(set) var failure: String?

    init(instrument: String, observer: AppleInterruptListener) {
        self.instrument = instrument
        self.observer = observer
        super.init()
        observeSession()
    }

    func includeSample(midi: Int32, root: Int32) { roots[midi] = root }

    func prepare(backingAsset: String?) {
        guard !closed, !prepared, failure == nil else { return }
        self.backingAsset = backingAsset
        do {
            try createPlayers()
            prepared = true
            if UIApplication.shared.applicationState != .active { interrupt() }
        } catch {
            failure = error.localizedDescription
        }
    }

    private func createPlayers() throws {
        if voices == nil, !roots.isEmpty {
            voices = try SampleVoices(instrument: instrument, roots: roots, includeStrike: backingAsset != nil)
        }
        if backing == nil, let asset = backingAsset {
            guard let folder = Bundle.main.resourceURL else { throw AudioFailure("Отсутствуют ресурсы игры.") }
            let url = folder.appendingPathComponent("media").appendingPathComponent(asset)
            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.volume = backingVolume
            backing = player
        }
    }

    private func capturePosition() {
        guard wantsPlayback, let backing = backing else { return }
        // A stopped or invalidated decoder may already report zero in its interruption callback.
        savedTime = max(savedTime, backing.currentTime)
    }

    func activate() -> Bool {
        guard !closed, prepared, !backingEnded, failure == nil else { return false }
        if wantsPlayback { return true }
        guard UIApplication.shared.applicationState == .active else {
            interrupt()
            return false
        }
        do {
            if audioSession.category != .playback || audioSession.mode != .default {
                try audioSession.setCategory(.playback, mode: .default)
            }
            // Let iOS choose the I/O buffer for the route, including during screen capture.
            try audioSession.setActive(true)
            sessionActive = true
        } catch {
            // Another app or a call can still own audio when the user first tries to resume.
            interrupt()
            return false
        }
        do {
            try createPlayers()
            try voices?.start()
            if let backing = backing {
                backing.currentTime = min(savedTime, backing.duration)
                if savedTime > 0 { backing.volume = 0 }
                guard backing.play() else { throw AudioFailure("Не удалось начать аккомпанемент.") }
                if savedTime > 0 { backing.setVolume(backingVolume, fadeDuration: 0.025) }
            }
            playbackOutputs = audioSession.currentRoute.outputs.map(\.uid)
            wantsPlayback = true
            return true
        } catch {
            silence()
            failure = error.localizedDescription
            return false
        }
    }

    func setBackingVolume(gain: Float) {
        backingVolume = min(1, max(0, gain))
        backing?.volume = backingVolume
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self, weak player] in
            guard let self = self, !self.closed, let player = player, player === self.backing else { return }
            if flag { self.backingEnded = true }
            else { self.failure = "Воспроизведение аккомпанемента прервано." }
        }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        let message = error?.localizedDescription ?? "Не удалось прочитать аккомпанемент."
        Task { @MainActor [weak self, weak player] in
            guard let self = self, !self.closed, let player = player, player === self.backing else { return }
            self.failure = message
        }
    }

    func silence() {
        guard wantsPlayback || sessionActive else { return }
        capturePosition()
        wantsPlayback = false
        backing?.pause()
        voices?.silence()
        if sessionActive {
            sessionActive = false
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func note(midi: Int32, gain: Float) {
        if wantsPlayback { voices?.play(midi, gain: gain) }
    }
    func strike(gain: Float) {
        if wantsPlayback { voices?.strike(gain: gain) }
    }

    private func discardPlayers() {
        backing?.delegate = nil
        backing = nil
        voices = nil
    }

    func close() {
        guard !closed else { return }
        closed = true
        observer = nil
        tokens.forEach { NotificationCenter.default.removeObserver($0) }
        tokens.removeAll()
        silence()
        discardPlayers()
    }

    private func interrupt() {
        guard !closed else { return }
        silence()
        discardPlayers()
        observer?.interrupted()
    }

    private var outputStopped: Bool {
        !backingEnded && (voices?.engine.isRunning == false || backing?.isPlaying == false)
    }

    private func observeUIKit(_ name: Notification.Name, action: @escaping @MainActor (NativeMedia) -> Void) {
        let token = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) {
            [weak self] _ in
            MainActor.assumeIsolated {
                guard let self = self, !self.closed else { return }
                action(self)
            }
        }
        tokens.append(token)
    }

    private enum AudioEvent: Sendable {
        case interruption, routeChanged, reset
        case engineChanged(ObjectIdentifier)
    }

    private func observeAudio(_ name: Notification.Name, object: AnyObject? = nil,
                              event: @escaping @Sendable (Notification) -> AudioEvent?) {
        let token = NotificationCenter.default.addObserver(forName: name, object: object, queue: nil) {
            [weak self] notification in
            guard let change = event(notification) else { return }
            // Leave Core Audio's callback before interacting with or disposing of its engine.
            DispatchQueue.main.async { [weak self] in self?.handle(change) }
        }
        tokens.append(token)
    }

    private func observeSession() {
        observeUIKit(UIApplication.willResignActiveNotification) { media in media.interrupt() }
        observeUIKit(UIApplication.userDidTakeScreenshotNotification) { media in
            if media.wantsPlayback && media.outputStopped { media.interrupt() }
        }
        observeAudio(AVAudioSession.interruptionNotification, object: audioSession) { notification in
            let raw = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            return raw == AVAudioSession.InterruptionType.began.rawValue ? .interruption : nil
        }
        observeAudio(AVAudioSession.routeChangeNotification, object: audioSession) { _ in .routeChanged }
        observeAudio(.AVAudioEngineConfigurationChange) { notification in
            (notification.object as? AVAudioEngine).map { .engineChanged(ObjectIdentifier($0)) }
        }
        observeAudio(AVAudioSession.mediaServicesWereResetNotification) { _ in .reset }
    }

    private func handle(_ event: AudioEvent) {
        guard !closed else { return }
        switch event {
        case .interruption:
            interrupt()
        case .routeChanged:
            // Compare with the route used at play(). A queued notification about an earlier
            // route may arrive after the new graph is already playing on the correct output.
            let outputs = audioSession.currentRoute.outputs.map(\.uid)
            if wantsPlayback {
                if playbackOutputs != outputs || outputStopped { interrupt() }
            } else if playbackOutputs != outputs {
                discardPlayers()
            }
        case .engineChanged(let id):
            if let engine = voices?.engine, id == ObjectIdentifier(engine), !engine.isRunning {
                if wantsPlayback { interrupt() } else { discardPlayers() }
            }
        case .reset:
            // These objects belong to the old media server. Use the last observed cursor
            // and build a fresh graph only when the user chooses to continue.
            wantsPlayback = false
            sessionActive = false
            discardPlayers()
            observer?.interrupted()
        }
    }
}

private final class SampleVoices {
    let engine = AVAudioEngine()
    private var buffers = [Int32: AVAudioPCMBuffer]()
    private var contact: AVAudioPCMBuffer?
    private var nodes = [AVAudioPlayerNode]()
    private var next = 0

    init(instrument: String, roots: [Int32: Int32], includeStrike: Bool) throws {
        var source = [Int32: AVAudioPCMBuffer]()
        for root in Set(roots.values) {
            source[root] = try Self.readSample(String(root), folder: "media/instruments/\(instrument)")
        }
        if includeStrike { contact = try Self.readSample(instrument, folder: "media/feedback") }
        // Pretranspose each required note once. The tap handler only schedules a ready PCM buffer.
        for (midi, root) in roots {
            guard let pcm = source[root] else { throw AudioFailure("Нет звука для ноты \(midi).") }
            buffers[midi] = try Self.transpose(pcm, semitones: midi - root)
        }
        guard let format = source.values.first?.format else { throw AudioFailure("В мелодии нет нот.") }
        for _ in 0..<8 {
            let node = AVAudioPlayerNode()
            engine.attach(node)
            engine.connect(node, to: engine.mainMixerNode, format: format)
            nodes.append(node)
        }
    }

    private static func readSample(_ name: String, folder: String) throws -> AVAudioPCMBuffer {
        guard let url = Bundle.main.url(forResource: name, withExtension: "wav", subdirectory: folder) else {
            throw AudioFailure("Отсутствует сэмпл \(folder) / \(name).")
        }
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        guard file.length > 1, file.length <= Int64(UInt32.max), file.processingFormat.channelCount == 1,
              let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)) else {
            throw AudioFailure("Недопустимый формат сэмпла \(name).")
        }
        try file.read(into: buffer)
        return buffer
    }

    private static func transpose(_ input: AVAudioPCMBuffer, semitones: Int32) throws -> AVAudioPCMBuffer {
        if semitones == 0 { return input }
        let step = pow(2, Double(semitones) / 12)
        let length = Int(input.frameLength)
        let frames = Int(Double(length - 1) / step) + 1
        guard length > 1, let samples = input.floatChannelData?[0],
              let result = AVAudioPCMBuffer(pcmFormat: input.format, frameCapacity: AVAudioFrameCount(frames)),
              let output = result.floatChannelData?[0] else { throw AudioFailure("Не удалось подготовить ноту.") }
        for i in 0..<frames {
            let position = Double(i) * step
            let index = min(Int(position), length - 2)
            output[i] = samples[index] + Float(position - Double(index)) * (samples[index + 1] - samples[index])
        }
        result.frameLength = AVAudioFrameCount(frames)
        return result
    }

    func start() throws {
        if !engine.isRunning {
            engine.prepare()
            try engine.start()
        }
        for node in nodes where !node.isPlaying { node.play() }
    }

    func play(_ pitch: Int32, gain: Float) {
        if let buffer = buffers[pitch] { schedule(buffer, gain: gain) }
    }
    func strike(gain: Float) {
        if let buffer = contact { schedule(buffer, gain: gain) }
    }
    private func schedule(_ buffer: AVAudioPCMBuffer, gain: Float) {
        guard engine.isRunning else { return }
        let node = nodes[next]
        next = (next + 1) % nodes.count
        node.volume = min(1, max(0, gain))
        node.scheduleBuffer(buffer, at: nil, options: .interrupts, completionHandler: nil)
    }

    func silence() {
        nodes.forEach { $0.stop() }
        engine.stop()
        next = 0
    }
}
