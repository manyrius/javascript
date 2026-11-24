import Foundation
import Security
import CryptoKit
import CommonCrypto

enum DeviceIdentity {
    private static let tag = Data("it.benazzi.smarthub.ios.device-key".utf8)

    static func privateKey() throws -> SecKey {
        let query: [CFString: Any] = [kSecClass: kSecClassKey, kSecAttrApplicationTag: tag,
            kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecReturnRef: true]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess { return item as! SecKey }
        guard status == errSecItemNotFound else { throw AppFailure("Portachiavi non disponibile.") }
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits: 2048,
            kSecPrivateKeyAttrs: [kSecAttrIsPermanent: true, kSecAttrApplicationTag: tag,
                                 kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw AppFailure("Impossibile creare la chiave del dispositivo.")
        }
        return key
    }

    static func publicParameters() throws -> (String, String) {
        guard let key = SecKeyCopyPublicKey(try privateKey()),
              let raw = SecKeyCopyExternalRepresentation(key, nil) as Data? else {
            throw AppFailure("Chiave pubblica non disponibile.")
        }
        return try decodePublicDER(raw)
    }

    // Apple's RSA export is PKCS#1 DER. Aruba expects unsigned modulus and exponent.
    static func decodePublicDER(_ raw: Data) throws -> (String, String) {
        let bytes = Array(raw)
        var offset = 0
        func read(_ tag: UInt8) throws -> [UInt8] {
            guard offset + 2 <= bytes.count, bytes[offset] == tag else { throw AppFailure("Chiave DER non valida.") }
            offset += 1
            var length = Int(bytes[offset]); offset += 1
            if length & 128 != 0 {
                let count = length & 127
                guard count > 0, count <= 4, offset + count <= bytes.count else { throw AppFailure("Lunghezza DER non valida.") }
                length = 0
                for _ in 0..<count { length = (length << 8) | Int(bytes[offset]); offset += 1 }
            }
            guard length <= bytes.count - offset else { throw AppFailure("Chiave DER troncata.") }
            let result = Array(bytes[offset..<offset + length])
            if tag != 0x30 { offset += length }
            return result
        }
        _ = try read(0x30)
        func integer() throws -> String {
            var value = try read(0x02)
            while value.count > 1 && value.first == 0 { value.removeFirst() }
            return Data(value).base64EncodedString()
        }
        return try (integer(), integer())
    }

    static func decrypt(_ wrapped: Data) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let plain = SecKeyCreateDecryptedData(try privateKey(), .rsaEncryptionOAEPSHA256,
                                                   wrapped as CFData, &error) as Data? else {
            throw AppFailure("La credenziale non corrisponde alla chiave di questo dispositivo.")
        }
        return plain
    }

    static func open(_ envelope: Envelope) throws -> Data {
        let material = try decrypt(envelope.wrapped)
        guard material.count == 64, envelope.iv.count == 16, envelope.tag.count == 32,
              !envelope.ct.isEmpty, envelope.ct.count % 16 == 0 else { throw AppFailure("Credenziale incompleta.") }
        guard HMAC<SHA256>.isValidAuthenticationCode(envelope.tag, authenticating: envelope.iv + envelope.ct,
                                                    using: SymmetricKey(data: material.suffix(32))) else {
            throw AppFailure("Integrità della credenziale non valida.")
        }
        let key = Data(material.prefix(32))
        var output = Data(count: envelope.ct.count + 16)
        let capacity = output.count
        var size = 0
        let status = output.withUnsafeMutableBytes { out in
            key.withUnsafeBytes { keyBytes in
                envelope.iv.withUnsafeBytes { iv in
                    envelope.ct.withUnsafeBytes { input in
                        CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                                keyBytes.baseAddress, 32, iv.baseAddress, input.baseAddress, envelope.ct.count,
                                out.baseAddress, capacity, &size)
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw AppFailure("Decifratura VPN fallita.") }
        output.count = size
        return output
    }

    static func savedID() throws -> String? {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword,
            kSecAttrService: "SIB", kSecAttrAccount: "deviceID", kSecReturnData: true]
        var item: CFTypeRef?
        let result = SecItemCopyMatching(query as CFDictionary, &item)
        if result == errSecItemNotFound { return nil }
        guard result == errSecSuccess, let data = item as? Data else { throw AppFailure("Portachiavi non disponibile.") }
        return String(data: data, encoding: .utf8)
    }

    static func saveID(_ id: String) throws {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword,
            kSecAttrService: "SIB", kSecAttrAccount: "deviceID"]
        let value = Data(id.utf8)
        let result = SecItemUpdate(query as CFDictionary, [kSecValueData: value] as CFDictionary)
        if result == errSecSuccess { return }
        guard result == errSecItemNotFound else { throw AppFailure("Salvataggio dispositivo fallito.") }
        var insert = query
        insert[kSecValueData] = value
        insert[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw AppFailure("Salvataggio dispositivo fallito.") }
    }
}

struct Envelope: Decodable {
    let wrapped: Data
    let iv: Data
    let ct: Data
    let tag: Data
}
