import CryptoKit
import Foundation
import Security

/// Stores only the MobileAgent local conversation snapshot in an encrypted, non-exported app file.
final class SecureConversationStore {
  private static let schemaVersion = 1
  private static let maxConversations = 20
  private static let maxMessagesPerConversation = 64
  private static let maxMessageCharacters = 32 * 1024
  private static let maxSnapshotCharacters = 512 * 1024
  private static let maxSnapshotBytes = 1024 * 1024
  private static let maxEncryptedBytes = maxSnapshotBytes + 1024

  private let service = "ai.coreline.mobileagent.conversations"
  private let account = "vault-key-v1"
  private let lock = NSLock()
  private let fileManager: FileManager
  private let fileURL: URL

  init(fileManager: FileManager = .default) {
    self.fileManager = fileManager
    let appSupport = fileManager.urls(
      for: .applicationSupportDirectory,
      in: .userDomainMask
    ).first ?? URL(fileURLWithPath: NSHomeDirectory())
      .appendingPathComponent("Library/Application Support", isDirectory: true)
    let directory = appSupport
      .appendingPathComponent("MobileAgent", isDirectory: true)
    self.fileURL = directory.appendingPathComponent("conversations-v1.bin")
  }

  func read() throws -> String? {
    lock.lock()
    defer { lock.unlock() }
    guard fileManager.fileExists(atPath: fileURL.path) else { return nil }
    let encrypted = try Data(contentsOf: fileURL, options: .mappedIfSafe)
    guard !encrypted.isEmpty, encrypted.count <= Self.maxEncryptedBytes else {
      throw ConversationVaultError.invalid
    }
    let sealed = try AES.GCM.SealedBox(combined: encrypted)
    let plaintext = try AES.GCM.open(sealed, using: try encryptionKey())
    guard plaintext.count <= Self.maxSnapshotBytes,
          let snapshot = String(data: plaintext, encoding: .utf8)
    else { throw ConversationVaultError.invalid }
    try Self.validate(snapshot: snapshot)
    return snapshot
  }

  func write(_ snapshot: String) throws {
    lock.lock()
    defer { lock.unlock() }
    try Self.validate(snapshot: snapshot)
    guard let plaintext = snapshot.data(using: .utf8), plaintext.count <= Self.maxSnapshotBytes else {
      throw ConversationVaultError.invalid
    }
    let encrypted = try AES.GCM.seal(plaintext, using: try encryptionKey()).combined
    guard let encrypted, encrypted.count <= Self.maxEncryptedBytes else {
      throw ConversationVaultError.invalid
    }
    let directory = fileURL.deletingLastPathComponent()
    try fileManager.createDirectory(
      at: directory,
      withIntermediateDirectories: true,
      attributes: [.protectionKey: FileProtectionType.complete.rawValue]
    )
    try Self.excludeFromBackup(directory)
    try encrypted.write(to: fileURL, options: .atomic)
    try fileManager.setAttributes(
      [.protectionKey: FileProtectionType.complete.rawValue],
      ofItemAtPath: fileURL.path
    )
    try Self.excludeFromBackup(fileURL)
  }

  func clear() throws {
    lock.lock()
    defer { lock.unlock() }
    guard fileManager.fileExists(atPath: fileURL.path) else { return }
    try fileManager.removeItem(at: fileURL)
  }

  private func encryptionKey() throws -> SymmetricKey {
    if let existing = try keyData() { return SymmetricKey(data: existing) }
    var keyMaterial = Data(count: 32)
    let status = keyMaterial.withUnsafeMutableBytes {
      SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
    }
    guard status == errSecSuccess else { throw ConversationVaultError.keychain(status) }
    let addStatus = SecItemAdd([
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: service,
      kSecAttrAccount: account,
      kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
      kSecValueData: keyMaterial,
    ] as CFDictionary, nil)
    if addStatus == errSecDuplicateItem, let existing = try keyData() {
      return SymmetricKey(data: existing)
    }
    guard addStatus == errSecSuccess else { throw ConversationVaultError.keychain(addStatus) }
    return SymmetricKey(data: keyMaterial)
  }

  private func keyData() throws -> Data? {
    var item: CFTypeRef?
    let status = SecItemCopyMatching([
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: service,
      kSecAttrAccount: account,
      kSecReturnData: true,
      kSecMatchLimit: kSecMatchLimitOne,
    ] as CFDictionary, &item)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let value = item as? Data, value.count == 32 else {
      throw ConversationVaultError.keychain(status)
    }
    return value
  }

  private static func validate(snapshot: String) throws {
    guard let data = snapshot.data(using: .utf8),
          !data.isEmpty,
          data.count <= maxSnapshotBytes,
          let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { throw ConversationVaultError.invalid }
    try requireExactKeys(root, ["schemaVersion", "records"])
    guard int(root["schemaVersion"]) == schemaVersion,
          let records = root["records"] as? [[String: Any]],
          records.count <= maxConversations
    else { throw ConversationVaultError.invalid }
    var ids = Set<String>()
    var totalCharacters = 0
    for record in records {
      try requireExactKeys(record, ["id", "provider", "model", "updatedAtMillis", "messages"])
      guard let id = record["id"] as? String,
            matches(id, pattern: "^[A-Za-z0-9_-]{8,80}$"),
            ids.insert(id).inserted,
            let provider = record["provider"] as? String,
            ["openai", "anthropic", "xai"].contains(provider),
            let model = record["model"] as? String,
            matches(model, pattern: "^[A-Za-z0-9._:/-]{1,120}$"),
            int(record["updatedAtMillis"]) ?? 0 > 0,
            let messages = record["messages"] as? [[String: Any]],
            !messages.isEmpty,
            messages.count <= maxMessagesPerConversation
      else { throw ConversationVaultError.invalid }
      for (index, message) in messages.enumerated() {
        try requireExactKeys(message, ["role", "content", "createdAtMillis"])
        guard let role = message["role"] as? String,
              role == "user" || role == "assistant",
              index != 0 || role == "user",
              let content = message["content"] as? String,
              !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              content.count <= maxMessageCharacters,
              int(message["createdAtMillis"]) ?? 0 > 0
        else { throw ConversationVaultError.invalid }
        totalCharacters += content.count
        guard totalCharacters <= maxSnapshotCharacters else { throw ConversationVaultError.invalid }
      }
    }
  }

  private static func requireExactKeys(_ value: [String: Any], _ expected: Set<String>) throws {
    guard Set(value.keys) == expected else { throw ConversationVaultError.invalid }
  }

  private static func int(_ value: Any?) -> Int? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else {
      return nil
    }
    return number.intValue
  }

  private static func matches(_ value: String, pattern: String) -> Bool {
    value.range(of: pattern, options: .regularExpression) != nil
  }

  private static func excludeFromBackup(_ source: URL) throws {
    var url = source
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try url.setResourceValues(values)
  }
}

enum ConversationVaultError: Error {
  case invalid
  case keychain(OSStatus)
}
