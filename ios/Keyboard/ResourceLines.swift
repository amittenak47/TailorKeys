import Foundation

/// Bounded UTF-8 line reader for bundled dictionaries and n-grams. Never retains the file
/// or a file-sized array of Substrings. Lines larger than the resource format allows are skipped.
enum ResourceLines {
    static func read(_ url: URL, _ consume: (String) -> Void) {
        guard let stream = InputStream(url: url) else { return }
        stream.open()
        defer { stream.close() }
        var chunk = [UInt8](repeating: 0, count: 16_384)
        var line = [UInt8]()
        line.reserveCapacity(256)
        var oversized = false
        func finish() {
            if !oversized, !line.isEmpty, let text = String(bytes: line, encoding: .utf8) { consume(text) }
            line.removeAll(keepingCapacity: true); oversized = false
        }
        while true {
            let n = chunk.withUnsafeMutableBufferPointer { stream.read($0.baseAddress!, maxLength: $0.count) }
            if n <= 0 { break }
            autoreleasepool {
                for i in 0..<n {
                    let byte = chunk[i]
                    if byte == 10 || byte == 13 { finish() }
                    else if !oversized {
                        if line.count < 4096 { line.append(byte) } else { oversized = true }
                    }
                }
            }
        }
        finish()
    }
}
