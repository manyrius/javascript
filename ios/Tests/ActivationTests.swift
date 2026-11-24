import XCTest
import Security
@testable import SIBSmartHub

final class ActivationTests: XCTestCase {
    func testAcceptsExistingAndroidQR() throws {
        let qr = try ActivationQR.parse(#"{"u":"https://artsdomus.com/alborgo/stradioltre.ashx","t":"TEST_TOKEN"}"#)
        XCTAssertEqual(qr.t, "TEST_TOKEN")
    }
    func testRejectsUntrustedQR() {
        for address in ["http://www.artsdomus.com/alborgo/stradioltre.ashx",
                        "https://example.org/alborgo/stradioltre.ashx",
                        "https://www.artsdomus.com/alborgo/stradioltre.ashx?key=1"] {
            XCTAssertThrowsError(try ActivationQR.parse("{\"u\":\"\(address)\",\"t\":\"test\"}"))
        }
    }
    func testPortalBoundary() {
        XCTAssertTrue(Settings.acceptsPortal(Settings.portal))
        XCTAssertTrue(Settings.acceptsPortal(URL(string: "http://192.168.1.34/ilborghetto/Aziende")!))
        XCTAssertFalse(Settings.acceptsPortal(URL(string: "http://192.168.1.34/ilborghetto/../admin")!))
        XCTAssertFalse(Settings.acceptsPortal(URL(string: "http://192.168.1.95/ilborghetto/")!))
    }
    func testUnsignedRSAModulus() throws {
        let raw = Data([0x30, 0x0a, 0x02, 0x03, 0x00, 0x80, 0x01, 0x02, 0x03, 0x01, 0x00, 0x01])
        let (modulus, exponent) = try DeviceIdentity.decodePublicDER(raw)
        XCTAssertEqual(Data(base64Encoded: modulus), Data([0x80, 0x01]))
        XCTAssertEqual(Data(base64Encoded: exponent), Data([1, 0, 1]))
        XCTAssertThrowsError(try DeviceIdentity.decodePublicDER(Data([0x30, 0x82])))
    }
    func testRSAOAEPMatchesServerAlgorithm() throws {
        let privateKey = try DeviceIdentity.privateKey()
        let publicKey = try XCTUnwrap(SecKeyCopyPublicKey(privateKey))
        let input = Data((0..<64).map(UInt8.init))
        let wrapped = try XCTUnwrap(SecKeyCreateEncryptedData(publicKey, .rsaEncryptionOAEPSHA256, input as CFData, nil) as Data?)
        XCTAssertEqual(try DeviceIdentity.decrypt(wrapped), input)
    }

    func testEnvelopeAgainstIndependentAESAndHMACVector() throws {
        let key = try XCTUnwrap(SecKeyCopyPublicKey(DeviceIdentity.privateKey()))
        let material = Data((0..<64).map(UInt8.init))
        let wrapped = try XCTUnwrap(SecKeyCreateEncryptedData(key, .rsaEncryptionOAEPSHA256, material as CFData, nil) as Data?)
        // Vector generated with Python cryptography, separately from CommonCrypto.
        let iv = Data(base64Encoded: "AAECAwQFBgcICQoLDA0ODw==")!
        let ct = Data(base64Encoded: "lAVaPGN9zAfPrgZ2QdKPMfpkEusOF/g79H/ZecbqwHnm6AIIg/OpiWBq+r37H+z/")!
        let tag = Data(base64Encoded: "qT9YsO09Jayf/G7oThXeReWYdPxBk/QKWVmkoaFNKGI=")!
        let envelope = Envelope(wrapped: wrapped, iv: iv, ct: ct, tag: tag)
        XCTAssertEqual(try DeviceIdentity.open(envelope), Data("client\nremote test.example 1194\n".utf8))
        var altered = ct
        altered[0] ^= 1
        XCTAssertThrowsError(try DeviceIdentity.open(Envelope(wrapped: wrapped, iv: iv, ct: altered, tag: tag)))
    }
}
