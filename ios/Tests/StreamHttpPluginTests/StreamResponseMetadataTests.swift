import Foundation
import XCTest

@testable import StreamHttpPlugin

final class StreamResponseMetadataTests: XCTestCase {
  func testResponsePrecedesBodyAndCompletionForSuccessAndError() throws {
    for status in [200, 429] {
      let plugin = RecordingStreamHttpPlugin()
      let session = URLSession(configuration: .ephemeral)
      defer { session.invalidateAndCancel() }
      let url = try XCTUnwrap(URL(string: "https://example.com/stream"))
      let task = session.dataTask(with: url)
      plugin.activeStreams.register(id: "stream-id", session: session, task: task)
      let response = try XCTUnwrap(
        HTTPURLResponse(
          url: url, statusCode: status, httpVersion: "HTTP/1.1",
          headerFields: ["Content-Type": "text/event-stream", "Retry-After": "60"]))

      var allowed = false
      plugin.urlSession(session, dataTask: task, didReceive: response) { disposition in
        XCTAssertEqual(disposition, .allow)
        XCTAssertEqual(plugin.notifications.map(\.name), ["response"])
        allowed = true
      }
      XCTAssertTrue(allowed)
      plugin.urlSession(session, dataTask: task, didReceive: Data("data: message\n\n".utf8))
      plugin.urlSession(session, task: task, didCompleteWithError: nil)

      XCTAssertEqual(plugin.notifications.map(\.name), ["response", "chunk", "end"])
      XCTAssertEqual(
        plugin.notifications.compactMap { $0.data["id"] as? String },
        ["stream-id", "stream-id", "stream-id"])
      XCTAssertEqual(plugin.notifications[0].data["status"] as? Int, status)
      XCTAssertEqual(
        plugin.notifications[0].data["headers"] as? [String: String],
        ["content-type": "text/event-stream", "retry-after": "60"])
      XCTAssertEqual(plugin.notifications[1].data["chunk"] as? String, "data: message\n\n")
      XCTAssertNil(plugin.activeStreams.id(for: task))
    }
  }

  func testEveryByteOfUnicodeReachesTheBridge() throws {
    let plugin = RecordingStreamHttpPlugin()
    let session = URLSession(configuration: .ephemeral)
    let task = session.dataTask(with: try XCTUnwrap(URL(string: "https://example.com")))
    plugin.activeStreams.register(id: "unicode", session: session, task: task)
    for byte in "data: 中文😀\n\n".utf8 {
      plugin.urlSession(session, dataTask: task, didReceive: Data([byte]))
    }
    plugin.urlSession(session, task: task, didCompleteWithError: nil)
    XCTAssertEqual(
      plugin.notifications.compactMap { $0.data["chunk"] as? String }.joined(), "data: 中文😀\n\n")
    XCTAssertEqual(plugin.notifications.last?.name, "end")
  }

  func testRedirectPolicyRejectsBeforeForwardingTheRequest() throws {
    for policy in ["follow", "error"] {
      let plugin = RecordingStreamHttpPlugin()
      let session = URLSession(configuration: .ephemeral)
      defer { session.invalidateAndCancel() }
      let url = try XCTUnwrap(URL(string: "https://example.com"))
      let task = session.dataTask(with: url)
      plugin.activeStreams.register(id: "redirect", session: session, task: task, redirect: policy)
      let response = try XCTUnwrap(
        HTTPURLResponse(url: url, statusCode: 302, httpVersion: nil, headerFields: [:]))
      let request = URLRequest(url: try XCTUnwrap(URL(string: "https://other.example.com")))
      plugin.urlSession(
        session, task: task, willPerformHTTPRedirection: response, newRequest: request
      ) { forwarded in
        XCTAssertEqual(forwarded?.url, policy == "follow" ? request.url : nil)
      }
      XCTAssertEqual(plugin.notifications.map(\.name), policy == "follow" ? [] : ["error"])
      plugin.urlSession(session, task: task, didCompleteWithError: URLError(.cancelled))
      XCTAssertEqual(plugin.notifications.count, 1)
    }
  }

  func testEmptyResponseEmitsMetadataAndEnd() throws {
    let plugin = RecordingStreamHttpPlugin()
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }
    let url = try XCTUnwrap(URL(string: "https://example.com/stream"))
    let task = session.dataTask(with: url)
    plugin.activeStreams.register(id: "empty", session: session, task: task)
    let response = try XCTUnwrap(
      HTTPURLResponse(url: url, statusCode: 204, httpVersion: "HTTP/1.1", headerFields: [:]))

    plugin.urlSession(session, dataTask: task, didReceive: response) { disposition in
      XCTAssertEqual(disposition, .allow)
    }
    plugin.urlSession(session, task: task, didCompleteWithError: nil)

    XCTAssertEqual(plugin.notifications.map(\.name), ["response", "end"])
    XCTAssertEqual(plugin.notifications[0].data["status"] as? Int, 204)
  }

  func testRemovedStreamIgnoresLateCallbacksAndAllowsResponseCompletion() throws {
    let plugin = RecordingStreamHttpPlugin()
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }
    let url = try XCTUnwrap(URL(string: "https://example.com/stream"))
    let task = session.dataTask(with: url)
    plugin.activeStreams.register(id: "cancelled", session: session, task: task)
    plugin.activeStreams.remove(id: "cancelled")
    let response = try XCTUnwrap(
      HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [:]))

    var allowed = false
    plugin.urlSession(session, dataTask: task, didReceive: response) { disposition in
      XCTAssertEqual(disposition, .allow)
      allowed = true
    }
    plugin.urlSession(session, dataTask: task, didReceive: Data("late".utf8))
    plugin.urlSession(session, task: task, didCompleteWithError: URLError(.cancelled))

    XCTAssertTrue(allowed)
    XCTAssertTrue(plugin.notifications.isEmpty)
  }
}

private final class RecordingStreamHttpPlugin: StreamHttpPlugin {
  private let recorder = NotificationRecorder()

  var notifications: [(name: String, data: [String: Any])] {
    recorder.snapshot
  }

  override func notifyListeners(_ eventName: String, data: [String: Any]?) {
    recorder.append(name: eventName, data: data ?? [:])
  }
}

private final class NotificationRecorder: @unchecked Sendable {
  private let lock = NSLock()
  private var notifications: [(name: String, data: [String: Any])] = []

  var snapshot: [(name: String, data: [String: Any])] {
    lock.lock()
    defer { lock.unlock() }
    return notifications
  }

  func append(name: String, data: [String: Any]) {
    lock.lock()
    defer { lock.unlock() }
    notifications.append((name, data))
  }
}
