import Foundation
import XCTest

@testable import StreamHttpPlugin

final class StreamResponseMetadataTests: XCTestCase {
  func testExtractsStatusAndNormalizedHeaders() throws {
    let url = try XCTUnwrap(URL(string: "https://example.com/stream"))
    let response = try XCTUnwrap(
      HTTPURLResponse(
        url: url,
        statusCode: 429,
        httpVersion: "HTTP/1.1",
        headerFields: ["Content-Type": "application/json", "Retry-After": "60"]
      )
    )

    let metadata = StreamResponseMetadata(response: response)

    XCTAssertEqual(metadata.status, 429)
    XCTAssertEqual(metadata.headers["content-type"], "application/json")
    XCTAssertEqual(metadata.headers["retry-after"], "60")
  }
}
