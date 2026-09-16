import Capacitor
import Foundation

@objc(StreamHttpPlugin)
public class StreamHttpPlugin: CAPPlugin, CAPBridgedPlugin, URLSessionDataDelegate {
  public let identifier = "StreamHttpPlugin"
  public let jsName = "StreamHttp"
  public let pluginMethods: [CAPPluginMethod] = [
    CAPPluginMethod(name: "startStream", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "cancelStream", returnType: CAPPluginReturnPromise),
  ]

  let activeStreams = StreamTaskRegistry()

  @objc public func startStream(_ call: CAPPluginCall) {
    guard let urlString = call.getString("url"), let url = URL(string: urlString) else {
      call.reject("Invalid URL")
      return
    }
    let redirect = call.getString("redirect") ?? "follow"
    guard ["follow", "error"].contains(redirect) else {
      call.reject("redirect must be follow or error")
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
    activeStreams.register(id: id, session: session, task: task, redirect: redirect)
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

  public func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    defer { completionHandler(.allow) }
    guard let id = activeStreams.id(for: dataTask),
      let http = response as? HTTPURLResponse
    else { return }
    var headers: [String: String] = [:]
    for (name, value) in http.allHeaderFields {
      headers[String(describing: name).lowercased()] = String(describing: value)
    }
    notifyListeners("response", data: ["id": id, "status": http.statusCode, "headers": headers])
  }

  public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data)
  {
    guard let chunk = activeStreams.decode(for: dataTask, data: data), !chunk.text.isEmpty else {
      return
    }
    notifyListeners("chunk", data: ["id": chunk.id, "chunk": chunk.text])
  }

  public func urlSession(
    _ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?
  ) {
    guard let id = activeStreams.id(for: task), let entry = activeStreams.remove(id: id) else {
      return
    }
    defer { entry.session.finishTasksAndInvalidate() }
    if let error = error {
      notifyListeners("error", data: ["id": id, "error": error.localizedDescription])
    } else {
      let trailing = entry.decoder.decode(Data(), final: true)
      if !trailing.isEmpty { notifyListeners("chunk", data: ["id": id, "chunk": trailing]) }
      notifyListeners("end", data: ["id": id])
    }
  }

  public func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) {
    guard let policy = activeStreams.redirect(for: task) else {
      completionHandler(nil)
      return
    }
    guard policy == "follow" else {
      completionHandler(nil)
      urlSession(session, task: task, didCompleteWithError: URLError(.httpTooManyRedirects))
      task.cancel()
      return
    }
    completionHandler(request)
  }

}
