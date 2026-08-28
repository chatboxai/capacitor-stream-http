import Foundation
import XCTest

@testable import StreamHttpPlugin

final class StreamTaskRegistryTests: XCTestCase {
  func testResolvesIdsAndReleasesEntriesOnRemoval() throws {
    let registry = StreamTaskRegistry()
    let session = URLSession(configuration: .ephemeral)
    let url = try XCTUnwrap(URL(string: "https://example.com/stream"))
    let first = session.dataTask(with: url)
    let second = session.dataTask(with: url)

    registry.register(id: "first", session: session, task: first)
    registry.register(id: "second", session: session, task: second)

    XCTAssertEqual(registry.id(for: first), "first")
    XCTAssertEqual(registry.id(for: second), "second")

    let removed = registry.remove(id: "first")

    XCTAssertTrue(removed?.task === first)
    XCTAssertNil(registry.id(for: first))
    XCTAssertNil(registry.remove(id: "first"))
    XCTAssertEqual(registry.id(for: second), "second")
  }

  func testServesConcurrentRegistrationsAndLookups() throws {
    let registry = StreamTaskRegistry()
    let session = URLSession(configuration: .ephemeral)
    let url = try XCTUnwrap(URL(string: "https://example.com/stream"))
    let tasks = (0..<200).map { _ in session.dataTask(with: url) }

    DispatchQueue.concurrentPerform(iterations: tasks.count) { index in
      registry.register(id: "stream-\(index)", session: session, task: tasks[index])
      _ = registry.id(for: tasks[index])
    }

    for (index, task) in tasks.enumerated() {
      XCTAssertEqual(registry.id(for: task), "stream-\(index)")
    }

    DispatchQueue.concurrentPerform(iterations: tasks.count) { index in
      registry.remove(id: "stream-\(index)")
    }

    for task in tasks {
      XCTAssertNil(registry.id(for: task))
    }
  }
}
