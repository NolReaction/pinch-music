import AVFoundation
import ComposeApp
import UIKit

@MainActor
final class AppleMediaFactory: NSObject, @preconcurrency AppleMediaFactoryProtocol {
    private let songImport = AppleSongImport()
    private weak var current: NativeMedia?
    private var sceneActive = false

    func setSceneActive(_ active: Bool) {
        guard active != sceneActive else { return }
        sceneActive = active
        if !active { current?.interrupt() }
    }

    func createCascade() -> AppleCascadeSurface { AppleCascadeDisplay() }

    func pickAudio(listener: AppleAudioImportListener) { songImport.pick(listener) }

    func createMedia(instrument: String, observer: AppleInterruptListener) -> AppleMediaSession {
        current?.close()
        let media = NativeMedia(instrument: instrument, observer: observer) { [weak self] in
            self?.sceneActive == true && UIApplication.shared.applicationState == .active
        }
        current = media
        return media
    }
}

private struct AudioFailure: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

// The shared controller and UIKit notifications access the audio session on the main actor.
@MainActor
private final class NativeMedia: NSObject, @preconcurrency AppleMediaSession {
    private let instrument: String
    private var observer: AppleInterruptListener?
    private let foreground: () -> Bool
    private let audioSession = AVAudioSession.sharedInstance()
    private var graph: GameAudioGraph?
    private var backingAsset: String?
    private var savedFrame: AVAudioFramePosition = 0
    private var sampleRate = 1.0
    private var backingVolume: Float = 1
    private(set) var backingEnded = false
    var backingPlaying: Bool { wantsPlayback && graph?.backingIsPlaying == true }
    var backingPositionMs: Int64 {
        capturePosition()
        return Int64((Double(savedFrame) * 1000 / sampleRate).rounded())
    }
    private var roots = [Int32: Int32]()
    private var tokens = [NSObjectProtocol]()
    private var prepared = false
    private var wantsPlayback = false
    private var sessionActive = false
    private var outputPorts: [String] = []
    private var outputRate = 0.0
    private var mediaServicesAvailable = true
    private var closed = false
    private(set) var failure: String?

    init(instrument: String, observer: AppleInterruptListener, foreground: @escaping () -> Bool) {
        self.instrument = instrument
        self.observer = observer
        self.foreground = foreground
        super.init()
        observeSession()
    }

    func includeSample(midi: Int32, root: Int32) { roots[midi] = root }

    func prepare(backingAsset: String?) {
        guard !closed, !prepared, failure == nil else { return }
        self.backingAsset = backingAsset
        prepared = true
        guard foreground(), mediaServicesAvailable else { interrupt(); return }
        do { _ = try makeGraph() } catch { failure = error.localizedDescription }
    }

    private func makeGraph() throws -> GameAudioGraph {
        if let graph { return graph }
        let newGraph = try GameAudioGraph(instrument: instrument, roots: roots, backingAsset: backingAsset)
        sampleRate = newGraph.backingRate
        graph = newGraph
        return newGraph
    }

    private func capturePosition() {
        if wantsPlayback, let frame = graph?.currentBackingFrame { savedFrame = max(savedFrame, frame) }
    }

    func activate() -> Bool {
        guard !closed, prepared, !backingEnded, failure == nil else { return false }
        guard foreground(), mediaServicesAvailable else { interrupt(); return false }
        if wantsPlayback { return true }
        do {
            if audioSession.category != .playback || audioSession.mode != .default {
                try audioSession.setCategory(.playback, mode: .default)
            }
            try audioSession.setActive(true)
            sessionActive = true
        } catch {
            interrupt()
            return false
        }
        do {
            let current = try makeGraph()
            try current.start(from: savedFrame, volume: backingVolume) { [weak self, weak current] in
                Task { @MainActor [weak self, weak current] in
                    guard let self, let current, self.graph === current, self.wantsPlayback else { return }
                    // Core Audio may also complete a scheduled segment when hardware is interrupted.
                    self.capturePosition()
                    if self.foreground(), current.engine.isRunning, current.reachedEnd(at: self.savedFrame) {
                        self.savedFrame = current.backingLength
                        self.backingEnded = true
                    } else {
                        self.interrupt()
                    }
                }
            }
            outputPorts = audioSession.currentRoute.outputs.map(\.uid)
            outputRate = audioSession.sampleRate
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
        graph?.setBackingVolume(backingVolume)
    }

    func silence() {
        capturePosition()
        wantsPlayback = false
        // Detach ownership before stopping: stop() can complete scheduled buffers.
        let previous = graph
        graph = nil
        previous?.stop()
        if sessionActive {
            sessionActive = false
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func note(midi: Int32, gain: Float) {
        if wantsPlayback && foreground() { graph?.play(midi, gain: gain) }
    }
    func strike(gain: Float) {
        if wantsPlayback && foreground() { graph?.strike(gain: gain) }
    }

    func close() {
        guard !closed else { return }
        closed = true
        observer = nil
        tokens.forEach { NotificationCenter.default.removeObserver($0) }
        tokens.removeAll()
        silence()
    }

    func interrupt() {
        guard !closed else { return }
        silence()
        observer?.interrupted()
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
        case interruption, routeChanged, lost, reset
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
        for name in [UIScene.willDeactivateNotification, UIScene.didEnterBackgroundNotification,
                     UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
            observeUIKit(name) { $0.interrupt() }
        }
        observeUIKit(UIApplication.userDidTakeScreenshotNotification) { media in
            // A system capture can disturb the output while isRunning stays true.
            // Resume from the saved musical position with fresh hardware objects.
            if media.wantsPlayback { media.interrupt() }
        }
        observeAudio(AVAudioSession.interruptionNotification, object: audioSession) { notification in
            let raw = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            return raw == AVAudioSession.InterruptionType.began.rawValue ? .interruption : nil
        }
        observeAudio(AVAudioSession.routeChangeNotification, object: audioSession) { _ in .routeChanged }
        observeAudio(.AVAudioEngineConfigurationChange) { notification in
            (notification.object as? AVAudioEngine).map { .engineChanged(ObjectIdentifier($0)) }
        }
        observeAudio(AVAudioSession.mediaServicesWereLostNotification) { _ in .lost }
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
            if wantsPlayback && (outputPorts != outputs || outputRate != audioSession.sampleRate || graph?.running != true) {
                interrupt()
            }
        case .engineChanged(let id):
            if let current = graph, ObjectIdentifier(current.engine) == id, wantsPlayback && !current.running {
                interrupt()
            }
        case .lost, .reset:
            // Orphaned audio objects must not be queried after loss of the media server.
            if case .lost = event { mediaServicesAvailable = false } else { mediaServicesAvailable = true }
            wantsPlayback = false
            sessionActive = false
            graph = nil
            observer?.interrupted()
        }
    }
}

/// Backing, pitched notes and taps share one output mixer and one hardware clock.
private final class GameAudioGraph {
    let engine = AVAudioEngine()
    private let backingNode = AVAudioPlayerNode()
    private let track: AVAudioFile?
    private var startFrame: AVAudioFramePosition = 0
    private var presentationDelay = 0.0
    private var buffers = [Int32: AVAudioPCMBuffer]()
    private var contact: AVAudioPCMBuffer?
    private var nodes = [AVAudioPlayerNode]()
    private var next = 0

    var backingRate: Double { track?.processingFormat.sampleRate ?? 1 }
    var backingLength: AVAudioFramePosition { track?.length ?? 0 }
    var backingIsPlaying: Bool { track != nil && backingNode.isPlaying && engine.isRunning }
    var running: Bool { engine.isRunning && (track == nil || backingNode.isPlaying) }

    func reachedEnd(at frame: AVAudioFramePosition) -> Bool {
        // The last UI sample can predate the hardware's played-back callback by a
        // decoder polling interval plus the output pipeline latency.
        let tolerance = AVAudioFramePosition((presentationDelay + 0.10) * backingRate)
        return frame >= backingLength - tolerance
    }

    var currentBackingFrame: AVAudioFramePosition? {
        guard track != nil, let renderTime = backingNode.lastRenderTime,
              let time = backingNode.playerTime(forNodeTime: renderTime), time.sampleRate > 0 else { return nil }
        // Player time is rendered audio. Account for the downstream mixer/device
        // latency so the displayed strike follows what has reached the listener.
        let audibleSeconds = max(0, Double(time.sampleTime) / time.sampleRate - presentationDelay)
        return min(backingLength, startFrame + AVAudioFramePosition(audibleSeconds * backingRate))
    }

    init(instrument: String, roots: [Int32: Int32], backingAsset: String?) throws {
        if let asset = backingAsset, let folder = Bundle.main.resourceURL {
            let url = folder.appendingPathComponent("media").appendingPathComponent(asset)
            track = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        } else if backingAsset != nil {
            throw AudioFailure("Отсутствуют ресурсы игры.")
        } else {
            track = nil
        }
        if let track {
            guard track.length > 0, track.length <= Int64(UInt32.max) else {
                throw AudioFailure("Недопустимая длина аккомпанемента.")
            }
            engine.attach(backingNode)
            engine.connect(backingNode, to: engine.mainMixerNode, format: track.processingFormat)
            contact = try Self.readSample(instrument, folder: "media/feedback")
        }
        var source = [Int32: AVAudioPCMBuffer]()
        for root in Set(roots.values) {
            source[root] = try Self.readSample(String(root), folder: "media/instruments/\(instrument)")
        }
        for (midi, root) in roots {
            guard let pcm = source[root] else { throw AudioFailure("Нет звука для ноты \(midi).") }
            buffers[midi] = try Self.transpose(pcm, semitones: midi - root)
        }
        if let format = source.values.first?.format ?? contact?.format {
            for _ in 0..<(roots.isEmpty ? 1 : 8) {
                let node = AVAudioPlayerNode()
                engine.attach(node)
                engine.connect(node, to: engine.mainMixerNode, format: format)
                nodes.append(node)
            }
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

    func setBackingVolume(_ gain: Float) { backingNode.volume = gain }

    func start(from frame: AVAudioFramePosition, volume: Float, finished: @escaping @Sendable () -> Void) throws {
        startFrame = min(frame, backingLength)
        if let track {
            var first = startFrame
            // Taper the first 12 ms after seeking to avoid starting on a discontinuity.
            if first > 0 && first < track.length {
                let count = AVAudioFrameCount(min(track.length - first, Int64(backingRate * 0.012)))
                guard let head = AVAudioPCMBuffer(pcmFormat: track.processingFormat, frameCapacity: count) else {
                    throw AudioFailure("Не удалось возобновить аккомпанемент.")
                }
                track.framePosition = first
                try track.read(into: head, frameCount: count)
                if let samples = head.floatChannelData, head.frameLength > 0 {
                    for channel in 0..<Int(head.format.channelCount) {
                        for i in 0..<Int(head.frameLength) {
                            samples[channel][i] *= Float(i) / Float(max(1, Int(head.frameLength) - 1))
                        }
                    }
                    first += AVAudioFramePosition(head.frameLength)
                    if first == track.length {
                        backingNode.scheduleBuffer(head, completionCallbackType: .dataPlayedBack) { _ in finished() }
                    } else {
                        backingNode.scheduleBuffer(head)
                    }
                }
            }
            if first < track.length {
                backingNode.scheduleSegment(track, startingFrame: first, frameCount: AVAudioFrameCount(track.length - first),
                                            at: nil, completionCallbackType: .dataPlayedBack) { _ in finished() }
            } else if startFrame == track.length {
                finished()
            }
            backingNode.volume = volume
        }
        engine.prepare()
        try engine.start()
        if track != nil {
            presentationDelay = max(0, backingNode.outputPresentationLatency)
            backingNode.play()
        }
    }

    func play(_ pitch: Int32, gain: Float) {
        if let buffer = buffers[pitch] { schedule(buffer, gain: gain) }
    }
    func strike(gain: Float) {
        if let buffer = contact { schedule(buffer, gain: gain) }
    }
    private func schedule(_ buffer: AVAudioPCMBuffer, gain: Float) {
        guard engine.isRunning, !nodes.isEmpty else { return }
        let node = nodes[next]
        next = (next + 1) % nodes.count
        node.volume = min(1, max(0, gain))
        node.scheduleBuffer(buffer, at: nil, options: .interrupts, completionHandler: nil)
        if !node.isPlaying { node.play() }
    }

    func stop() {
        engine.mainMixerNode.outputVolume = 0
        engine.stop()
        if track != nil { backingNode.stop() }
        nodes.forEach { $0.stop() }
    }
}
