import Foundation

/// Tracks the session and task backing each stream ID.
///
/// Plugin calls arrive on the bridge queue while `URLSession` delivers its
/// delegate callbacks on a background queue, so every lookup and mutation is
/// serialized here instead of touching a bare dictionary from both.
final class StreamTaskRegistry {
  struct Entry {
    let session: URLSession
    let task: URLSessionDataTask
  }

  private let lock = NSLock()
  private var entries: [String: Entry] = [:]

  func register(id: String, session: URLSession, task: URLSessionDataTask) {
    lock.lock()
    defer { lock.unlock() }
    entries[id] = Entry(session: session, task: task)
  }

  func id(for task: URLSessionTask) -> String? {
    lock.lock()
    defer { lock.unlock() }
    return entries.first(where: { $0.value.task === task })?.key
  }

  @discardableResult
  func remove(id: String) -> Entry? {
    lock.lock()
    defer { lock.unlock() }
    return entries.removeValue(forKey: id)
  }
}
