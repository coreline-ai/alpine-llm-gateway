import AppAuth
import Flutter
import Security
import UIKit

public final class MobileAgentAuthPlugin: NSObject, FlutterPlugin, FlutterApplicationLifeCycleDelegate, FlutterStreamHandler {
  private static let channelName = "mobile_agent_auth"
  private static let authEventChannelName = "mobile_agent_auth/events"
  private static let conversationChannelName = "mobile_agent_conversations"
  private static let protocolVersion = 1
  private static let conversationSchemaVersion = 1
  private let stateStore = KeychainAuthStateStore()
  private let conversationStore = SecureConversationStore()
  private let conversationQueue = DispatchQueue(label: "ai.coreline.mobileagent.conversations")
  private lazy var transport = NativeLlmTransportController(
    stateStore: stateStore,
    onReauthenticationRequired: { [weak self] in
      self?.emitAuthEvent(
        reason: "reauthentication_required",
        session: self?.reauthenticationRequired() ?? [:]
      )
    }
  )
  private var currentAuthorizationFlow: OIDExternalUserAgentSession?
  private var pendingResult: FlutterResult?
  private var authEventSink: FlutterEventSink?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: channelName, binaryMessenger: registrar.messenger())
    let instance = MobileAgentAuthPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
    registrar.addApplicationDelegate(instance)
    let authEvents = FlutterEventChannel(
      name: authEventChannelName,
      binaryMessenger: registrar.messenger()
    )
    authEvents.setStreamHandler(instance)
    let conversations = FlutterMethodChannel(
      name: conversationChannelName,
      binaryMessenger: registrar.messenger()
    )
    conversations.setMethodCallHandler(instance.handleConversation)
    let transportChannel = FlutterMethodChannel(
      name: "mobile_agent_llm_transport",
      binaryMessenger: registrar.messenger()
    )
    transportChannel.setMethodCallHandler(instance.transport.handle)
    let transportEvents = FlutterEventChannel(
      name: "mobile_agent_llm_transport/events",
      binaryMessenger: registrar.messenger()
    )
    transportEvents.setStreamHandler(instance.transport)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getCapabilities":
      result(capabilities())
    case "signIn":
      signIn(arguments: call.arguments as? [String: Any], result: result)
    case "restoreSession":
      restoreSession(result: result)
    case "cancelSignIn":
      cancelSignIn(result: result)
    case "signOut":
      signOut(arguments: call.arguments as? [String: Any], result: result)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func handleConversation(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    conversationQueue.async { [weak self] in
      guard let self else { return }
      do {
        let value: Any?
        switch call.method {
        case "loadConversationSnapshot":
          let payload = try self.conversationStore.read()
          value = payload.map {
            [
              "schemaVersion": Self.conversationSchemaVersion,
              "payload": $0,
            ]
          }
        case "saveConversationSnapshot":
          guard let arguments = call.arguments as? [String: Any],
                Set(arguments.keys) == Set(["schemaVersion", "payload"]),
                arguments["schemaVersion"] as? Int == Self.conversationSchemaVersion,
                let payload = arguments["payload"] as? String
          else {
            throw ConversationVaultError.invalid
          }
          try self.conversationStore.write(payload)
          value = nil
        case "clearConversationSnapshot":
          guard call.arguments == nil else { throw ConversationVaultError.invalid }
          try self.conversationStore.clear()
          value = nil
        default:
          DispatchQueue.main.async { result(FlutterMethodNotImplemented) }
          return
        }
        DispatchQueue.main.async { result(value) }
      } catch ConversationVaultError.invalid {
        DispatchQueue.main.async {
          result(self.stableError("conversation_invalid", "Conversation payload is invalid."))
        }
      } catch {
        DispatchQueue.main.async {
          result(self.stableError("conversation_storage_failure", "Encrypted conversation storage failed."))
        }
      }
    }
  }

  private func signIn(arguments: [String: Any]?, result: @escaping FlutterResult) {
    guard pendingResult == nil else {
      result(stableError("operation_in_progress", "An authorization operation is active."))
      return
    }
    let configuration: OAuthConfiguration
    do {
      configuration = try OAuthConfiguration(arguments: arguments)
    } catch {
      result(stableError("configuration_invalid", "OIDC configuration is invalid."))
      return
    }
    guard let presenter = topViewController() else {
      result(stableError("activity_unavailable", "No foreground view controller is available."))
      return
    }

    pendingResult = result
    OIDAuthorizationService.discoverConfiguration(forIssuer: configuration.issuer) {
      [weak self] serviceConfiguration, _ in
      guard let self, self.pendingResult != nil else { return }
      guard let serviceConfiguration else {
        self.finishWithError("oauth_discovery_failed", "OIDC discovery failed.")
        return
      }
      var additionalParameters: [String: String]?
      if let audience = configuration.audience {
        additionalParameters = ["audience": audience]
      }
      let request = OIDAuthorizationRequest(
        configuration: serviceConfiguration,
        clientId: configuration.clientId,
        clientSecret: nil,
        scopes: configuration.scopes,
        redirectURL: configuration.redirectURI,
        responseType: OIDResponseTypeCode,
        additionalParameters: additionalParameters
      )
      self.currentAuthorizationFlow = OIDAuthState.authState(
        byPresenting: request,
        presenting: presenter
      ) { [weak self] authState, error in
        guard let self else { return }
        self.currentAuthorizationFlow = nil
        guard let callback = self.pendingResult else { return }
        self.pendingResult = nil
        guard let authState, authState.isAuthorized, error == nil else {
          let nsError = error as NSError?
          let cancelled = nsError?.code == -3 || nsError?.code == -4
          callback(self.stableError(
            cancelled ? "oauth_authorization_cancelled" : "oauth_authorization_failed",
            cancelled ? "Authorization was cancelled." : "Authorization did not complete."
          ))
          return
        }
        do {
          try self.stateStore.write(authState)
          self.transport.updateAuthState(authState)
          let summary = self.sessionSummary(authState)
          callback(summary)
          self.emitAuthEvent(reason: "signed_in", session: summary)
        } catch {
          self.stateStore.clear()
          callback(self.stableError("storage_failure", "Secure storage failed."))
        }
      }
    }
  }

  private func restoreSession(result: @escaping FlutterResult) {
    do {
      guard let state = try stateStore.read() else {
        let summary = signedOut()
        result(summary)
        emitAuthEvent(reason: "restored", session: summary)
        return
      }
      let summary: [String: Any]
      if state.isAuthorized {
        transport.updateAuthState(state)
        summary = sessionSummary(state)
      } else {
        summary = reauthenticationRequired()
      }
      result(summary)
      emitAuthEvent(reason: "restored", session: summary)
    } catch {
      stateStore.clear()
      result(stableError("storage_failure", "Secure storage failed."))
    }
  }

  private func cancelSignIn(result: @escaping FlutterResult) {
    currentAuthorizationFlow?.cancel()
    currentAuthorizationFlow = nil
    if let callback = pendingResult {
      pendingResult = nil
      callback(stableError("oauth_authorization_cancelled", "Authorization was cancelled."))
    }
    result(nil)
  }

  private func signOut(arguments: [String: Any]?, result: @escaping FlutterResult) {
    let allowedKeys = Set(["bffBaseUrl"])
    guard arguments == nil || Set(arguments!.keys).isSubset(of: allowedKeys) else {
      result(stableError("configuration_invalid", "Sign-out configuration is invalid."))
      return
    }
    let bffBaseURL: URL?
    if let rawBffBaseURL = arguments?["bffBaseUrl"] as? String {
      guard let validated = try? NativeStreamInput.validateBaseURL(rawBffBaseURL) else {
        result(stableError("configuration_invalid", "Sign-out configuration is invalid."))
        return
      }
      bffBaseURL = validated
    } else {
      bffBaseURL = nil
    }
    currentAuthorizationFlow?.cancel()
    currentAuthorizationFlow = nil
    if let callback = pendingResult {
      pendingResult = nil
      callback(stableError("oauth_authorization_cancelled", "Authorization was cancelled."))
    }
    transport.revokeSession(bffBaseURL: bffBaseURL) {
      result(nil)
      self.emitAuthEvent(reason: "signed_out", session: self.signedOut())
    }
  }

  private func finishWithError(_ code: String, _ message: String) {
    guard let callback = pendingResult else { return }
    pendingResult = nil
    callback(stableError(code, message))
  }

  private func sessionSummary(_ state: OIDAuthState) -> [String: Any] {
    var summary: [String: Any] = [
      "status": "authenticated",
      "canRefresh": state.refreshToken != nil,
    ]
    if let expiration = state.lastTokenResponse?.accessTokenExpirationDate {
      summary["expiresAtMillis"] = Int64(expiration.timeIntervalSince1970 * 1000)
    }
    let idToken = state.lastTokenResponse?.idToken ?? state.lastAuthorizationResponse.idToken
    if let accountLabel = accountLabel(from: idToken) {
      summary["accountLabel"] = accountLabel
    }
    return summary
  }

  private func accountLabel(from idToken: String?) -> String? {
    guard let payload = idToken?.split(separator: ".").dropFirst().first else { return nil }
    var base64 = String(payload).replacingOccurrences(of: "-", with: "+")
      .replacingOccurrences(of: "_", with: "/")
    base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
    guard
      let data = Data(base64Encoded: base64),
      let claims = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    for key in ["name", "preferred_username", "email"] {
      if let value = claims[key] as? String, !value.isEmpty { return value }
    }
    return nil
  }

  private func signedOut() -> [String: Any] {
    ["status": "signed_out", "canRefresh": false]
  }

  private func reauthenticationRequired() -> [String: Any] {
    ["status": "reauthentication_required", "canRefresh": false]
  }

  private func capabilities() -> [String: Any] {
    [
      "protocolVersion": Self.protocolVersion,
      "authStateEvents": true,
      "secureSessionStorage": true,
      "nativeAuthorizedTransport": true,
    ]
  }

  private func emitAuthEvent(reason: String, session: [String: Any]) {
    let event: [String: Any] = [
      "protocolVersion": Self.protocolVersion,
      "reason": reason,
      "session": session,
    ]
    DispatchQueue.main.async { [weak self] in
      self?.authEventSink?(event)
    }
  }

  public func onListen(
    withArguments arguments: Any?,
    eventSink events: @escaping FlutterEventSink
  ) -> FlutterError? {
    authEventSink = events
    do {
      if let state = try stateStore.read() {
        let summary = state.isAuthorized
          ? sessionSummary(state)
          : reauthenticationRequired()
        emitAuthEvent(reason: "restored", session: summary)
      } else {
        emitAuthEvent(reason: "restored", session: signedOut())
      }
    } catch {
      stateStore.clear()
      emitAuthEvent(reason: "signed_out", session: signedOut())
    }
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    authEventSink = nil
    return nil
  }

  private func stableError(_ code: String, _ message: String) -> FlutterError {
    FlutterError(code: code, message: message, details: nil)
  }

  private func topViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let root = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
    var current = root
    while let presented = current?.presentedViewController { current = presented }
    return current
  }

  public func application(
    _ application: UIApplication,
    open url: URL,
    options: [UIApplication.OpenURLOptionsKey: Any] = [:]
  ) -> Bool {
    guard let flow = currentAuthorizationFlow else { return false }
    do {
      try flow.resumeExternalUserAgentFlow(with: url)
      currentAuthorizationFlow = nil
      return true
    } catch {
      return false
    }
  }
}

private struct OAuthConfiguration {
  let issuer: URL
  let clientId: String
  let redirectURI: URL
  let scopes: [String]
  let audience: String?

  init(arguments: [String: Any]?) throws {
    let allowedKeys = Set(["issuer", "clientId", "redirectUri", "scopes", "audience"])
    guard
      let arguments,
      Set(arguments.keys).isSubset(of: allowedKeys),
      let issuerString = arguments["issuer"] as? String,
      let issuer = URL(string: issuerString),
      issuer.scheme == "https",
      issuer.host != nil,
      URLComponents(url: issuer, resolvingAgainstBaseURL: false)?.query == nil,
      issuer.fragment == nil,
      let clientId = arguments["clientId"] as? String,
      clientId.range(of: "^[A-Za-z0-9._~-]{3,200}$", options: .regularExpression) != nil,
      let redirectString = arguments["redirectUri"] as? String,
      let redirectURI = URL(string: redirectString),
      let redirectComponents = URLComponents(url: redirectURI, resolvingAgainstBaseURL: false),
      Self.allowedRedirect(redirectString, redirectComponents),
      let scopes = arguments["scopes"] as? [String],
      scopes.contains("openid"),
      scopes.allSatisfy({
        $0.range(of: "^[A-Za-z0-9:._~-]+$", options: .regularExpression) != nil
      })
    else { throw OAuthConfigurationError.invalid }
    self.issuer = issuer
    self.clientId = clientId
    self.redirectURI = redirectURI
    self.scopes = scopes
    self.audience = (arguments["audience"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private static func allowedRedirect(
    _ rawValue: String,
    _ components: URLComponents
  ) -> Bool {
    if rawValue == "ai.coreline.mobileagent:/oauth/callback" { return true }
    return components.scheme == "https"
      && components.host != nil
      && components.user == nil
      && components.password == nil
      && components.query == nil
      && components.fragment == nil
  }
}

private enum OAuthConfigurationError: Error { case invalid }

final class KeychainAuthStateStore {
  private let service = "ai.coreline.mobileagent.oauth"
  private let account = "oidc-auth-state-v1"
  private let lock = NSRecursiveLock()

  func write(_ state: OIDAuthState) throws {
    lock.lock()
    defer { lock.unlock() }
    let data = try NSKeyedArchiver.archivedData(withRootObject: state, requiringSecureCoding: true)
    clear()
    let status = SecItemAdd([
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: service,
      kSecAttrAccount: account,
      kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
      kSecValueData: data,
    ] as CFDictionary, nil)
    guard status == errSecSuccess else { throw KeychainError.status(status) }
  }

  func read() throws -> OIDAuthState? {
    lock.lock()
    defer { lock.unlock() }
    var item: CFTypeRef?
    let status = SecItemCopyMatching([
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: service,
      kSecAttrAccount: account,
      kSecReturnData: true,
      kSecMatchLimit: kSecMatchLimitOne,
    ] as CFDictionary, &item)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let data = item as? Data else {
      throw KeychainError.status(status)
    }
    return try NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data)
  }

  func clear() {
    lock.lock()
    defer { lock.unlock() }
    SecItemDelete([
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: service,
      kSecAttrAccount: account,
    ] as CFDictionary)
  }
}

private enum KeychainError: Error { case status(OSStatus) }
