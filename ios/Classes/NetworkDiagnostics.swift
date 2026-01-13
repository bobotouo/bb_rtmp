import Foundation
import Network

/// Network diagnostics helper for debugging RTMP connection issues
class NetworkDiagnostics {
    
    /// Test TCP connectivity to a host and port
    static func testTCPConnection(host: String, port: UInt16, completion: @escaping (Result<String, Error>) -> Void) {
        let connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!,
            using: .tcp
        )
        
        var resultSent = false
        
        connection.stateUpdateHandler = { state in
            guard !resultSent else { return }
            
            switch state {
            case .ready:
                resultSent = true
                connection.cancel()
                completion(.success("✅ TCP连接成功: \(host):\(port)"))
                
            case .failed(let error):
                resultSent = true
                connection.cancel()
                completion(.failure(error))
                
            case .waiting(let error):
                print("⚠️ 连接等待中: \(error)")
                
            default:
                break
            }
        }
        
        connection.start(queue: .global())
        
        // 10秒超时
        DispatchQueue.global().asyncAfter(deadline: .now() + 10) {
            if !resultSent {
                resultSent = true
                connection.cancel()
                completion(.failure(NSError(
                    domain: "NetworkDiagnostics",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "连接超时"]
                )))
            }
        }
    }
    
    /// Get all network interface addresses
    static func getNetworkInterfaces() -> [String] {
        var addresses = [String]()
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        
        guard getifaddrs(&ifaddr) == 0 else { return [] }
        defer { freeifaddrs(ifaddr) }
        
        var ptr = ifaddr
        while ptr != nil {
            defer { ptr = ptr?.pointee.ifa_next }
            
            guard let interface = ptr?.pointee else { continue }
            let addrFamily = interface.ifa_addr.pointee.sa_family
            
            if addrFamily == UInt8(AF_INET) || addrFamily == UInt8(AF_INET6) {
                let name = String(cString: interface.ifa_name)
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                
                if getnameinfo(interface.ifa_addr,
                             socklen_t(interface.ifa_addr.pointee.sa_len),
                             &hostname,
                             socklen_t(hostname.count),
                             nil,
                             0,
                             NI_NUMERICHOST) == 0 {
                    let address = String(cString: hostname)
                    addresses.append("\(name): \(address)")
                }
            }
        }
        
        return addresses
    }
    
    /// Run full network diagnostics
    static func runDiagnostics(rtmpHost: String, rtmpPort: UInt16 = 1935, completion: @escaping (String) -> Void) {
        var report = "=== 网络诊断报告 ===\n\n"
        
        // 1. 本地网络接口
        report += "📱 本地网络接口:\n"
        let interfaces = getNetworkInterfaces()
        if interfaces.isEmpty {
            report += "  ⚠️ 未找到网络接口\n"
        } else {
            interfaces.forEach { report += "  \($0)\n" }
        }
        report += "\n"
        
        // 2. 测试 RTMP 服务器连接
        report += "🔌 测试 RTMP 服务器连接:\n"
        report += "  目标: \(rtmpHost):\(rtmpPort)\n"
        
        testTCPConnection(host: rtmpHost, port: rtmpPort) { result in
            switch result {
            case .success(let message):
                report += "  \(message)\n"
            case .failure(let error):
                report += "  ❌ 连接失败: \(error.localizedDescription)\n"
                
                // 提供可能的解决方案
                report += "\n💡 可能的解决方案:\n"
                
                let errorCode = (error as NSError).code
                if errorCode == 65 || errorCode == 50 { // EHOSTUNREACH or ENETDOWN
                    report += "  1. 检查设备是否与 RTMP 服务器在同一 WiFi 网络\n"
                    report += "  2. 在 iOS 设置中检查是否授权了本地网络权限:\n"
                    report += "     设置 > 隐私 > 本地网络 > bb_rtmp_example\n"
                    report += "  3. 确认 RTMP 服务器已启动并监听端口 \(rtmpPort)\n"
                    report += "  4. 检查服务器防火墙是否允许端口 \(rtmpPort)\n"
                } else {
                    report += "  1. 确认服务器地址和端口正确\n"
                    report += "  2. 检查网络连接状态\n"
                }
            }
            
            report += "\n===================\n"
            completion(report)
        }
    }
}
