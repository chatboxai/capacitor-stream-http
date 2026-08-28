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

  func testOrchestratesNonSuccessResponseBodyInOrder() throws {
    let id = "stream-id"
    let url = try XCTUnwrap(URL(string: "https://example.com/stream"))
    let response = try XCTUnwrap(
      HTTPURLResponse(
        url: url,
        statusCode: 429,
        httpVersion: "HTTP/1.1",
        headerFields: ["Content-Type": "text/event-stream"]
      )
    )
    var notifications: [(name: String, data: [String: Any])] = []
    var eventCountWhenResponseAllowed = 0
    let orchestrator = StreamEventOrchestrator { name, data in
      notifications.append((name: name, data: data))
    }

    orchestrator.receiveResponse(id: id, response: response) { disposition in
      XCTAssertEqual(disposition, .allow)
      eventCountWhenResponseAllowed = notifications.count
    }
    orchestrator.receiveData(id: id, data: Data("data: rate-limited\n\n".utf8))
    orchestrator.complete(id: id, error: nil)

    XCTAssertEqual(eventCountWhenResponseAllowed, 1)
    XCTAssertEqual(notifications.map(\.name), ["response", "chunk", "end"])
    XCTAssertEqual(notifications.compactMap { $0.data["id"] as? String }, [id, id, id])
    XCTAssertEqual(notifications[0].data["status"] as? Int, 429)
    XCTAssertEqual(
      notifications[0].data["headers"] as? [String: String],
      ["content-type": "text/event-stream"])
    XCTAssertEqual(notifications[1].data["chunk"] as? String, "data: rate-limited\n\n")
  }
}
