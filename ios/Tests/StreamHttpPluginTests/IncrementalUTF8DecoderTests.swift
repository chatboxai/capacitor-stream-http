import Foundation
import XCTest

@testable import StreamHttpPlugin

final class IncrementalUTF8DecoderTests: XCTestCase {
  func testEverySplitPreservesUnicodeAndSSE() {
    let text = "data: {\"text\":\"中文😀 café\"}\r\n\r\n"
    let data = Data(text.utf8)
    for split in 0...data.count {
      let decoder = IncrementalUTF8Decoder()
      let output =
        decoder.decode(data.prefix(split))
        + decoder.decode(data.dropFirst(split)) + decoder.decode(Data(), final: true)
      XCTAssertEqual(output, text, "split \(split)")
    }
  }

  func testSingleBytesAndIndependentStreams() {
    let first = IncrementalUTF8Decoder()
    let second = IncrementalUTF8Decoder()
    var output = ""
    for byte in "你好😀".utf8 {
      output += first.decode(Data([byte]))
      XCTAssertEqual(second.decode(Data("x".utf8)), "x")
    }
    XCTAssertEqual(output + first.decode(Data(), final: true), "你好😀")
  }

  func testIncompleteAndInvalidInputUsesReplacementCharacters() {
    let decoder = IncrementalUTF8Decoder()
    XCTAssertEqual(decoder.decode(Data([0xE4, 0xB8])), "")
    XCTAssertEqual(decoder.decode(Data(), final: true), "�")
    XCTAssertEqual(decoder.decode(Data([0xFF])), "�")
  }
}
