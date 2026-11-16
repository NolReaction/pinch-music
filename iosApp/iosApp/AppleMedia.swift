import AVFoundation
import ComposeApp
import UIKit

@MainActor
final class AppleMediaFactory: NSObject, @preconcurrency AppleMediaFactoryProtocol {
    private let songImport = AppleSongImport()
    private weak var current: NativeMedia?
    private var sceneActive = false
    private var pendingLaunch: (gain: Float, time: TimeInterval)?
    private var launchTask: Task<Void, Never>?
    private var launchID: UUID?
    private var launchObservers = [NSObjectProtocol]()

    func setSceneActive(_ active: Bool) {
        guard active != sceneActive else { return }
        sceneActive = active
        if active, let pending = pendingLaunch {
            pendingLaunch = nil
            if ProcessInfo.processInfo.systemUptime - pending.time <= 0.35 {
                beginLaunchSound(volume: pending.gain, requestedAt: pending.time)
            }
        } else if !active {
            MainViewControllerKt.dismissLaunchPresentation()
            stopLaunchSound()
            current?.interrupt()
        }
    }

    func playLaunchSound(volume: Float) {
        stopLaunchSound()
        guard volume > 0, current == nil else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if sceneActive {
            beginLaunchSound(volume: volume, requestedAt: now)
        } else if UIApplication.shared.applicationState != .background {
            // SwiftUI can mount the first Compose frame just before the scene becomes active.
            pendingLaunch = (volume, now)
        }
    }

    private func beginLaunchSound(volume: Float, requestedAt: TimeInterval) {
        guard let url = Bundle.main.url(forResource: "launch", withExtension: "wav") else { return }
        let id = UUID()
        launchID = id
        for name in [AVAudioSession.interruptionNotification, AVAudioSession.silenceSecondaryAudioHintNotification,
                     UIApplication.willResignActiveNotification, UIApplication.userDidTakeScreenshotNotification] {
            launchObservers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) {
                [weak self] _ in MainActor.assumeIsolated { self?.stopLaunchSound() }
            })
        }
        launchObservers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification,
            object: nil, queue: .main) { [weak self] event in
                let reason = (event.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue
                if reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                    MainActor.assumeIsolated { self?.stopLaunchSound() }
                }
            })
        launchTask = Task { [weak self] in
            do {
                let started = try await LaunchAudio.shared.play(id: id, url: url, volume: volume, requestedAt: requestedAt) {
                    [weak self] in
                    Task { @MainActor in
                        if self?.launchID == id { self?.stopLaunchSound() }
                    }
                }
                if started { return }
            } catch { }
            if !Task.isCancelled, self?.launchID == id { self?.stopLaunchSound() }
        }
    }

    func stopLaunchSound() {
        pendingLaunch = nil
        launchTask?.cancel()
        launchTask = nil
        launchObservers.forEach { NotificationCenter.default.removeObserver($0) }
        launchObservers.removeAll()
        if let id = launchID {
            launchID = nil
            Task { await LaunchAudio.shared.stop(id: id) }
        }
    }

    func createCascade() -> AppleCascadeSurface { AppleCascadeDisplay() }

    func pickAudio(listener: AppleAudioImportListener) { songImport.pick(listener) }

    func createMedia(instrument: String, observer: AppleInterruptListener) -> AppleMediaSession {
        stopLaunchSound()
        current?.close()
        let media = NativeMedia(instrument: instrument, observer: observer) { [weak self] in
            self?.sceneActive == true && UIApplication.shared.applicationState == .active
        }
        current = media
        return media
    }
}

private final class LaunchCompletion: NSObject, AVAudioPlayerDelegate {
    private let finished: @Sendable () -> Void
    init(_ finished: @escaping @Sendable () -> Void) { self.finished = finished }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { finished() }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) { finished() }
}

// Serial ownership keeps cold audio hardware setup off the UI thread and orders it before game playback.
private actor LaunchAudio {
    static let shared = LaunchAudio()
    private let session = AVAudioSession.sharedInstance()
    private var owner: UUID?
    private var player: AVAudioPlayer?
    private var completion: LaunchCompletion?

    func play(id: UUID, url: URL, volume: Float, requestedAt: TimeInterval,
              finished: @escaping @Sendable () -> Void) throws -> Bool {
        guard !Task.isCancelled, session.outputVolume > 0, !session.isOtherAudioPlaying, !session.secondaryAudioShouldBeSilencedHint,
              ProcessInfo.processInfo.systemUptime - requestedAt <= 0.35 else { return false }
        // Ambient observes the hardware Silent switch and lock without interrupting other audio.
        try session.setCategory(.ambient, mode: .default)
        let effect = try AVAudioPlayer(contentsOf: url)
        let callback = LaunchCompletion(finished)
        completion = callback
        effect.delegate = callback
        player = effect
        owner = id
        effect.volume = min(1, volume)
        guard effect.prepareToPlay(), !Task.isCancelled, !session.isOtherAudioPlaying,
              !session.secondaryAudioShouldBeSilencedHint, ProcessInfo.processInfo.systemUptime - requestedAt <= 0.35
        else { stop(id: id); return false }
        guard effect.play() else { stop(id: id); return false }
        return true
    }

    func stop(id: UUID? = nil) {
        guard let player, id == nil || owner == id else { return }
        self.player = nil
        owner = nil
        player.delegate = nil
        completion = nil
        player.stop()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
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
    private var preparation: Task<Void, Never>?
    var ready: Bool { graph != nil }
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

    func prepare(backingAsset: String?, arrangementPath: String? = nil) {
        guard !closed, !backingEnded, graph == nil, preparation == nil, failure == nil else { return }
        guard foreground(), mediaServicesAvailable else { interrupt(); return }
        preparation = Task { [weak self, instrument, roots, backingAsset, arrangementPath, savedFrame] in
            do {
                await LaunchAudio.shared.stop()
                try Task.checkCancellation()
                let graph = try await GameAudioLoader.shared.load(instrument, roots: roots, backingAsset: backingAsset, arrangementPath: arrangementPath, from: savedFrame)
                guard !Task.isCancelled, let self else { return }
                self.preparation = nil
                guard self.foreground(), self.mediaServicesAvailable else { self.interrupt(); return }
                self.sampleRate = graph.backingRate
                self.graph = graph
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.preparation = nil
                self.failure = error.localizedDescription
            }
        }
    }

    private func cancelPreparation() {
        preparation?.cancel()
        preparation = nil
    }

    private func capturePosition() {
        if wantsPlayback, let frame = graph?.currentBackingFrame { savedFrame = max(savedFrame, frame) }
    }

    func activate() -> Bool {
        guard !closed, !backingEnded, failure == nil else { return false }
        guard foreground(), mediaServicesAvailable else { interrupt(); return false }
        if wantsPlayback { return true }
        guard let current = graph else { return false }
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
            try current.start(volume: backingVolume) { [weak self, weak current] in
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
        cancelPreparation()
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

    func note(midi: Int32, gain: Float, durationMs: Int32) {
        if wantsPlayback && foreground() { graph?.play(midi, gain: gain, durationMs: durationMs) }
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
            if media.preparation != nil || media.ready { media.interrupt() }
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
            cancelPreparation()
            wantsPlayback = false
            sessionActive = false
            graph = nil
            observer?.interrupted()
        }
    }
}

// The loader exclusively constructs the graph, then hands it to the main actor.
// Cached PCM is read-only after decoding, including while scheduled on a player.
private actor GameAudioLoader {
    static let shared = GameAudioLoader()
    private let samples = NSCache<NSString, AVAudioPCMBuffer>()

    private init() { samples.totalCostLimit = 24 * 1024 * 1024 }

    func load(_ instrument: String, roots: [Int32: Int32], backingAsset: String?, arrangementPath: String?,
              from frame: AVAudioFramePosition) throws -> GameAudioGraph {
        try Task.checkCancellation()
        var track: AVAudioFile?
        var sourceURL: URL?
        if let path = arrangementPath {
            sourceURL = URL(fileURLWithPath: path)
        } else if let asset = backingAsset {
            guard let folder = Bundle.main.resourceURL else { throw AudioFailure("Отсутствуют ресурсы игры.") }
            sourceURL = folder.appendingPathComponent("media").appendingPathComponent(asset)
        }
        if let url = sourceURL {
            let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
            guard file.length > 0, file.length <= Int64(UInt32.max) else {
                throw AudioFailure("Недопустимая длина аккомпанемента.")
            }
            track = file
        }
        var buffers = [Int32: AVAudioPCMBuffer]()
        let folder = "media/instruments/\(instrument)"
        for (midi, root) in roots {
            try Task.checkCancellation()
            let original = try cached("\(folder)/\(root)") { try Self.readSample(String(root), folder: folder) }
            buffers[midi] = try cached("\(folder)/\(midi)") { try Self.transpose(original, semitones: midi - root) }
        }
        var contact: AVAudioPCMBuffer?
        if backingAsset != nil && !roots.isEmpty {
            contact = try cached("media/feedback/\(instrument)") { try Self.readSample(instrument, folder: "media/feedback") }
        }
        try Task.checkCancellation()
        return try GameAudioGraph(track: track, buffers: buffers, contact: contact, from: frame)
    }

    private func cached(_ key: String, load: () throws -> AVAudioPCMBuffer) rethrows -> AVAudioPCMBuffer {
        if let buffer = samples.object(forKey: key as NSString) { return buffer }
        let buffer = try load()
        let cost = Int(buffer.frameCapacity) * Int(buffer.format.channelCount) * MemoryLayout<Float>.stride
        samples.setObject(buffer, forKey: key as NSString, cost: cost)
        return buffer
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
}

/// Backing, pitched notes and taps share one output mixer and one hardware clock.
private final class GameAudioGraph: @unchecked Sendable {
    let engine = AVAudioEngine()
    private let backingNode = AVAudioPlayerNode()
    private let track: AVAudioFile?
    private let startFrame: AVAudioFramePosition
    private let fadeHead: AVAudioPCMBuffer?
    private var presentationDelay = 0.0
    private let buffers: [Int32: AVAudioPCMBuffer]
    private let contact: AVAudioPCMBuffer?
    // The immutable gated buffers may still be in the render pipeline after a
    // voice is stolen. Let AVAudioEngine retain each scheduled buffer until done.
    private let gated = NSCache<NSNumber, AVAudioPCMBuffer>()
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

    init(track: AVAudioFile?, buffers: [Int32: AVAudioPCMBuffer], contact: AVAudioPCMBuffer?,
         from frame: AVAudioFramePosition) throws {
        self.track = track
        startFrame = min(frame, track?.length ?? 0)
        if let track, startFrame > 0, startFrame < track.length {
            fadeHead = try Self.resumeBuffer(track, from: startFrame)
        } else {
            fadeHead = nil
        }
        self.buffers = buffers
        self.contact = contact
        gated.totalCostLimit = 6 * 1024 * 1024
        if let track {
            engine.attach(backingNode)
            engine.connect(backingNode, to: engine.mainMixerNode, format: track.processingFormat)
        }
        if let format = buffers.values.first?.format ?? contact?.format {
            for _ in 0..<(buffers.isEmpty ? 1 : 8) {
                let node = AVAudioPlayerNode()
                engine.attach(node)
                engine.connect(node, to: engine.mainMixerNode, format: format)
                nodes.append(node)
            }
        }
        try Task.checkCancellation()
        engine.prepare()
    }

    // Decode the first 12 ms of a seek off the UI thread and taper the discontinuity.
    private static func resumeBuffer(_ track: AVAudioFile, from frame: AVAudioFramePosition) throws -> AVAudioPCMBuffer {
        let frames = min(track.length - frame, max(1, Int64(track.processingFormat.sampleRate * 0.012)))
        let count = AVAudioFrameCount(frames)
        guard let head = AVAudioPCMBuffer(pcmFormat: track.processingFormat, frameCapacity: count) else {
            throw AudioFailure("Не удалось возобновить аккомпанемент.")
        }
        track.framePosition = frame
        try track.read(into: head, frameCount: count)
        guard let samples = head.floatChannelData, head.frameLength > 0 else {
            throw AudioFailure("Не удалось прочитать аккомпанемент.")
        }
        let last = max(1, Int(head.frameLength) - 1)
        for channel in 0..<Int(head.format.channelCount) {
            for i in 0..<Int(head.frameLength) { samples[channel][i] *= Float(i) / Float(last) }
        }
        return head
    }

    func setBackingVolume(_ gain: Float) { backingNode.volume = gain }

    func start(volume: Float, finished: @escaping @Sendable () -> Void) throws {
        if let track {
            var first = startFrame
            if let fadeHead {
                first += AVAudioFramePosition(fadeHead.frameLength)
                if first == track.length {
                    backingNode.scheduleBuffer(fadeHead, completionCallbackType: .dataPlayedBack) { _ in finished() }
                } else {
                    backingNode.scheduleBuffer(fadeHead)
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
        try engine.start()
        if track != nil {
            presentationDelay = max(0, backingNode.outputPresentationLatency)
            backingNode.play()
        }
    }

    func play(_ pitch: Int32, gain: Float, durationMs: Int32) {
        guard let sample = buffers[pitch] else { return }
        let rate = sample.format.sampleRate
        let hold = max(1, Int(Double(durationMs) * rate / 1000))
        let release = max(2, Int(rate * 0.016))
        let count = min(Int(sample.frameLength), hold + release)
        if count == Int(sample.frameLength) {
            schedule(sample, gain: gain)
            return
        }
        let key = NSNumber(value: Int64(pitch) << 32 | Int64(count))
        if let cached = gated.object(forKey: key) {
            schedule(cached, gain: gain)
            return
        }
        guard let clipped = AVAudioPCMBuffer(pcmFormat: sample.format, frameCapacity: AVAudioFrameCount(count)),
              let source = sample.floatChannelData, let dest = clipped.floatChannelData else { return }
        let channels = Int(sample.format.channelCount)
        for channel in 0..<channels {
            dest[channel].update(from: source[channel], count: count)
            for i in hold..<count {
                dest[channel][i] *= Float(count - 1 - i) / Float(release - 1)
            }
        }
        clipped.frameLength = AVAudioFrameCount(count)
        gated.setObject(clipped, forKey: key, cost: count * channels * MemoryLayout<Float>.stride)
        schedule(clipped, gain: gain)
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
