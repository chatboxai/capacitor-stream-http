import Foundation

/// Tracks the session and task backing each stream ID.
///
/// All access is serialized because plugin calls and `URLSession` callbacks
/// use different queues.
final class StreamTaskRegistry {
  struct Entry {
    let session: URLSession
    let task: URLSessionDataTask
    let redirect: String
    let decoder = IncrementalUTF8Decoder()
  }

  private let lock = NSLock()
  private var entries: [String: Entry] = [:]

  func register(
    id: String, session: URLSession, task: URLSessionDataTask, redirect: String = "follow"
  ) {
    lock.lock()
    defer { lock.unlock() }
    entries[id] = Entry(session: session, task: task, redirect: redirect)
  }

  func id(for task: URLSessionTask) -> String? {
    lock.lock()
    defer { lock.unlock() }
    return entries.first(where: { $0.value.task === task })?.key
  }

  func decode(for task: URLSessionTask, data: Data) -> (id: String, text: String)? {
    lock.lock()
    defer { lock.unlock() }
    guard let (id, entry) = entries.first(where: { $0.value.task === task }) else { return nil }
    return (id, entry.decoder.decode(data))
  }

  func redirect(for task: URLSessionTask) -> String? {
    lock.lock()
    defer { lock.unlock() }
    return entries.first(where: { $0.value.task === task })?.value.redirect
  }

  @discardableResult
  func remove(id: String) -> Entry? {
    lock.lock()
    defer { lock.unlock() }
    return entries.removeValue(forKey: id)
  }
}
