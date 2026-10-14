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
import com.arcadence.core.email.microsoft.{
  GraphEndpoints,
  GraphProvider,
  MicrosoftCredentials
}
import io.circe.Json
import org.http4s.client.Client

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Adapter registry for the API process: holds configured provider adapters and
  * centralizes credential (de)cryption + OAuth state signing.
  *
  * Provider OAuth credentials are optional — when a provider's console
  * credentials are absent, the registry exposes no adapter for it and the
  * connect endpoints answer "not configured" honestly (Phases 2.2/2.3).
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

  def microsoft: Option[GraphProvider] =
    providers.get(EmailProvider.Microsoft).collect { case m: GraphProvider =>
      m
    }

  def registerGmail(provider: GmailProvider): Unit =
    providers(EmailProvider.Gmail) = provider

  def registerMicrosoft(provider: GraphProvider): Unit =
    providers(EmailProvider.Microsoft) = provider

  def adapter(providerId: String): Option[EmailProvider] =
    providers.get(providerId)

  /** True when a provider's connect flow can run right now. */
  def configured(providerId: String): Boolean =
    providerId match
      case EmailProvider.Gmail     => gmail.isDefined
      case EmailProvider.Microsoft => microsoft.isDefined
      case _                       => false // smtp_imap (2.4)

  /** Serializes + encrypts provider credentials for at-rest storage. */
  def encryptCredentials(
      creds: GmailCredentials | MicrosoftCredentials
  ): Array[Byte] =
    val json = creds match
      case g: GmailCredentials     => g.toJson
      case m: MicrosoftCredentials => m.toJson
    encryptor.encryptString(json.noSpaces)

  /** Decrypts stored credentials for the given provider. */
  def decryptCredentials(
      blob: Array[Byte],
      providerId: String
  ): Either[String, GmailCredentials | MicrosoftCredentials] =
    encryptor
      .decryptString(blob)
      .left
      .map {
        case _: com.arcadence.core.crypto.CredentialEncryptor.DecryptFailed =>
          "decryption failed"
      }
      .flatMap { text =>
        providerId match
          case EmailProvider.Gmail     => GmailCredentials.fromJsonString(text)
          case EmailProvider.Microsoft =>
            MicrosoftCredentials.fromJsonString(text)
          case other => Left(s"no credential decoder for provider $other")
      }

  def decryptGmail(blob: Array[Byte]): Either[String, GmailCredentials] =
    decryptCredentials(blob, EmailProvider.Gmail).map {
      case g: GmailCredentials => g
    }

  /** Credential loader for the adapter contract, workspace-scoped and
    * provider-aware.
    */
  def credentialLoader(
      repos: EmailAccountRepos,
      workspaceId: UUID,
      accountId: UUID,
      providerId: String
  ): IO[Option[Credentials]] =
    repos.loadCredentials(workspaceId, accountId).flatMap {
      case None      => IO.pure(None)
      case Some(row) =>
        decryptCredentials(row.encryptedBlob, providerId) match
          case Right(creds) => IO.pure(Some(creds: Credentials))
          case Left(_)      =>
            // Wrong key or corrupt blob: surface as "no credentials" — the
            // account shows reconnect_required rather than failing opaquely.
            IO.pure(None)
    }

  /** Store callback for the adapter contract: derives expiry from the
    * credentials themselves and persists the encrypted blob.
    */
  def credentialStorer(
      repos: EmailAccountRepos,
      accountId: UUID
  ): Credentials => IO[Unit] = creds =>
    val (json, expiresAt) = creds match
      case g: GmailCredentials     => (g.toJson, g.accessTokenExpiresAt)
      case m: MicrosoftCredentials => (m.toJson, m.accessTokenExpiresAt)
      case _                       => (Json.Null, OffsetDateTime.now)
    if json.isNull then
      IO.raiseError(new IllegalStateException("unknown credential type"))
    else
      IO(encryptor.encryptString(json.noSpaces)).flatMap { blob =>
        repos.storeCredentials(
          accountId,
          "oauth2",
          blob,
          encryptor.keyVersion,
          Some(expiresAt)
        )
      }

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

  /** Builds the registry; registers the Gmail (2.2) and Microsoft (2.3)
    * adapters when their console credentials exist.
    */
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
    (config.microsoftOAuthClientId, config.microsoftOAuthClientSecret).tupled
      .foreach { case (clientId, clientSecret) =>
        val microsoftRedirect = config.microsoftOAuthRedirectUri.getOrElse(
          registry.redirectUri.replace("/google/", "/microsoft/")
        )
        registry.registerMicrosoft(
          new GraphProvider(
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = microsoftRedirect,
            endpoints = GraphEndpoints.Production(
              config.microsoftOAuthTenant.getOrElse(
                GraphEndpoints.DefaultTenant
              )
            ),
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
