package com.arcadence.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

import java.security.SecureRandom
import java.util.Base64

/** AES-256-GCM encryption for provider credentials at rest (ADR-0020).
  *
  * Key management: a single 32-byte key is provisioned out-of-band
  * (EMAIL_ENCRYPTION_KEY, base64-encoded); `keyVersion` tags ciphertexts so a
  * future rotation can re-encrypt lazily. Nonces are random 12 bytes per
  * operation (NIST SP 800-38D bounds: ~2^32 encryptions per key remain safe).
  * Ciphertext layout: nonce (12) || ciphertext+tag (16-byte tag appended by
  * JCE). Authentication failures (wrong key, tampered bytes) are values, not
  * exceptions.
  */
final class CredentialEncryptor(key: Array[Byte], val keyVersion: Int = 1):
  import CredentialEncryptor.*

  private val random = new SecureRandom()

  require(key.length == KeyBytes, s"encryption key must be $KeyBytes bytes")

  def encrypt(plaintext: Array[Byte]): Array[Byte] =
    val nonce = new Array[Byte](NonceBytes)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance(Transformation)
    cipher.init(
      Cipher.ENCRYPT_MODE,
      new SecretKeySpec(key, "AES"),
      new GCMParameterSpec(TagBits, nonce)
    )
    nonce ++ cipher.doFinal(plaintext)

  def decrypt(blob: Array[Byte]): Either[DecryptFailed, Array[Byte]] =
    if blob.length <= NonceBytes then Left(DecryptFailed.Malformed)
    else
      try
        val nonce = blob.take(NonceBytes)
        val body = blob.drop(NonceBytes)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TagBits, nonce)
        )
        Right(cipher.doFinal(body))
      catch case _: Exception => Left(DecryptFailed.Rejected)

  def encryptString(plaintext: String): Array[Byte] =
    encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  def decryptString(blob: Array[Byte]): Either[DecryptFailed, String] =
    decrypt(blob).map(bytes =>
      new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    )

object CredentialEncryptor:

  val KeyBytes: Int = 32
  val NonceBytes: Int = 12
  val TagBits: Int = 128
  val Transformation = "AES/GCM/NoPadding"

  enum DecryptFailed:
    /** Blob does not even have a nonce prefix. */
    case Malformed

    /** Wrong key or tampered ciphertext (GCM tag mismatch). Indistinguishable
      * by design.
      */
    case Rejected

  /** Decodes a base64 EMAIL_ENCRYPTION_KEY value; fails as a value so config
    * parsing can aggregate errors.
    */
  def decodeKey(base64Key: String): Either[String, Array[Byte]] =
    try
      val key = Base64.getDecoder.decode(base64Key.trim)
      if key.length == KeyBytes then Right(key)
      else
        Left(
          s"EMAIL_ENCRYPTION_KEY must decode to $KeyBytes bytes (got ${key.length})"
        )
    catch
      case _: IllegalArgumentException =>
        Left("EMAIL_ENCRYPTION_KEY is not valid base64")

  def fromBase64(
      base64Key: String,
      keyVersion: Int = 1
  ): Either[String, CredentialEncryptor] =
    decodeKey(base64Key).map(new CredentialEncryptor(_, keyVersion))
