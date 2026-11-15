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
private final class NativeMedia: NSObject, @preconcurrency AppleMediaSession, @preconcurrency AVAudioPlayerDelegate {
    private let instrument: String
    private var observer: AppleInterruptListener?
    private let audioSession = AVAudioSession.sharedInstance()
    private var voices: SampleVoices?
    private var backing: AVAudioPlayer?
    private var backingVolume: Float = 1
    private(set) var backingEnded = false
    var backingPlaying: Bool { backing?.isPlaying ?? false }
    var backingPositionMs: Int64 { Int64(((backing?.currentTime ?? 0) * 1000).rounded()) }
    private var roots = [Int32: Int32]()
    private var tokens = [NSObjectProtocol]()
    private var wantsPlayback = false
    private var sessionWasActivated = false
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
        guard !closed, voices == nil, backing == nil, failure == nil else { return }
        do {
            if !roots.isEmpty {
                voices = try SampleVoices(instrument: instrument, roots: roots, includeStrike: backingAsset != nil)
            }
            if let asset = backingAsset {
                guard let folder = Bundle.main.resourceURL else { throw AudioFailure("Отсутствуют ресурсы игры.") }
                let url = folder.appendingPathComponent("media").appendingPathComponent(asset)
                let player = try AVAudioPlayer(contentsOf: url)
                player.delegate = self
                player.volume = backingVolume
                guard player.prepareToPlay() else { throw AudioFailure("Не удалось подготовить аккомпанемент.") }
                backing = player
            }
            if UIApplication.shared.applicationState != .active { interrupt() }
        } catch {
            failure = error.localizedDescription
        }
    }

    func activate() -> Bool {
        guard !closed, !backingEnded, voices != nil || backing != nil, failure == nil else { return false }
        guard UIApplication.shared.applicationState == .active else {
            interrupt()
            return false
        }
        do {
            if audioSession.category != .playback { try audioSession.setCategory(.playback, mode: .default) }
            try? audioSession.setPreferredIOBufferDuration(0.005)
            try audioSession.setActive(true)
            sessionWasActivated = true
            try voices?.start()
            if let backing = backing, !backing.isPlaying && !backing.play() {
                throw AudioFailure("Не удалось начать аккомпанемент.")
            }
            wantsPlayback = true
            return true
        } catch {
            failure = error.localizedDescription
            return false
        }
    }

    func setBackingVolume(gain: Float) {
        backingVolume = min(1, max(0, gain))
        backing?.volume = backingVolume
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard !closed, player === backing else { return }
        if flag { backingEnded = true }
        else { failure = "Воспроизведение аккомпанемента прервано." }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        guard !closed, player === backing else { return }
        failure = error?.localizedDescription ?? "Не удалось прочитать аккомпанемент."
    }

    func silence() {
        wantsPlayback = false
        backing?.pause()
        voices?.silence()
    }
    func note(midi: Int32, gain: Float) {
        if wantsPlayback { voices?.play(midi, gain: gain) }
    }
    func strike(gain: Float) {
        if wantsPlayback { voices?.strike(gain: gain) }
    }

    func close() {
        guard !closed else { return }
        closed = true
        observer = nil
        tokens.forEach { NotificationCenter.default.removeObserver($0) }
        tokens.removeAll()
        backing?.delegate = nil
        backing?.stop()
        backing = nil
        voices?.shutdown()
        voices = nil
        if sessionWasActivated {
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private func interrupt() {
        guard !closed else { return }
        silence()
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
            if reason == .oldDeviceUnavailable || reason == .newDeviceAvailable || changed { media.interrupt() }
        }
        observe(.AVAudioEngineConfigurationChange) { media, notification in
            if media.wantsPlayback, let changed = notification.object as? AVAudioEngine,
               changed === media.voices?.engine, !changed.isRunning {
                media.interrupt()
            }
        }
        observe(AVAudioSession.mediaServicesWereResetNotification) { media, _ in
            media.failure = "Система перезапустила звук. Начните попытку заново."
            media.silence()
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
        let node = nodes[next]
        next = (next + 1) % nodes.count
        node.volume = min(1, max(0, gain))
        node.scheduleBuffer(buffer, at: nil, options: .interrupts, completionHandler: nil)
    }

    func silence() {
        nodes.forEach { $0.stop() }
        if engine.isRunning { engine.pause() }
    }
    func shutdown() {
        silence()
        engine.stop()
    }
}
