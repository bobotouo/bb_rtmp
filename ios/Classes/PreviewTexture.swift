import Flutter
import Foundation
import CoreVideo

public class PreviewTexture: NSObject, FlutterTexture {
    private var pixelBuffer: CVPixelBuffer?
    private let registry: FlutterTextureRegistry
    var textureId: Int64 = -1
    
    init(registry: FlutterTextureRegistry) {
        self.registry = registry
        super.init()
        self.textureId = registry.register(self)
    }
    
    public func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? {
        guard let pixelBuffer = pixelBuffer else {
            return nil
        }
        return Unmanaged.passRetained(pixelBuffer)
    }
    
    func updateBuffer(_ buffer: CVPixelBuffer) {
        self.pixelBuffer = buffer
        registry.textureFrameAvailable(textureId)
    }
    
    func release() {
        if textureId != -1 {
            registry.unregisterTexture(textureId)
            textureId = -1
        }
        pixelBuffer = nil
    }
}
