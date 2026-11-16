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

/// Animated frames present on the session display link. UIKit can still redraw a paused canvas after layout.
@MainActor
final class AppleCascadeDisplay: NSObject, @preconcurrency AppleCascadeSurface, @preconcurrency GLKViewDelegate {
    private let canvas: GLKView
    var view: UIView { canvas }

    private var stage: UnsafeMutableRawPointer?
    private var keys = [Float]()
    private var rails = [Float]()
    private var frame = [Float](repeating: 0, count: Int(STAGE3D_FRAME_SIZE))
    private var needsUpload = true
    private var hasFrame = false
    private var closed = false
    private var suspended = false
    private var onPresented: (() -> Void)?
    private var firstRefresh: CADisplayLink?

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
        center.addObserver(self, selector: #selector(releaseStage), name: UIApplication.didEnterBackgroundNotification, object: nil)
        center.addObserver(self, selector: #selector(resumeStage), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    func setCourse(keys: Data, rails: Data, onPresented: @escaping () -> Void) {
        guard !closed else { return }
        cancelRefresh()
        self.onPresented = onPresented
        self.keys = floats(keys)
        self.rails = floats(rails)
        hasFrame = false
        needsUpload = true
    }

    private func floats(_ data: Data) -> [Float] {
        var values = [Float](repeating: 0, count: data.count / MemoryLayout<Float>.stride)
        _ = values.withUnsafeMutableBytes { data.copyBytes(to: $0) }
        return values
    }

    func draw(frame values: UnsafeMutableRawPointer) {
        guard !closed else { return }
        // The pinned Kotlin array is borrowed for this call; retain our own last pose for UIKit redraws.
        frame.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress?.update(from: values.assumingMemoryBound(to: Float.self), count: buffer.count)
        }
        hasFrame = true
        if !suspended && canvas.window != nil && UIApplication.shared.applicationState == .active {
            // The caller already runs on CADisplayLink and supplied this refresh's pose.
            canvas.display()
        }
    }

    func glkView(_ view: GLKView, drawIn rect: CGRect) {
        guard !closed, !suspended, hasFrame, !keys.isEmpty, UIApplication.shared.applicationState == .active,
              view.drawableWidth > 0, view.drawableHeight > 0 else { return }
        if stage == nil {
            stage = stage3d_create()
            needsUpload = true
        }
        guard let stage else { return }
        if needsUpload {
            keys.withUnsafeBufferPointer { k in
                rails.withUnsafeBufferPointer { r in
                    stage3d_set_course(stage, k.baseAddress, Int32(k.count / Int(STAGE3D_KEY_STRIDE)),
                                       r.baseAddress, Int32(r.count / 3))
                }
            }
            needsUpload = false
        }
        frame.withUnsafeBufferPointer { values in
            stage3d_draw(stage, Int32(view.drawableWidth), Int32(view.drawableHeight), values.baseAddress)
        }
        // GLKView presents its drawable after this delegate returns. Acknowledge the drawn
        // course on the following display refresh, on the same main thread as the session.
        if onPresented != nil && firstRefresh == nil {
            let link = CADisplayLink(target: self, selector: #selector(afterPresentation(_:)))
            firstRefresh = link
            link.add(to: .main, forMode: .common)
        }
    }

    @objc private func afterPresentation(_ link: CADisplayLink) {
        guard link === firstRefresh else { return }
        cancelRefresh()
        guard !closed, !suspended, canvas.window != nil, !canvas.isHidden,
              UIApplication.shared.applicationState == .active else { return }
        let notify = onPresented
        onPresented = nil
        notify?()
    }

    private func cancelRefresh() {
        firstRefresh?.invalidate()
        firstRefresh = nil
    }

    @objc private func suspendStage() {
        suspended = true
        cancelRefresh()
        canvas.enableSetNeedsDisplay = false
        // Drain submitted commands before iOS backgrounds us. A transient
        // interruption can keep the course meshes and shaders for the next frame.
        let previous = EAGLContext.current()
        EAGLContext.setCurrent(canvas.context)
        glFinish()
        EAGLContext.setCurrent(previous)
    }

    @objc private func releaseStage() {
        let previous = EAGLContext.current()
        EAGLContext.setCurrent(canvas.context)
        if let stage {
            stage3d_destroy(stage)
            self.stage = nil
        }
        canvas.deleteDrawable()
        glFinish()
        EAGLContext.setCurrent(previous === canvas.context ? nil : previous)
    }

    @objc private func resumeStage() {
        if !closed {
            suspended = false
            canvas.enableSetNeedsDisplay = true
            canvas.setNeedsLayout()
            canvas.setNeedsDisplay()
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        cancelRefresh()
        onPresented = nil
        canvas.delegate = nil
        NotificationCenter.default.removeObserver(self)
        // If another observer closes us during didEnterBackground, we still own
        // the stage until our own background cleanup runs. Later it is already nil.
        if stage != nil || UIApplication.shared.applicationState != .background { releaseStage() }
    }
}
