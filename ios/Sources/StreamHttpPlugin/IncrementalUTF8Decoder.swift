import Foundation

/// Retains at most one incomplete UTF-8 scalar between network reads.
final class IncrementalUTF8Decoder {
  private var pending = Data()

  func decode(_ data: Data, final: Bool = false) -> String {
    pending.append(data)
    var end = pending.count
    if !final && !pending.isEmpty {
      let bytes = Array(pending.suffix(4))
      var start = bytes.count - 1
      while start > 0 && bytes[start] & 0xC0 == 0x80 { start -= 1 }
      let lead = bytes[start]
      let length =
        lead >= 0xF0 && lead <= 0xF4
        ? 4
        : lead >= 0xE0 && lead <= 0xEF
          ? 3
          : lead >= 0xC2 && lead <= 0xDF ? 2 : 1
      if bytes.count - start < length { end -= bytes.count - start }
    }
    let text = String(decoding: pending.prefix(end), as: UTF8.self)
    pending = Data(pending.dropFirst(end))
    return text
  }
}
