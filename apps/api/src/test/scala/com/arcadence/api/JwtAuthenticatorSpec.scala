package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwk.Jwk
import com.comcast.ip4s.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.{HttpRoutes, Response}

import java.security.{KeyPair, KeyPairGenerator}
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.{Base64, UUID}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Verifies the SupabaseJwtAuthenticator against REAL ES256 signatures: an
  * embedded JWKS server publishes a generated P-256 key; tokens are signed with
  * the matching private key.
  */
final class JwtAuthenticatorSpec extends CatsEffectSuite:

  private val issuer = "https://test.supabase.co/auth/v1"
  private val audience = "test-project-ref"
  private val keyId = "test-key-1"

  private def newKeyPair: KeyPair =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(new ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()

  // One key pair for the whole suite; the JWKS server publishes its public half.
  private val keyPair = newKeyPair

  private def b64(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** P-256 coordinates are exactly 32 bytes: strip the BigInteger sign byte
    * when present, then left-pad. A short or over-long encoding makes the
    * published JWK unusable for signature verification.
    */
  private def b64Coord(v: java.math.BigInteger): String =
    val raw = v.toByteArray
    val body = if raw.length > 32 then raw.takeRight(32) else raw
    val padded = Array.fill(32 - body.length)(0.toByte) ++ body
    b64(padded)

  private def jwkJson: Json =
    val ec = keyPair.getPublic.asInstanceOf[ECPublicKey]
    val point = ec.getParams.getCurve // not used directly; coordinates below
    val w = ec.getW
    Json.obj(
      "kty" -> Json.fromString("EC"),
      "kid" -> Json.fromString(keyId),
      "use" -> Json.fromString("sig"),
      "alg" -> Json.fromString("ES256"),
      "crv" -> Json.fromString("P-256"),
      "x" -> Json.fromString(b64Coord(w.getAffineX)),
      "y" -> Json.fromString(b64Coord(w.getAffineY))
    )

  private var server: org.http4s.server.Server = null
  private var releaseServer: IO[Unit] = IO.unit
  private var authenticator: SupabaseJwtAuthenticator = null
  private var jwksUrl: String = _

  override def beforeAll(): Unit =
    val routes = HttpRoutes.of[IO] { _ =>
      IO.pure(
        Response[IO]().withEntity(
          Json.obj("keys" -> Json.arr(jwkJson)).noSpaces
        )
      )
    }
    val (srv, release) = EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(routes.orNotFound)
      .withShutdownTimeout(1.second)
      .build
      .allocated
      .unsafeRunSync()
    server = srv
    releaseServer = release
    jwksUrl =
      s"http://127.0.0.1:${server.address.getPort}/.well-known/jwks.json"
    authenticator = SupabaseJwtAuthenticator(
      issuer,
      audience,
      org.http4s.Uri.unsafeFromString(jwksUrl)
    )
    super.beforeAll()

  override def afterAll(): Unit =
    releaseServer.unsafeRunSync()
    super.afterAll()

  private def signToken(
      subject: String,
      email: String,
      expireInSecondsFromNow: Long = 3600,
      aud: String = audience,
      iss: String = issuer,
      key: KeyPair = keyPair
  ): String =
    val algorithm = Algorithm.ECDSA256(
      key.getPublic.asInstanceOf[ECPublicKey],
      key.getPrivate.asInstanceOf[java.security.interfaces.ECPrivateKey]
    )
    JWT.create
      .withKeyId(keyId)
      .withIssuer(iss)
      .withAudience(aud)
      .withSubject(subject)
      .withClaim("email", email)
      .withExpiresAt(java.time.Instant.now.plusSeconds(expireInSecondsFromNow))
      .sign(algorithm)

  private val userId = UUID.randomUUID()

  test("a valid token verifies and yields subject + email") {
    val token = signToken(userId.toString, "user@example.com")
    val result = authenticator.verify(token).unsafeRunSync()
    assertEquals(result.map(_.id), Right(userId))
    assertEquals(result.map(_.email), Right("user@example.com"))
  }

  test("an expired token is rejected as expired") {
    val token = signToken(
      userId.toString,
      "user@example.com",
      expireInSecondsFromNow = -60
    )
    val result = authenticator.verify(token).unsafeRunSync()
    assertEquals(result.left.map(_.code), Left("expired_token"))
  }

  test("a token for a different audience is rejected") {
    val token =
      signToken(userId.toString, "user@example.com", aud = "other-project")
    val result = authenticator.verify(token).unsafeRunSync()
    assertEquals(result.left.map(_.code), Left("invalid_token"))
  }

  test("a token from a different issuer is rejected") {
    val token = signToken(
      userId.toString,
      "user@example.com",
      iss = "https://evil.example.com/auth/v1"
    )
    val result = authenticator.verify(token).unsafeRunSync()
    assertEquals(result.left.map(_.code), Left("invalid_token"))
  }

  test("a token signed with an unknown key is rejected") {
    val otherPair = newKeyPair
    val token = signToken(userId.toString, "user@example.com", key = otherPair)
    val result = authenticator.verify(token).unsafeRunSync()
    assertEquals(result.left.map(_.code), Left("invalid_token"))
  }

  test("garbage tokens are rejected without throwing") {
    val result = authenticator.verify("not-a-token").unsafeRunSync()
    assertEquals(result.left.map(_.code), Left("invalid_token"))
  }
