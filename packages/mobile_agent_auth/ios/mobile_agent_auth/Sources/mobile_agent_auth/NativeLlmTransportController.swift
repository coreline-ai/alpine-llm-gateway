import AppAuth
import Flutter
import Foundation

final class NativeLlmTransportController: NSObject, FlutterStreamHandler {
  private let stateStore: KeychainAuthStateStore
  private let lock = NSLock()
  private let authStateLock = NSLock()
  private let onReauthenticationRequired: () -> Void
  private var requests: [String: ActiveNativeRequest] = [:]
  private var eventSink: FlutterEventSink?
  private var cachedAuthState: OIDAuthState?

  init(
    stateStore: KeychainAuthStateStore,
    onReauthenticationRequired: @escaping () -> Void
  ) {
    self.stateStore = stateStore
    self.onReauthenticationRequired = onReauthenticationRequired
  }

  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "startStream":
      startStream(arguments: call.arguments as? [String: Any], result: result)
    case "cancelRequest":
      cancelRequest(arguments: call.arguments as? [String: Any], result: result)
    case "requestState":
      requestState(arguments: call.arguments as? [String: Any], result: result)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func startStream(arguments: [String: Any]?, result: @escaping FlutterResult) {
    guard currentEventSink() != nil else {
      result(error("event_listener_unavailable", "Event listener is not active."))
      return
    }
    let input: NativeStreamInput
    do {
      input = try NativeStreamInput(arguments: arguments)
    } catch _ {
      result(error("request_invalid", "Streaming request is invalid."))
      return
    }
    let active = ActiveNativeRequest()
    lock.lock()
    let inserted = requests[input.requestId] == nil
    if inserted { requests[input.requestId] = active }
    lock.unlock()
    guard inserted else {
      result(error("request_in_progress", "Request is already active."))
      return
    }
    let state: OIDAuthState
    do {
      guard let restored = try currentAuthState(), restored.isAuthorized else {
        remove(requestId: input.requestId, expected: active)
        onReauthenticationRequired()
        result(error("reauthentication_required", "A valid login is required."))
        return
      }
      state = restored
    } catch _ {
      remove(requestId: input.requestId, expected: active)
      result(error("storage_failure", "Secure storage failed."))
      return
    }

    state.performAction { [weak self] accessToken, _, authError in
      guard let self else { return }
      guard !active.isCancelled else {
        self.remove(requestId: input.requestId, expected: active)
        result(self.error("request_cancelled", "Request was cancelled."))
        return
      }
      guard authError == nil, let accessToken, !accessToken.isEmpty else {
        self.remove(requestId: input.requestId, expected: active)
        self.onReauthenticationRequired()
        result(self.error("reauthentication_required", "Token refresh failed."))
        return
      }
      do {
        try self.stateStore.write(state)
      } catch {
        self.remove(requestId: input.requestId, expected: active)
        result(self.error("storage_failure", "Secure storage failed."))
        return
      }
      let task = NativeStreamTask(
        input: input,
        accessToken: accessToken,
        onEvent: { [weak self] type, data in
          if type == "error", data["code"] as? String == "reauthentication_required" {
            self?.onReauthenticationRequired()
          }
          self?.emit(requestId: input.requestId, type: type, data: data)
        },
        onComplete: { [weak self] in
          self?.remove(requestId: input.requestId, expected: active)
        }
      )
      guard active.install(task: task) else {
        self.remove(requestId: input.requestId, expected: active)
        task.cancel(sendServerCancel: false)
        result(self.error("request_cancelled", "Request was cancelled."))
        return
      }
      task.start()
      result(nil)
    }
  }

  private func cancelRequest(arguments: [String: Any]?, result: @escaping FlutterResult) {
    guard
      let requestId = arguments?["requestId"] as? String,
      requestId.range(of: "^[A-Za-z0-9_-]{8,80}$", options: .regularExpression) != nil,
      let baseUrl = arguments?["bffBaseUrl"] as? String,
      (try? NativeStreamInput.validateBaseURL(baseUrl)) != nil
    else {
      result(error("request_invalid", "Request ID is invalid."))
      return
    }
    guard let active = remove(requestId: requestId) else {
      result(cancelResult(
        requestId: requestId,
        localCancelled: false,
        serverAcknowledgment: "not_required"
      ))
      return
    }
    let task = active.cancel()
    emit(requestId: requestId, type: "cancelled", data: [:])
    guard let task else {
      result(cancelResult(
        requestId: requestId,
        localCancelled: true,
        serverAcknowledgment: "not_required"
      ))
      return
    }
    task.cancel(sendServerCancel: true) { [weak self] acknowledgment in
      DispatchQueue.main.async {
        guard let self else { return }
        result(self.cancelResult(
          requestId: requestId,
          localCancelled: true,
          serverAcknowledgment: acknowledgment
        ))
      }
    }
  }

  private func requestState(arguments: [String: Any]?, result: @escaping FlutterResult) {
    guard
      let arguments,
      Set(arguments.keys).isSubset(of: Set(["requestId"])),
      let requestId = arguments["requestId"] as? String,
      requestId.range(of: "^[A-Za-z0-9_-]{8,80}$", options: .regularExpression) != nil
    else {
      result(error("request_invalid", "Request ID is invalid."))
      return
    }
    lock.lock()
    let state = requests[requestId]?.state ?? "not_found"
    lock.unlock()
    result(["requestId": requestId, "state": state])
  }

  private func cancelResult(
    requestId: String,
    localCancelled: Bool,
    serverAcknowledgment: String
  ) -> [String: Any] {
    [
      "requestId": requestId,
      "localCancelled": localCancelled,
      "serverAcknowledgment": serverAcknowledgment,
    ]
  }

  private func remove(requestId: String) -> ActiveNativeRequest? {
    lock.lock()
    defer { lock.unlock() }
    return requests.removeValue(forKey: requestId)
  }

  private func remove(requestId: String, expected: ActiveNativeRequest) {
    lock.lock()
    if requests[requestId] === expected { requests.removeValue(forKey: requestId) }
    lock.unlock()
  }

  func cancelAll() {
    lock.lock()
    let active = Array(requests.values)
    requests.removeAll()
    lock.unlock()
    active.forEach { $0.cancel()?.cancel(sendServerCancel: false) }
  }

  func updateAuthState(_ state: OIDAuthState) {
    authStateLock.lock()
    cachedAuthState = state
    authStateLock.unlock()
  }

  func clearAuthState() {
    authStateLock.lock()
    cachedAuthState = nil
    authStateLock.unlock()
  }

  func revokeSession(bffBaseURL: URL?, completion: @escaping () -> Void) {
    cancelAll()
    let state = try? currentAuthState()
    clearAuthState()
    stateStore.clear()
    guard let state else {
      DispatchQueue.main.async(execute: completion)
      return
    }

    var revocations: [URLRequest] = []
    if
      let bffBaseURL,
      let accessToken = state.lastTokenResponse?.accessToken,
      let endpoint = URL(string: "/v1/session/revoke", relativeTo: bffBaseURL)?.absoluteURL
    {
      var request = URLRequest(url: endpoint)
      request.httpMethod = "POST"
      request.timeoutInterval = 5
      request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
      revocations.append(request)
    }

    let authorizationRequest = state.lastAuthorizationResponse.request
    let configuration = authorizationRequest.configuration
    let discovery = configuration.discoveryDocument?.discoveryDictionary
    if
      let refreshToken = state.refreshToken,
      !refreshToken.isEmpty,
      let rawEndpoint = discovery?["revocation_endpoint"] as? String,
      let endpoint = URL(string: rawEndpoint),
      let issuer = configuration.issuer,
      Self.sameHostHTTPS(issuer: issuer, endpoint: endpoint)
    {
      var request = URLRequest(url: endpoint)
      request.httpMethod = "POST"
      request.timeoutInterval = 5
      request.setValue(
        "application/x-www-form-urlencoded; charset=utf-8",
        forHTTPHeaderField: "Content-Type"
      )
      request.httpBody = Self.formBody([
        "token": refreshToken,
        "token_type_hint": "refresh_token",
        "client_id": authorizationRequest.clientID,
      ])
      revocations.append(request)
    }

    guard !revocations.isEmpty else {
      DispatchQueue.main.async(execute: completion)
      return
    }
    let group = DispatchGroup()
    let session = URLSession(configuration: .ephemeral)
    for request in revocations {
      group.enter()
      session.dataTask(with: request) { _, _, _ in
        group.leave()
      }.resume()
    }
    group.notify(queue: .main) {
      session.finishTasksAndInvalidate()
      completion()
    }
  }

  private static func sameHostHTTPS(issuer: URL, endpoint: URL) -> Bool {
    issuer.scheme == "https"
      && endpoint.scheme == "https"
      && issuer.host?.caseInsensitiveCompare(endpoint.host ?? "") == .orderedSame
      && endpoint.user == nil
      && endpoint.password == nil
      && endpoint.fragment == nil
  }

  private static func formBody(_ values: [String: String]) -> Data? {
    var components = URLComponents()
    components.queryItems = values.sorted(by: { $0.key < $1.key }).map {
      URLQueryItem(name: $0.key, value: $0.value)
    }
    return components.percentEncodedQuery?.data(using: .utf8)
  }

  private func currentAuthState() throws -> OIDAuthState? {
    authStateLock.lock()
    defer { authStateLock.unlock() }
    if let cachedAuthState, cachedAuthState.isAuthorized {
      return cachedAuthState
    }
    let restored = try stateStore.read()
    cachedAuthState = restored
    return restored
  }

  private func emit(requestId: String, type: String, data: [String: Any]) {
    DispatchQueue.main.async { [weak self] in
      self?.currentEventSink()?([
        "requestId": requestId,
        "type": type,
        "data": data,
      ])
    }
  }

  private func currentEventSink() -> FlutterEventSink? {
    lock.lock()
    defer { lock.unlock() }
    return eventSink
  }

  func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    lock.lock()
    eventSink = events
    lock.unlock()
    return nil
  }

  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    lock.lock()
    eventSink = nil
    lock.unlock()
    cancelAll()
    return nil
  }

  private func error(_ code: String, _ message: String) -> FlutterError {
    FlutterError(code: code, message: message, details: nil)
  }
}

struct NativeStreamInput {
  let requestId: String
  let baseURL: URL
  let requestData: Data

  init(arguments: [String: Any]?) throws {
    guard
      let arguments,
      Set(arguments.keys).isSubset(of: Set(["bffBaseUrl", "request"])),
      let baseUrl = arguments["bffBaseUrl"] as? String,
      let baseURL = try? Self.validateBaseURL(baseUrl),
      let request = arguments["request"] as? [String: Any],
      let requestId = request["request_id"] as? String,
      requestId.range(of: "^[A-Za-z0-9_-]{8,80}$", options: .regularExpression) != nil,
      JSONSerialization.isValidJSONObject(request)
    else { throw NativeStreamInputError.invalid }
    let requestData = try JSONSerialization.data(withJSONObject: request)
    guard requestData.count <= 500_000 else { throw NativeStreamInputError.invalid }
    self.requestId = requestId
    self.baseURL = baseURL
    self.requestData = requestData
  }

  static func validateBaseURL(_ value: String) throws -> URL {
    guard
      let components = URLComponents(string: value),
      components.scheme == "https",
      components.host != nil,
      components.user == nil,
      components.password == nil,
      components.query == nil,
      components.fragment == nil,
      let url = components.url
    else { throw NativeStreamInputError.invalid }
    return url
  }
}

private enum NativeStreamInputError: Error { case invalid }

private final class ActiveNativeRequest {
  private let lock = NSLock()
  private var task: NativeStreamTask?
  private var cancelled = false
  private var phase = "preparing"

  var isCancelled: Bool {
    lock.lock()
    defer { lock.unlock() }
    return cancelled
  }

  var state: String {
    lock.lock()
    defer { lock.unlock() }
    return phase
  }

  func install(task: NativeStreamTask) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard !cancelled else { return false }
    self.task = task
    phase = "streaming"
    return true
  }

  func cancel() -> NativeStreamTask? {
    lock.lock()
    defer { lock.unlock() }
    cancelled = true
    phase = "cancelling"
    return task
  }
}

private final class NativeStreamTask: NSObject, URLSessionDataDelegate {
  private let input: NativeStreamInput
  private let accessToken: String
  private let onEvent: (String, [String: Any]) -> Void
  private let onComplete: () -> Void
  private let lock = NSLock()
  private var session: URLSession?
  private var task: URLSessionDataTask?
  private var buffer = Data()
  private var eventType: String?
  private var dataLines: [String] = []
  private var terminalEmitted = false
  private var cancelled = false

  init(
    input: NativeStreamInput,
    accessToken: String,
    onEvent: @escaping (String, [String: Any]) -> Void,
    onComplete: @escaping () -> Void
  ) {
    self.input = input
    self.accessToken = accessToken
    self.onEvent = onEvent
    self.onComplete = onComplete
  }

  func start() {
    guard let url = URL(string: "/v1/chat/stream", relativeTo: input.baseURL)?.absoluteURL else {
      emitTerminal("error", ["code": "configuration_invalid"])
      onComplete()
      return
    }
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.httpBody = input.requestData
    request.timeoutInterval = 130
    request.cachePolicy = .reloadIgnoringLocalCacheData
    request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
    request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
    let queue = OperationQueue()
    queue.maxConcurrentOperationCount = 1
    let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: queue)
    self.session = session
    let task = session.dataTask(with: request)
    self.task = task
    task.resume()
  }

  func cancel(
    sendServerCancel: Bool,
    completion: ((String) -> Void)? = nil
  ) {
    lock.lock()
    cancelled = true
    let task = self.task
    lock.unlock()
    task?.cancel()
    if sendServerCancel {
      postServerCancel(completion: completion)
    } else {
      completion?("not_required")
    }
    session?.invalidateAndCancel()
  }

  func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    guard let response = response as? HTTPURLResponse else {
      emitTerminal("error", ["code": "network_unavailable"])
      completionHandler(.cancel)
      return
    }
    guard (200...299).contains(response.statusCode) else {
      emitTerminal("error", ["code": statusError(response.statusCode)])
      completionHandler(.cancel)
      return
    }
    completionHandler(.allow)
  }

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    lock.lock()
    buffer.append(data)
    while let newline = buffer.firstIndex(of: 0x0A) {
      var lineData = buffer.prefix(upTo: newline)
      buffer.removeSubrange(...newline)
      if lineData.last == 0x0D { lineData = lineData.dropLast() }
      guard let line = String(data: lineData, encoding: .utf8) else {
        lock.unlock()
        emitTerminal("error", ["code": "stream_invalid"])
        task?.cancel()
        return
      }
      consume(line: line)
    }
    lock.unlock()
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didCompleteWithError error: Error?
  ) {
    lock.lock()
    let shouldEmitError = !cancelled && !terminalEmitted
    lock.unlock()
    if shouldEmitError {
      emitTerminal("error", ["code": error == nil ? "stream_ended_early" : "network_unavailable"])
    }
    session.finishTasksAndInvalidate()
    onComplete()
  }

  private func consume(line: String) {
    if line.isEmpty {
      guard !dataLines.isEmpty else { eventType = nil; return }
      let raw = dataLines.joined(separator: "\n")
      dataLines.removeAll(keepingCapacity: true)
      let type = eventType ?? "message"
      eventType = nil
      guard
        let data = raw.data(using: .utf8),
        let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
      else {
        emitTerminalLocked("error", ["code": "stream_invalid"])
        task?.cancel()
        return
      }
      if ["done", "cancelled", "error"].contains(type) {
        emitTerminalLocked(type, object)
      } else {
        onEvent(type, object)
      }
    } else if line.hasPrefix("event:") {
      eventType = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
    } else if line.hasPrefix("data:") {
      dataLines.append(String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces))
    }
  }

  private func emitTerminal(_ type: String, _ data: [String: Any]) {
    lock.lock()
    emitTerminalLocked(type, data)
    lock.unlock()
  }

  private func emitTerminalLocked(_ type: String, _ data: [String: Any]) {
    guard !terminalEmitted else { return }
    terminalEmitted = true
    onEvent(type, data)
  }

  private func postServerCancel(completion: ((String) -> Void)?) {
    guard let url = URL(
      string: "/v1/requests/\(input.requestId)/cancel",
      relativeTo: input.baseURL
    )?.absoluteURL else {
      completion?("unavailable")
      return
    }
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = 5
    request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
    let session = URLSession(configuration: .ephemeral)
    session.dataTask(with: request) { _, response, _ in
      let status = (response as? HTTPURLResponse)?.statusCode
      let acknowledgment: String
      switch status {
      case 202: acknowledgment = "accepted"
      case 404: acknowledgment = "not_active"
      default: acknowledgment = "unavailable"
      }
      completion?(acknowledgment)
      session.finishTasksAndInvalidate()
    }.resume()
  }

  private func statusError(_ status: Int) -> String {
    switch status {
    case 401: return "reauthentication_required"
    case 403: return "provider_access_denied"
    case 404: return "provider_not_found"
    case 429: return "provider_rate_limited"
    case 500...599: return "provider_unavailable"
    default: return "request_rejected"
    }
  }
}
