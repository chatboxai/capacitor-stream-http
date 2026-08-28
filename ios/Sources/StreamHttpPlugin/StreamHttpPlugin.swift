import Capacitor
import Foundation

struct StreamResponseMetadata {
  let status: Int
  let headers: [String: String]

  init(response: HTTPURLResponse) {
    status = response.statusCode
    headers = response.allHeaderFields.reduce(into: [:]) { result, header in
      let name = String(describing: header.key).lowercased()
      let value = String(describing: header.value)
      if let existing = result[name] {
        result[name] = "\(existing), \(value)"
      } else {
        result[name] = value
      }
    }
  }
}

enum StreamHttpEvent {
  case response(id: String, status: Int, headers: [String: String])
  case chunk(id: String, chunk: String)
  case end(id: String)
  case error(id: String, error: String)
}

struct StreamEventOrchestrator {
  let notify: (String, [String: Any]) -> Void

  func receiveResponse(
    id: String,
    response: HTTPURLResponse,
    completionHandler: (URLSession.ResponseDisposition) -> Void
  ) {
    let metadata = StreamResponseMetadata(response: response)
    emit(.response(id: id, status: metadata.status, headers: metadata.headers))
    completionHandler(.allow)
  }

  func receiveData(id: String, data: Data) {
    emit(.chunk(id: id, chunk: String(data: data, encoding: .utf8) ?? ""))
  }

  func complete(id: String, error: Error?) {
    if let error {
      emit(.error(id: id, error: error.localizedDescription))
    } else {
      emit(.end(id: id))
    }
  }

  private func emit(_ event: StreamHttpEvent) {
    switch event {
    case .response(let id, let status, let headers):
      notify("response", ["id": id, "status": status, "headers": headers])
    case .chunk(let id, let chunk):
      notify("chunk", ["id": id, "chunk": chunk])
    case .end(let id):
      notify("end", ["id": id])
    case .error(let id, let error):
      notify("error", ["id": id, "error": error])
    }
  }
}

@objc(StreamHttpPlugin)
public class StreamHttpPlugin: CAPPlugin, CAPBridgedPlugin, URLSessionDataDelegate {
  public let identifier = "StreamHttpPlugin"
  public let jsName = "StreamHttp"
  public let pluginMethods: [CAPPluginMethod] = [
    CAPPluginMethod(name: "startStream", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "cancelStream", returnType: CAPPluginReturnPromise),
  ]

  private let activeStreams = StreamTaskRegistry()
  private var streamEvents: StreamEventOrchestrator {
    StreamEventOrchestrator(notify: { [weak self] eventName, data in
      self?.notifyListeners(eventName, data: data)
    })
  }

  @objc public func startStream(_ call: CAPPluginCall) {
    guard let urlString = call.getString("url"), let url = URL(string: urlString) else {
      call.reject("Invalid URL")
      return
    }
    let method = call.getString("method") ?? "GET"
    let headers = call.getObject("headers") as? [String: String] ?? [:]
    let body = call.getString("body")?.data(using: .utf8)

    var request = URLRequest(url: url)
    request.httpMethod = method
    request.httpBody = body
    for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }

    let config = URLSessionConfiguration.default
    config.waitsForConnectivity = true
    config.allowsConstrainedNetworkAccess = true
    config.allowsExpensiveNetworkAccess = true
    config.requestCachePolicy = .reloadIgnoringLocalCacheData

    let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    let task = session.dataTask(with: request)
    let id = UUID().uuidString
    activeStreams.register(id: id, session: session, task: task)
    call.resolve(["id": id])
    task.resume()
  }

  @objc public func cancelStream(_ call: CAPPluginCall) {
    guard let id = call.getString("id") else {
      call.reject("Missing id")
      return
    }
    if let removed = activeStreams.remove(id: id) {
      removed.task.cancel()
      removed.session.invalidateAndCancel()
    }
    call.resolve()
  }

  public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data)
  {
    guard let id = activeStreams.id(for: dataTask) else { return }
    streamEvents.receiveData(id: id, data: data)
  }

  public func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    guard
      let id = activeStreams.id(for: dataTask),
      let httpResponse = response as? HTTPURLResponse
    else {
      // Without metadata to report, keep streaming the body as before.
      completionHandler(.allow)
      return
    }

    streamEvents.receiveResponse(
      id: id, response: httpResponse, completionHandler: completionHandler)
  }

  public func urlSession(
    _ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?
  ) {
    guard let id = activeStreams.id(for: task) else { return }
    streamEvents.complete(id: id, error: error)
    activeStreams.remove(id: id)
  }
}
