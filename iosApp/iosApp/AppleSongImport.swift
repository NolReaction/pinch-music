import AVFoundation
import ComposeApp
import UIKit
import UniformTypeIdentifiers

private enum SongImportError: LocalizedError {
    case invalidFormat, shortAudio, decoding
    var errorDescription: String? {
        switch self {
        case .invalidFormat: return "Неизвестный формат аудио"
        case .shortAudio: return "В файле меньше секунды звука"
        case .decoding: return "Не удалось прочитать аудиодорожку. Попробуй файл WAV, MP3 или M4A."
        }
    }
}

/// The document URL is read only while its security-scoped access is active.
@MainActor
final class AppleSongImport: NSObject, UIDocumentPickerDelegate, UIAdaptivePresentationControllerDelegate {
    private var listener: AppleAudioImportListener?
    private var decoding = false

    func pick(_ listener: AppleAudioImportListener) {
        guard self.listener == nil else {
            listener.failed(message: "Выбор файла уже открыт")
            return
        }
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }.flatMap(\.windows)
        guard var presenter = windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            listener.failed(message: "Не удалось открыть приложение «Файлы»")
            return
        }
        while let next = presenter.presentedViewController { presenter = next }

        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.audio, .mpeg4Movie], asCopy: false)
        picker.delegate = self
        self.listener = listener
        presenter.present(picker, animated: true)
        picker.presentationController?.delegate = self
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        if !decoding { finish { $0.cancelled() } }
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        if !decoding { finish { $0.cancelled() } }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            finish { $0.cancelled() }
            return
        }
        decoding = true
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = Result { try Self.readDocument(url) }
            DispatchQueue.main.async {
                self?.finish { listener in
                    switch result {
                    case .success(let clip):
                        listener.decoded(pcm16: clip.data, rate: Int32(clip.rate), channels: Int32(clip.channels),
                                         displayName: url.lastPathComponent)
                    case .failure(let error): listener.failed(message: error.localizedDescription)
                    }
                }
            }
        }
    }

    private func finish(_ callback: (AppleAudioImportListener) -> Void) {
        let pending = listener
        listener = nil
        decoding = false
        if let pending = pending { callback(pending) }
    }

    private struct Excerpt: Sendable {
        let data: Data
        let rate: Int
        let channels: Int
    }

    nonisolated private static func readDocument(_ url: URL) throws -> Excerpt {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var coordinationError: NSError?
        var result: Result<Excerpt, Error>?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordinationError) { path in
            result = Result { try decode(path) }
        }
        if let error = coordinationError { throw error }
        guard let decoded = result else { throw SongImportError.decoding }
        return try decoded.get()
    }

    nonisolated private static func decode(_ url: URL) throws -> Excerpt {
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        let format = file.processingFormat
        let rate = format.sampleRate
        let channels = Int(format.channelCount)
        guard rate.isFinite, rate >= 4_000, rate <= 384_000, (1...32).contains(channels),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4096) else {
            throw SongImportError.invalidFormat
        }
        let length = Double(file.length) / rate
        if length >= 70 { file.framePosition = AVAudioFramePosition(min(length / 3, 55) * rate) }
        let outputRate = Int(min(rate, 48_000).rounded())
        let outputChannels = min(channels, 2)
        let limit = outputRate * 30 * outputChannels
        var pcm = [Int16]()
        pcm.reserveCapacity(limit)
        var phase = 0.0
        var left: Float = 0
        var right: Float = 0
        var frames = 0
        func quantize(_ value: Float) -> Int16 {
            guard value.isFinite else { return 0 }
            return Int16(clamping: Int((max(-1, min(1, value)) * 32_768).rounded())).littleEndian
        }
        while pcm.count < limit {
            try file.read(into: buffer, frameCount: buffer.frameCapacity)
            let count = Int(buffer.frameLength)
            if count == 0 { break }
            guard let planes = buffer.floatChannelData else { throw SongImportError.invalidFormat }
            for index in 0..<count {
                if channels <= 2 {
                    left += planes[0][index]
                    if channels == 2 { right += planes[1][index] }
                } else {
                    for channel in 0..<channels {
                        if channel.isMultiple(of: 2) { left += planes[channel][index] / Float((channels + 1) / 2) }
                        else { right += planes[channel][index] / Float(channels / 2) }
                    }
                }
                frames += 1
                phase += Double(outputRate)
                if phase >= rate {
                    pcm.append(quantize(left / Float(frames)))
                    if outputChannels == 2 { pcm.append(quantize(right / Float(frames))) }
                    phase -= rate
                    left = 0
                    right = 0
                    frames = 0
                }
                if pcm.count == limit { break }
            }
        }
        guard pcm.count >= outputRate * outputChannels else { throw SongImportError.shortAudio }
        return Excerpt(data: pcm.withUnsafeBytes { Data($0) }, rate: outputRate, channels: outputChannels)
    }
}
