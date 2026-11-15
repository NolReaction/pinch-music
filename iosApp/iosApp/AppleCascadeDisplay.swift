import ComposeApp
import GLKit
import UIKit

@MainActor
private final class CascadeCanvas: GLKView {
    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateRasterScale()
        if window != nil { setNeedsDisplay() }
    }

    override func layoutSubviews() {
        updateRasterScale()
        super.layoutSubviews()
    }

    private func updateRasterScale() {
        guard let screen = window?.screen, bounds.width > 0, bounds.height > 0 else { return }
        let scale = CGFloat(CascadeRaster.shared.scale(width: Double(bounds.width),
                                                       height: Double(bounds.height),
                                                       nativeScale: Double(screen.scale)))
        if abs(contentScaleFactor - scale) > 0.0001 {
            contentScaleFactor = scale
            setNeedsDisplay()
        }
    }
}

/// GLKView renders only when Compose submits a frame or UIKit resizes its drawable.
@MainActor
final class AppleCascadeDisplay: NSObject, @preconcurrency AppleCascadeSurface, @preconcurrency GLKViewDelegate {
    private let canvas: GLKView
    var view: UIView { canvas }

    private var stage: UnsafeMutableRawPointer?
    private var keys = [Float]()
    private var rails = [Float]()
    private var lamps = [Float]()
    private var frame = [Float](repeating: 0, count: 12)
    private var needsUpload = true
    private var hasFrame = false
    private var closed = false

    override init() {
        guard let context = EAGLContext(api: .openGLES2) else {
            preconditionFailure("OpenGL ES 2 context unavailable")
        }
        canvas = CascadeCanvas(frame: .zero, context: context)
        super.init()
        canvas.delegate = self
        canvas.isUserInteractionEnabled = false
        canvas.isOpaque = true
        canvas.drawableDepthFormat = .format16
        canvas.drawableColorFormat = .RGBA8888
        canvas.enableSetNeedsDisplay = true
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(suspendStage), name: UIApplication.willResignActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(resumeStage), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    func setCourse(keys: Data, rails: Data, lamps: Data) {
        self.keys = floats(keys)
        self.rails = floats(rails)
        self.lamps = floats(lamps)
        needsUpload = true
    }

    private func floats(_ data: Data) -> [Float] {
        var values = [Float](repeating: 0, count: data.count / MemoryLayout<Float>.stride)
        _ = values.withUnsafeMutableBytes { data.copyBytes(to: $0) }
        return values
    }

    func draw(frame data: Data) {
        guard !closed, data.count == 12 * MemoryLayout<Float>.stride else { return }
        _ = frame.withUnsafeMutableBytes { dest in data.copyBytes(to: dest) }
        hasFrame = true
        if canvas.window != nil && UIApplication.shared.applicationState == .active {
            canvas.display()
        }
    }

    func glkView(_ view: GLKView, drawIn rect: CGRect) {
        guard !closed, hasFrame, UIApplication.shared.applicationState == .active,
              view.drawableWidth > 0, view.drawableHeight > 0 else { return }
        if stage == nil {
            stage = stage3d_create()
            needsUpload = true
        }
        guard let stage else { return }
        if needsUpload {
            keys.withUnsafeBufferPointer { k in
                rails.withUnsafeBufferPointer { r in
                    lamps.withUnsafeBufferPointer { l in
                        stage3d_set_course(stage, k.baseAddress, Int32(k.count / 4),
                                           r.baseAddress, Int32(r.count / 3),
                                           l.baseAddress, Int32(l.count / 3))
                    }
                }
            }
            needsUpload = false
        }
        frame.withUnsafeBufferPointer { values in
            stage3d_draw(stage, Int32(view.drawableWidth), Int32(view.drawableHeight), values.baseAddress)
        }
    }

    @objc private func suspendStage() {
        let previous = EAGLContext.current()
        EAGLContext.setCurrent(canvas.context)
        if let stage {
            stage3d_destroy(stage)
            self.stage = nil
        }
        canvas.deleteDrawable()
        glFinish()
        EAGLContext.setCurrent(previous)
    }

    @objc private func resumeStage() {
        if !closed {
            canvas.setNeedsLayout()
            canvas.setNeedsDisplay()
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        canvas.delegate = nil
        NotificationCenter.default.removeObserver(self)
        if UIApplication.shared.applicationState != .background { suspendStage() }
    }
}
