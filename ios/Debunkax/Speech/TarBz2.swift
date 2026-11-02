import Foundation

/// Streaming `.tar.bz2` extraction on the system libbz2 (`bzlib.h` in the bridging header, `libbz2.tbd` linked),
/// with a minimal tar reader (ustar, plus the GNU long-name and pax path records). The archive is read and
/// decompressed in 64 KiB pieces; only the entries `destination` accepts are written to disk.
enum TarBz2 {
    struct Failure: Error {
        let detail: String
    }

    private static let bzOK: Int32 = 0
    private static let bzStreamEnd: Int32 = 4

    /// - Parameter destination: called with each regular entry's path inside the archive; return the file to write
    ///   it to, or nil to skip the entry.
    static func extract(archive: URL, destination: @escaping (String) -> URL?) throws {
        let input = try FileHandle(forReadingFrom: archive)
        defer { try? input.close() }

        var reader = TarReader(destination: destination)
        var stream = bz_stream()
        guard BZ2_bzDecompressInit(&stream, 0, 0) == bzOK else {
            throw Failure(detail: "Initialisation de la décompression impossible")
        }
        defer { BZ2_bzDecompressEnd(&stream) }

        var output = [UInt8](repeating: 0, count: 64 * 1024)
        var finished = false
        while !finished {
            guard var chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty else { break }
            try chunk.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
                stream.next_in = raw.baseAddress?.assumingMemoryBound(to: CChar.self)
                stream.avail_in = UInt32(raw.count)
                while true {
                    var code: Int32 = 0
                    var produced = 0
                    output.withUnsafeMutableBytes { out in
                        stream.next_out = out.baseAddress?.assumingMemoryBound(to: CChar.self)
                        stream.avail_out = UInt32(out.count)
                        code = BZ2_bzDecompress(&stream)
                        produced = out.count - Int(stream.avail_out)
                    }
                    guard code == bzOK || code == bzStreamEnd else {
                        throw Failure(detail: "Archive du modèle corrompue (bzip2 \(code))")
                    }
                    if produced > 0 {
                        try output.withUnsafeBufferPointer { try reader.feed(UnsafeBufferPointer(rebasing: $0[0..<produced])) }
                    }
                    if code == bzStreamEnd {
                        finished = true
                        break
                    }
                    if stream.avail_in == 0 && stream.avail_out != 0 { break }
                }
            }
        }
        try reader.close()
        guard finished else { throw Failure(detail: "Archive du modèle tronquée") }
    }
}

/// Push parser for a tar byte stream. Only regular files are written; padding blocks are skipped.
private struct TarReader {
    private enum State {
        case header
        /// Writing (or skipping, when `out` is nil) `remaining` data bytes, then `padding` zero bytes.
        case body(remaining: Int, padding: Int, out: FileHandle?)
        /// Collecting the body of a long-name or pax record.
        case meta(remaining: Int, padding: Int, kind: UInt8, data: [UInt8])
        case end
    }

    private let destination: (String) -> URL?
    private var state = State.header
    private var header: [UInt8] = []
    private var skip = 0
    private var longName: String?
    private var paxPath: String?

    init(destination: @escaping (String) -> URL?) {
        self.destination = destination
        header.reserveCapacity(512)
    }

    mutating func close() throws {
        if case .body(_, _, let out) = state { try out?.close() }
    }

    mutating func feed(_ bytes: UnsafeBufferPointer<UInt8>) throws {
        var index = 0
        while index < bytes.count {
            if skip > 0 {
                let n = min(skip, bytes.count - index)
                skip -= n
                index += n
                continue
            }
            switch state {
            case .end:
                return
            case .header:
                let n = min(512 - header.count, bytes.count - index)
                header.append(contentsOf: bytes[index..<(index + n)])
                index += n
                if header.count == 512 {
                    try startEntry()
                    header.removeAll(keepingCapacity: true)
                }
            case .body(let remaining, let padding, let out):
                if remaining > 0 {
                    let n = min(remaining, bytes.count - index)
                    if let out, let base = bytes.baseAddress { try out.write(contentsOf: Data(bytes: base + index, count: n)) }
                    index += n
                    state = .body(remaining: remaining - n, padding: padding, out: out)
                } else {
                    try out?.close()
                    skip = padding
                    state = .header
                }
            case .meta(let remaining, let padding, let kind, var data):
                if remaining > 0 {
                    let n = min(remaining, bytes.count - index)
                    data.append(contentsOf: bytes[index..<(index + n)])
                    index += n
                    state = .meta(remaining: remaining - n, padding: padding, kind: kind, data: data)
                } else {
                    finishMeta(kind: kind, data: data)
                    skip = padding
                    state = .header
                }
            }
        }
        // A body that ended exactly at the end of this piece is closed by the next piece or by `close()`.
        if case .body(0, let padding, let out) = state {
            try out?.close()
            skip = padding
            state = .header
        } else if case .meta(0, let padding, let kind, let data) = state {
            finishMeta(kind: kind, data: data)
            skip = padding
            state = .header
        }
    }

    private mutating func startEntry() throws {
        if header.allSatisfy({ $0 == 0 }) {
            state = .end
            return
        }
        let size = Self.parseSize(header[124..<136])
        let padding = (512 - size % 512) % 512
        let type = header[156]

        switch type {
        case UInt8(ascii: "L"), UInt8(ascii: "x"), UInt8(ascii: "g"):
            state = .meta(remaining: size, padding: padding, kind: type, data: [])
        case 0, UInt8(ascii: "0"), UInt8(ascii: "7"):
            let name = entryName()
            longName = nil
            paxPath = nil
            var out: FileHandle?
            if let url = destination(name) {
                FileManager.default.createFile(atPath: url.path, contents: nil)
                out = try FileHandle(forWritingTo: url)
            }
            state = .body(remaining: size, padding: padding, out: out)
        default:
            // Directories, links and the rest carry no data we want.
            longName = nil
            paxPath = nil
            state = .body(remaining: size, padding: padding, out: nil)
        }
    }

    private func entryName() -> String {
        if let paxPath { return paxPath }
        if let longName { return longName }
        var name = Self.string(header[0..<100])
        // POSIX ustar ("ustar\0" "00") adds a path prefix; GNU's "ustar  " does not.
        if Self.string(header[257..<262]) == "ustar", header[262] == 0 {
            let prefix = Self.string(header[345..<500])
            if !prefix.isEmpty { name = prefix + "/" + name }
        }
        return name
    }

    private mutating func finishMeta(kind: UInt8, data: [UInt8]) {
        switch kind {
        case UInt8(ascii: "L"):
            longName = Self.string(data[...])
        case UInt8(ascii: "x"):
            // Records: "<length> <key>=<value>\n"
            var cursor = 0
            while cursor < data.count {
                guard let space = data[cursor...].firstIndex(of: 0x20),
                    let length = Int(String(decoding: data[cursor..<space], as: UTF8.self)),
                    length > 0, cursor + length <= data.count
                else { break }
                let record = String(decoding: data[(space + 1)..<(cursor + length)], as: UTF8.self)
                    .trimmingCharacters(in: .newlines)
                if record.hasPrefix("path=") { paxPath = String(record.dropFirst(5)) }
                cursor += length
            }
        default:
            break
        }
    }

    private static func string(_ bytes: ArraySlice<UInt8>) -> String {
        let end = bytes.firstIndex(of: 0) ?? bytes.endIndex
        return String(decoding: bytes[bytes.startIndex..<end], as: UTF8.self)
    }

    /// Octal ASCII, or base-256 when the high bit of the first byte is set.
    private static func parseSize(_ field: ArraySlice<UInt8>) -> Int {
        guard let first = field.first else { return 0 }
        if first & 0x80 != 0 {
            var value = Int(first & 0x7F)
            for byte in field.dropFirst() { value = (value << 8) | Int(byte) }
            return value
        }
        var value = 0
        for byte in field {
            if byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "7") {
                value = value * 8 + Int(byte - UInt8(ascii: "0"))
            } else if value > 0 || byte == 0 {
                break
            }
        }
        return value
    }
}
