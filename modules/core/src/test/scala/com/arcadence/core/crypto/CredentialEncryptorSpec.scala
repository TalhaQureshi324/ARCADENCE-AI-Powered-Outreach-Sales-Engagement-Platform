package com.arcadence.core.crypto

import munit.FunSuite

import java.util.Base64

/** AES-256-GCM credential encryption: round-trip, tamper rejection, wrong-key
  * rejection, nonce uniqueness and key validation (ADR-0020).
  */
final class CredentialEncryptorSpec extends FunSuite:

  private def randomKey(): Array[Byte] =
    val key = new Array[Byte](CredentialEncryptor.KeyBytes)
    new java.security.SecureRandom().nextBytes(key)
    key

  private def base64Key(): String =
    Base64.getEncoder.encodeToString(randomKey())

  test("round-trips plaintext through encrypt/decrypt") {
    val enc =
      CredentialEncryptor.fromBase64(base64Key()).fold(fail(_), identity)
    val secret = """{"refresh_token":"1//secret","access_token":"ya29.x"}"""
    val blob = enc.encryptString(secret)
    assert(blob.length > secret.length) // nonce + tag overhead
    assertEquals(enc.decryptString(blob), Right(secret))
  }

  test("produces fresh nonces — same plaintext never encrypts identically") {
    val enc =
      CredentialEncryptor.fromBase64(base64Key()).fold(fail(_), identity)
    val a = enc.encryptString("same")
    val b = enc.encryptString("same")
    assertNotEquals(a.toList, b.toList)
  }

  test("rejects tampered ciphertext as Rejected (never throws)") {
    val enc =
      CredentialEncryptor.fromBase64(base64Key()).fold(fail(_), identity)
    val blob = enc.encryptString("credential material")
    val tampered = blob.clone()
    tampered(tampered.length - 1) =
      (tampered(tampered.length - 1) ^ 0x01).toByte
    assertEquals(
      enc.decrypt(tampered),
      Left(CredentialEncryptor.DecryptFailed.Rejected)
    )
  }

  test("rejects decryption under a different key") {
    val encA =
      CredentialEncryptor.fromBase64(base64Key()).fold(fail(_), identity)
    val encB =
      CredentialEncryptor.fromBase64(base64Key()).fold(fail(_), identity)
    val blob = encA.encryptString("secret")
    assertEquals(
      encB.decrypt(blob),
      Left(CredentialEncryptor.DecryptFailed.Rejected)
    )
  }

  test("rejects blobs shorter than the nonce prefix as Malformed") {
    val enc =
      CredentialEncryptor.fromBase64(base64Key()).fold(fail(_), identity)
    assertEquals(
      enc.decrypt(Array[Byte](1, 2, 3)),
      Left(CredentialEncryptor.DecryptFailed.Malformed)
    )
  }

  test("decodeKey enforces base64 and 32-byte length") {
    assert(CredentialEncryptor.decodeKey(base64Key()).isRight)
    assert(CredentialEncryptor.decodeKey("not base64 !!!").isLeft)
    val short = Base64.getEncoder.encodeToString(new Array[Byte](16))
    assert(CredentialEncryptor.decodeKey(short).isLeft)
  }

  test("fromBase64 rejects wrong-length keys") {
    val short = Base64.getEncoder.encodeToString(new Array[Byte](31))
    assert(CredentialEncryptor.fromBase64(short).isLeft)
  }
