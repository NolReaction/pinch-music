import AVFoundation
import ComposeApp
import UIKit

@MainActor
final class AppleMediaFactory: NSObject, @preconcurrency AppleMediaFactoryProtocol {
    private lazy var songImport = AppleSongImport()
    private weak var current: NativeMedia?
    private var sceneActive = false
    private var pendingLaunch: (gain: Float, time: TimeInterval)?
    private var launchTask: Task<Void, Never>?
    private var launchID: UUID?
    private var launchObservers = [NSObjectProtocol]()

    func firstMenuFrame() {
        #if DEBUG
        if let start = PinchTiming.launch {
            PinchTiming.launch = nil
            PinchTiming.log.notice("[PinchTiming] Swift app init to first Compose menu frame: \(PinchTiming.ms(since: start)) ms")
        }
        #endif
    }

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
                let started = try await LaunchAudio.shared.play(id: id, volume: volume, requestedAt: requestedAt) {
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
    func pickReference(directory: String, listener: AppleReferenceListener) {
        songImport.pickReference(directory: directory, listener: listener)
    }

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

private nonisolated final class LaunchCompletion: NSObject, AVAudioPlayerDelegate {
    private let finished: @Sendable () -> Void
    init(_ finished: @escaping @Sendable () -> Void) { self.finished = finished }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { finished() }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) { finished() }
}

// Serial ownership keeps cold audio hardware setup off the UI thread and orders it before game playback.
private actor LaunchAudio {
    static let shared = LaunchAudio()
    // Accessing `shared` itself is synchronous. Only touch Core Audio on this actor.
    private lazy var session = AVAudioSession.sharedInstance()
    private var owner: UUID?
    private var player: AVAudioPlayer?
    private var completion: LaunchCompletion?

    func play(id: UUID, volume: Float, requestedAt: TimeInterval,
              finished: @escaping @Sendable () -> Void) throws -> Bool {
        guard !Task.isCancelled, let url = Bundle.main.url(forResource: "launch", withExtension: "wav"),
              session.outputVolume > 0, !session.isOtherAudioPlaying, !session.secondaryAudioShouldBeSilencedHint,
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
    private var audioSession: AVAudioSession { AVAudioSession.sharedInstance() }
    private var graph: GameAudioGraph?
    private var savedFrame: AVAudioFramePosition = 0
    private var initialPositionMs: Int64?
    private var sampleRate = 1.0
    private var backingVolume: Float = 1
    private(set) var backingEnded = false
    var backingPlaying: Bool { wantsPlayback && graph?.backingIsPlaying == true }
    var backingPositionMs: Int64 {
        capturePosition()
        return initialPositionMs ?? Int64((Double(savedFrame) * 1000 / sampleRate).rounded())
    }
    private var roots = [Int32: Int32]()
    private var tokens = [NSObjectProtocol]()
    private var preparation: Task<Void, Never>?
    var ready: Bool { graph != nil }
    private var wantsPlayback = false
    private var generation: UUID?
    private var outputPorts: [String] = []
    private var outputRate = 0.0
    private var mediaServicesAvailable = true
    private var closed = false
    private(set) var failure: String?
    #if DEBUG
    private var firstActivation = true
    private var firstStrike = true
    #endif

    init(instrument: String, observer: AppleInterruptListener, foreground: @escaping () -> Bool) {
        self.instrument = instrument
        self.observer = observer
        self.foreground = foreground
        super.init()
        observeSession()
    }

    func startAt(positionMs: Int64) {
        guard generation == nil, graph == nil, !closed else { return }
        initialPositionMs = max(0, positionMs)
    }

    func includeSample(midi: Int32, root: Int32) { roots[midi] = root }

    func prepare(backingAsset: String?, arrangementPath: String?, includeStrike: Bool) {
        guard !closed, !backingEnded, graph == nil, preparation == nil, failure == nil else { return }
        guard foreground(), mediaServicesAvailable else { interrupt(); return }
        let id = UUID()
        #if DEBUG
        let started = PinchTiming.now
        #endif
        generation = id
        preparation = Task { [weak self, instrument, roots, backingAsset, arrangementPath, savedFrame, initialPositionMs] in
            do {
                await LaunchAudio.shared.stop()
                try Task.checkCancellation()
                let loader = GameAudioLoader.shared
                let graph = try await loader.load(instrument, roots: roots, backingAsset: backingAsset,
                    arrangementPath: arrangementPath, includeStrike: includeStrike, from: savedFrame,
                    initialPositionMs: initialPositionMs, owner: id) { [weak self] in
                        Task { @MainActor in self?.completed(id) }
                    }
                guard !Task.isCancelled, let self, self.generation == id else {
                    await loader.release(id, graph: graph)
                    return
                }
                self.preparation = nil
                self.graph = graph
                guard self.foreground(), self.mediaServicesAvailable else { self.interrupt(); return }
                self.sampleRate = graph.backingRate
                self.savedFrame = graph.startFrame
                self.initialPositionMs = nil
                graph.setBackingVolume(self.backingVolume)
                self.outputPorts = graph.outputPorts
                self.outputRate = graph.outputRate
                #if DEBUG
                PinchTiming.log.notice("[PinchTiming] Audio prepare, hardware and first buffers: \(PinchTiming.ms(since: started)) ms")
                #endif
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

    func scheduleStart(delayMs: Int64) {
        guard !closed, foreground(), let graph, graph.engine.isRunning else { return }
        graph.startBacking(after: Double(delayMs) / 1000)
    }

    func activate() -> Bool {
        guard !closed, !backingEnded, failure == nil, let graph else { return false }
        guard foreground(), mediaServicesAvailable, graph.engine.isRunning else { interrupt(); return false }
        #if DEBUG
        let start = PinchTiming.now
        #endif
        wantsPlayback = true
        if graph.backingLength > 0 && graph.startFrame >= graph.backingLength {
            backingEnded = true
        } else {
            graph.startBacking(after: 0)
        }
        #if DEBUG
        if firstActivation {
            firstActivation = false
            PinchTiming.log.notice("[PinchTiming] First playback activation on UI: \(PinchTiming.ms(since: start)) ms")
        }
        #endif
        return true
    }

    private func completed(_ id: UUID) {
        guard id == generation, let graph, wantsPlayback else { return }
        // Core Audio may also complete a scheduled segment when hardware is interrupted.
        capturePosition()
        if foreground(), graph.engine.isRunning, graph.reachedEnd(at: savedFrame) {
            savedFrame = graph.backingLength
            backingEnded = true
        } else {
            interrupt()
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
        let id = generation
        graph = nil
        generation = nil
        if let previous, let id {
            // Silence instantly. Core Audio shutdown may block and is serialized with the next load.
            previous.mute()
            Task { await GameAudioLoader.shared.release(id, graph: previous) }
        }
    }

    func note(midi: Int32, gain: Float, durationMs: Int32) {
        if wantsPlayback && foreground() { graph?.play(midi, gain: gain, durationMs: durationMs) }
    }
    func strike(gain: Float) {
        guard wantsPlayback, foreground(), let graph else { return }
        #if DEBUG
        if firstStrike {
            firstStrike = false
            let start = PinchTiming.now
            graph.strike(gain: gain)
            PinchTiming.log.notice("[PinchTiming] First scored audio feedback: \(PinchTiming.ms(since: start)) ms")
            return
        }
        #endif
        graph.strike(gain: gain)
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
        observeAudio(AVAudioSession.interruptionNotification) { notification in
            let raw = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            return raw == AVAudioSession.InterruptionType.began.rawValue ? .interruption : nil
        }
        observeAudio(AVAudioSession.routeChangeNotification) { _ in .routeChanged }
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
            guard let graph else { return }
            let outputs = audioSession.currentRoute.outputs.map(\.uid)
            if outputPorts != outputs || outputRate != audioSession.sampleRate || !graph.engine.isRunning ||
                (wantsPlayback && !graph.running) {
                interrupt()
            }
        case .engineChanged(let id):
            if let current = graph, ObjectIdentifier(current.engine) == id,
               !current.engine.isRunning || (wantsPlayback && !current.running) {
                interrupt()
            }
        case .lost, .reset:
            // Orphaned audio objects must not be queried after loss of the media server.
            if case .lost = event { mediaServicesAvailable = false } else { mediaServicesAvailable = true }
            cancelPreparation()
            wantsPlayback = false
            if let id = generation { Task { await GameAudioLoader.shared.forget(id) } }
            generation = nil
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
    private var activeOwner: UUID?

    private init() { samples.totalCostLimit = 24 * 1024 * 1024 }

    func load(_ instrument: String, roots: [Int32: Int32], backingAsset: String?, arrangementPath: String?,
              includeStrike: Bool, from frame: AVAudioFramePosition, initialPositionMs: Int64?,
              owner: UUID, finished: @escaping @Sendable () -> Void) throws -> GameAudioGraph {
        try Task.checkCancellation()
        var track: AVAudioFile?
        var sourceURL: URL?
        if let path = arrangementPath {
            sourceURL = URL(fileURLWithPath: path)
        } else if let asset = backingAsset {
            guard let folder = Bundle.main.resourceURL else { throw AudioFailure("Отсутствуют ресурсы игры.") }
            let url = folder.appendingPathComponent("media").appendingPathComponent(asset)
            if url.pathExtension == "m4a", !FileManager.default.fileExists(atPath: url.path) {
                sourceURL = url.deletingPathExtension().appendingPathExtension("wav")
            } else {
                sourceURL = url
            }
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
        if includeStrike {
            contact = try cached("media/feedback/\(instrument)") { try Self.readSample(instrument, folder: "media/feedback") }
        }
        try Task.checkCancellation()
        let session = AVAudioSession.sharedInstance()
        if session.category != .playback || session.mode != .default {
            try session.setCategory(.playback, mode: .default)
        }
        try session.setActive(true)
        activeOwner = owner
        var starting: GameAudioGraph?
        do {
            let first: AVAudioFramePosition
            if let ms = initialPositionMs, let track {
                first = AVAudioFramePosition(min(Double(track.length), Double(ms) * track.processingFormat.sampleRate / 1000))
            } else {
                first = frame
            }
            let graph = try GameAudioGraph(track: track, buffers: buffers, contact: contact, from: first)
            starting = graph
            try graph.prime(session: session, finished: finished)
            try Task.checkCancellation()
            return graph
        } catch {
            release(owner, graph: starting)
            throw error
        }
    }

    func release(_ owner: UUID, graph: GameAudioGraph?) {
        graph?.stop()
        // Cancellation and a replacement prepare can reach this actor in either order.
        guard activeOwner == owner else { return }
        activeOwner = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func forget(_ owner: UUID) {
        if activeOwner == owner { activeOwner = nil }
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
private nonisolated final class GameAudioGraph: @unchecked Sendable {
    let engine = AVAudioEngine()
    private let backingNode = AVAudioPlayerNode()
    private let track: AVAudioFile?
    let startFrame: AVAudioFramePosition
    private let fadeHead: AVAudioPCMBuffer?
    private var presentationDelay = 0.0
    private var renderQuantum = 0.0
    private var backingArmed = false
    private(set) var outputPorts = [String]()
    private(set) var outputRate = 0.0
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
    var running: Bool { engine.isRunning && (!backingArmed || backingNode.isPlaying) }

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
        // Sample time belongs to the audio callback's host timestamp, not the UI's polling instant.
        // Bridge those host clocks only within the current hardware buffer so a stalled node freezes.
        let renderAge = renderTime.isHostTimeValid
            ? AVAudioTime.seconds(forHostTime: mach_absolute_time()) - AVAudioTime.seconds(forHostTime: renderTime.hostTime) : 0
        let age = min(renderQuantum, max(-renderQuantum, renderAge))
        let audibleSeconds = max(0, Double(time.sampleTime) / time.sampleRate + age - presentationDelay)
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

    /// Schedule and predecode while the stage is loading. No audio plays before startBacking.
    func prime(session: AVAudioSession, finished: @escaping @Sendable () -> Void) throws {
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
            }
            let available = track.length - startFrame
            if available > 0 { backingNode.prepare(withFrameCount: AVAudioFrameCount(min(8192, available))) }
        }
        try engine.start()
        // An idle player can accept a tap without its first play() doing work on the touch callback.
        nodes.forEach { $0.play() }
        outputPorts = session.currentRoute.outputs.map(\.uid)
        outputRate = session.sampleRate
        renderQuantum = session.ioBufferDuration
        if track != nil { presentationDelay = max(0, backingNode.outputPresentationLatency) }
    }

    func startBacking(after delay: TimeInterval) {
        guard track != nil, !backingArmed else { return }
        backingArmed = true
        // The note should arrive at the speaker at the same instant as the first collision.
        let lead = max(0, delay - presentationDelay)
        if lead > 0 {
            let target = mach_absolute_time() + AVAudioTime.hostTime(forSeconds: lead)
            backingNode.play(at: AVAudioTime(hostTime: target))
        } else {
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

    func mute() { engine.mainMixerNode.outputVolume = 0 }

    func stop() {
        engine.stop()
        if track != nil { backingNode.stop() }
        nodes.forEach { $0.stop() }
    }
}
