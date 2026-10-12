package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*

import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import java.util.UUID
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.{ECDSAKeyProvider, RSAKeyProvider}
import com.auth0.jwk.JwkProvider
import org.http4s.Uri
import org.typelevel.log4cats.LoggerFactory

/** Result of verifying a Supabase access token. */
final case class AuthenticatedUser(id: UUID, email: String)

final case class AuthError(code: String, message: String)

object AuthError:
  val Invalid: AuthError =
    AuthError("invalid_token", "The access token is invalid.")
  val Expired: AuthError =
    AuthError("expired_token", "The access token has expired.")

/** Verifies access tokens and resolves the caller identity. Implementations
  * must validate signature (via the project's signing keys), issuer, audience
  * and expiry — never trust decoded claims alone.
  */
trait Authenticator:
  def verify(token: String): IO[Either[AuthError, AuthenticatedUser]]

/** Production authenticator: verifies Supabase Auth JWTs against the project's
  * published JWKS. Supports ES256 (current asymmetric signing keys) and RS256
  * (legacy projects); key material is fetched per `kid` through a cached,
  * rate-limited JwkProvider.
  */
final class SupabaseJwtAuthenticator(
    issuer: String,
    audience: String,
    jwksUri: Uri,
    provider: JwkProvider
) extends Authenticator:

  private def algorithmFor(kid: String): Algorithm =
    val jwk =
      provider.get(kid) // throws SigningKeyNotFoundException on unknown kid
    jwk.getPublicKey match
      case ec: ECPublicKey =>
        val keyProvider = new ECDSAKeyProvider:
          override def getPublicKeyById(keyId: String): ECPublicKey =
            provider.get(keyId).getPublicKey.asInstanceOf[ECPublicKey]
          override def getPrivateKey: java.security.interfaces.ECPrivateKey =
            null
          override def getPrivateKeyId: String = null
        Algorithm.ECDSA256(keyProvider)
      case rsa: RSAPublicKey =>
        val keyProvider = new RSAKeyProvider:
          override def getPublicKeyById(keyId: String): RSAPublicKey =
            provider.get(keyId).getPublicKey.asInstanceOf[RSAPublicKey]
          override def getPrivateKey: java.security.interfaces.RSAPrivateKey =
            null
          override def getPrivateKeyId: String = null
        Algorithm.RSA256(keyProvider)
      case other =>
        throw new IllegalArgumentException(
          s"Unsupported JWKS key type: ${other.getAlgorithm}"
        )

  def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
    // Blocking: JWKS fetch + crypto run off the compute pool.
    IO.blocking {
      val decoded = JWT.decode(token) // throws on malformed tokens
      val kid = Option(decoded.getKeyId).getOrElse {
        throw new IllegalArgumentException("token has no kid header")
      }
      val jwt = JWT
        .require(algorithmFor(kid))
        .withIssuer(issuer)
        .withAudience(audience)
        .build()
        .verify(token) // throws on bad signature / expiry / claims
      val email = Option(jwt.getClaim("email").asString()).getOrElse("")
      Right(AuthenticatedUser(UUID.fromString(jwt.getSubject), email))
    }.attempt
      .map { outcome =>
        outcome.left.foreach { e =>
          System.err.println(
            s"jwt verify failed: ${e.getClass.getName}: ${e.getMessage}"
          )
        }
        outcome match {
          case Right(result)                                           => result
          case Left(_: com.auth0.jwt.exceptions.TokenExpiredException) =>
            Left(AuthError.Expired)
          case Left(_: com.auth0.jwt.exceptions.JWTVerificationException) =>
            Left(AuthError.Invalid)
          case Left(_: IllegalArgumentException) => Left(AuthError.Invalid)
          case Left(_: com.auth0.jwk.SigningKeyNotFoundException) =>
            Left(AuthError.Invalid)
          case Left(_) =>
            Left(
              AuthError(
                "verification_unavailable",
                "Token verification is temporarily unavailable."
              )
            )
        }
      }

object SupabaseJwtAuthenticator:
  def apply(
      issuer: String,
      audience: String,
      jwksUri: Uri
  ): SupabaseJwtAuthenticator =
    val url = new java.net.URL(jwksUri.renderString)
    val provider = new com.auth0.jwk.JwkProviderBuilder(url)
      .cached(10, 24, java.util.concurrent.TimeUnit.HOURS)
      .rateLimited(10, 1, java.util.concurrent.TimeUnit.MINUTES)
      .build()
    new SupabaseJwtAuthenticator(issuer, audience, jwksUri, provider)

  /** Visible for tests: inject a custom provider (e.g. backed by an in-test
    * JWKS server).
    */
  def withProvider(
      issuer: String,
      audience: String,
      jwksUri: Uri,
      provider: JwkProvider
  ): SupabaseJwtAuthenticator =
    new SupabaseJwtAuthenticator(issuer, audience, jwksUri, provider)
