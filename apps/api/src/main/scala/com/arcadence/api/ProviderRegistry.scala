package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.crypto.{CredentialEncryptor, OAuthStateSigner}
import com.arcadence.core.email.{
  Credentials,
  EmailProvider,
  ProviderCapabilities
}
import com.arcadence.core.email.gmail.{
  GmailCredentials,
  GmailEndpoints,
  GmailProvider
}
import org.http4s.client.Client

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac

import scala.concurrent.duration.*

/** Adapter registry for the API process: holds configured provider adapters and
  * centralizes credential (de)cryption + OAuth state signing.
  *
  * Google OAuth is optional — when the console credentials are absent, the
  * registry exposes no Gmail adapter and the connect endpoints answer "not
  * configured" honestly (Phase 2.2).
  */
final class ProviderRegistry(
    val encryptor: CredentialEncryptor,
    val stateSigner: OAuthStateSigner,
    val appOrigin: String,
    val redirectUri: String
):

  private val providers =
    scala.collection.mutable.Map.empty[EmailProvider.Id, EmailProvider]

  def gmail: Option[GmailProvider] =
    providers.get(EmailProvider.Gmail).collect { case g: GmailProvider => g }

  def registerGmail(provider: GmailProvider): Unit =
    providers(EmailProvider.Gmail) = provider

  def adapter(providerId: String): Option[EmailProvider] =
    providers.get(providerId)

  /** True when a provider's connect flow can run right now. */
  def configured(providerId: String): Boolean =
    providerId match
      case EmailProvider.Gmail => gmail.isDefined
      case _                   => false // microsoft (2.3), smtp_imap (2.4)

  /** Serializes + encrypts Gmail credentials for at-rest storage. */
  def encryptCredentials(creds: GmailCredentials): Array[Byte] =
    encryptor.encryptString(creds.toJson.noSpaces)

  def decryptCredentials(blob: Array[Byte]): Either[String, GmailCredentials] =
    encryptor
      .decryptString(blob)
      .left
      .map {
        case _: com.arcadence.core.crypto.CredentialEncryptor.DecryptFailed =>
          "decryption failed"
      }
      .flatMap(GmailCredentials.fromJsonString)

  /** Credential loader for the adapter contract, workspace-scoped. */
  def credentialLoader(
      repos: EmailAccountRepos,
      workspaceId: UUID,
      accountId: UUID
  ): IO[Option[Credentials]] =
    repos.loadCredentials(workspaceId, accountId).flatMap {
      case None      => IO.pure(None)
      case Some(row) =>
        decryptCredentials(row.encryptedBlob) match
          case Right(creds) => IO.pure(Some(creds: Credentials))
          case Left(_)      =>
            // Wrong key or corrupt blob: surface as "no credentials" — the
            // account shows reconnect_required rather than failing opaquely.
            IO.pure(None)
    }

  /** Store callback for the adapter contract. */
  def credentialStorer(
      repos: EmailAccountRepos,
      accountId: UUID,
      expiresAt: OffsetDateTime
  ): Credentials => IO[Unit] = creds =>
    IO(encryptCredentials(creds.asInstanceOf[GmailCredentials])).flatMap(blob =>
      repos.storeCredentials(
        accountId,
        "oauth2",
        blob,
        encryptor.keyVersion,
        Some(expiresAt)
      )
    )

object ProviderRegistry:

  /** Domain-separated HMAC key for OAuth state signing, derived from the
    * credential-encryption key (one master secret, purpose-bound keys).
    */
  def deriveStateSecret(credentialKey: Array[Byte]): Array[Byte] =
    MessageDigest
      .getInstance("SHA-256")
      .digest(
        credentialKey ++ "arcadence:oauth-state:v1".getBytes(
          java.nio.charset.StandardCharsets.UTF_8
        )
      )

  /** Builds the registry; registers the Gmail adapter when configured. */
  def build(
      config: com.arcadence.core.config.ApiConfig,
      httpClient: Client[IO]
  ): IO[ProviderRegistry] =
    IO {
      CredentialEncryptor.decodeKey(config.credentialEncryptionKey)
    }.flatMap {
      case Right(keyBytes) =>
        build0(config, httpClient, keyBytes)
      case Left(error) =>
        IO.raiseError(new IllegalStateException(error))
    }

  private def build0(
      config: com.arcadence.core.config.ApiConfig,
      httpClient: Client[IO],
      keyBytes: Array[Byte]
  ): IO[ProviderRegistry] = IO {
    val encryptor = new CredentialEncryptor(keyBytes)
    val registry = new ProviderRegistry(
      encryptor = encryptor,
      stateSigner = new OAuthStateSigner(deriveStateSecret(keyBytes)),
      appOrigin = "http://localhost:3000",
      redirectUri = config.googleOAuthRedirectUri.getOrElse(
        s"http://localhost:${config.port}/api/v1/email-connections/oauth/google/callback"
      )
    )
    (config.googleOAuthClientId, config.googleOAuthClientSecret).tupled
      .foreach { case (clientId, clientSecret) =>
        registry.registerGmail(
          new GmailProvider(
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = registry.redirectUri,
            endpoints = GmailEndpoints.Production,
            client = httpClient
          )
        )
      }
    registry
  }

  /** PKCE S256: base64url(SHA256(verifier)), unpadded. */
  def pkceChallenge(verifier: String): String =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    Base64.getUrlEncoder.withoutPadding.encodeToString(digest)

  /** URL-safe random PKCE verifier (48 bytes ≈ 64 chars). */
  def randomVerifier: String = randomUrlSafeToken(48)

  /** URL-safe random token for nonces. */
  def randomUrlSafeToken(byteLength: Int): String =
    val bytes = new Array[Byte](byteLength)
    new java.security.SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
