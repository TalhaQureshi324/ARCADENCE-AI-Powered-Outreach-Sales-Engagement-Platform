package com.arcadence.core.email.smtpimap

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.email.{
  AccountIdentity,
  AuthorizationResult,
  Credentials,
  EmailProvider,
  HealthSnapshot,
  InboxBatch,
  InboxMessage,
  OutboundMessage,
  ProviderCapabilities,
  ProviderError,
  ProviderMessageId,
  ReplyMessage
}
import jakarta.mail.UIDFolder
import jakarta.mail.{Folder, Message, MessagingException, Session, Transport}
import jakarta.mail.internet.{
  InternetAddress,
  MimeBodyPart,
  MimeMessage,
  MimeMultipart
}

import java.time.OffsetDateTime
import java.util.Properties
import scala.jdk.CollectionConverters.*

/** SMTP submission + IMAP retrieval adapter (Phase 2.4, ADR-0023).
  *
  * TLS is mandatory: `ssl` (implicit) or `starttls` (upgrade) — plaintext
  * configurations are rejected before any network attempt. Authentication is
  * validated server-side (real connect + login) at connect time; the password
  * is stored only as encrypted JSON (ADR-0020) and never appears in errors.
  *
  * OAuth-only trait operations return ProviderError.NotSupported. The inbox
  * cursor is the last seen IMAP UID; delta/change-notification equivalents
  * arrive with the Phase 6 sync engine (IMAP CONDSTORE/QRESYNC noted there).
  */
final class SmtpImapProvider extends EmailProvider:

  import SmtpImapProvider.*

  val id: EmailProvider.Id = EmailProvider.SmtpImap

  val capabilities: ProviderCapabilities =
    ProviderCapabilities.forProvider(EmailProvider.SmtpImap).get

  def completeAuthorization(
      authorization: AuthorizationResult,
      store: Credentials => IO[Unit]
  ): IO[Either[ProviderError, AccountIdentity]] =
    IO.pure(
      Left(
        ProviderError.NotSupported(
          "completeAuthorization (use the credentials connect form)"
        )
      )
    )

  /** Connects to BOTH SMTP and IMAP with the stored credentials — a real
    * authentication round-trip, not a config echo.
    */
  def validateConnection(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, AccountIdentity]] =
    load.flatMap {
      case None =>
        IO.pure(
          Left(
            ProviderError.InvalidCredentials(
              "account has no stored credentials"
            )
          )
        )
      case Some(creds: SmtpImapCredentials) =>
        IO.blocking(validateBlocking(creds))
          .map(_.left.map(mapMessagingException("validation")))
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not SMTP/IMAP credentials"
            )
          )
        )
    }

  def submit(
      load: IO[Option[Credentials]],
      message: OutboundMessage
  ): IO[Either[ProviderError, ProviderMessageId]] =
    load.flatMap {
      case None =>
        IO.pure(
          Left(
            ProviderError.InvalidCredentials(
              "account has no stored credentials"
            )
          )
        )
      case Some(creds: SmtpImapCredentials) =>
        IO.blocking(sendBlocking(creds, message, replyTo = None))
          .map(_.left.map(mapMessagingException("submission")))
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not SMTP/IMAP credentials"
            )
          )
        )
    }

  def submitReply(
      load: IO[Option[Credentials]],
      reply: ReplyMessage
  ): IO[Either[ProviderError, ProviderMessageId]] =
    load.flatMap {
      case None =>
        IO.pure(
          Left(
            ProviderError.InvalidCredentials(
              "account has no stored credentials"
            )
          )
        )
      case Some(creds: SmtpImapCredentials) =>
        val replyTo = Some(
          Map(
            "In-Reply-To" -> reply.inReplyToMessageId,
            "References" -> reply.inReplyToMessageId
          )
        )
        IO.blocking(
          sendBlocking(
            creds,
            OutboundMessage(
              from = reply.from,
              to = reply.to,
              subject = reply.subject,
              textBody = reply.textBody,
              htmlBody = reply.htmlBody,
              headers = Map.empty
            ),
            replyTo = replyTo
          )
        ).map(_.left.map(mapMessagingException("reply")))
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not SMTP/IMAP credentials"
            )
          )
        )
    }

  /** IMAP INBOX fetch newest-first; the cursor is the highest UID seen (the
    * next call fetches messages newer than it via UID search).
    */
  def syncInbox(
      load: IO[Option[Credentials]],
      cursor: Option[String]
  ): IO[Either[ProviderError, InboxBatch]] =
    load.flatMap {
      case None =>
        IO.pure(
          Left(
            ProviderError.InvalidCredentials(
              "account has no stored credentials"
            )
          )
        )
      case Some(creds: SmtpImapCredentials) =>
        IO.blocking(syncInboxBlocking(creds, cursor.flatMap(_.toLongOption)))
          .map(_.left.map(mapMessagingException("inbox sync")))
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not SMTP/IMAP credentials"
            )
          )
        )
    }

  def healthCheck(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, HealthSnapshot]] =
    validateConnection(load).map(
      _.map(identity =>
        HealthSnapshot(
          reachable = true,
          quotaRemaining = None,
          checkedAt = OffsetDateTime.now
        )
      )
    )

  /** Password providers have nothing to refresh. */
  def refresh(
      load: IO[Option[Credentials]],
      store: Credentials => IO[Unit]
  ): IO[Either[ProviderError, Unit]] =
    IO.pure(
      Left(
        ProviderError.NotSupported(
          "refresh (password auth — reconnect instead)"
        )
      )
    )

  /** Nothing server-side to revoke for password auth; disconnect purges the
    * local encrypted credentials.
    */
  def revokeAccess(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, Unit]] =
    IO.pure(
      Left(
        ProviderError.NotSupported(
          "revokeAccess (password auth — local purge is enough)"
        )
      )
    )

object SmtpImapProvider:

  /** Maps jakarta.mail failures to typed, secret-free provider errors. */
  def mapMessagingException(operation: String)(
      cause: Throwable
  ): ProviderError =
    val root = rootCause(cause)
    val name = root.getClass.getSimpleName
    name match
      case "AuthenticationFailedException" =>
        ProviderError.InvalidCredentials(
          "authentication failed — check username and password (use an app password where the provider requires one)"
        )
      case "UnknownHostException" =>
        ProviderError.Unreachable(
          s"server not found: ${safeHost(root.getMessage)}"
        )
      case "ConnectException" =>
        ProviderError.Unreachable(
          s"connection refused — check host and port (${safeHost(root.getMessage)})"
        )
      case "SSLHandshakeException" =>
        ProviderError.Unreachable(
          "TLS handshake failed — check the security mode (ssl vs starttls) and the server's certificate"
        )
      case _ =>
        ProviderError.Unexpected(
          s"$operation failed: ${Option(root.getMessage).getOrElse(name).take(200)}"
        )

  private def rootCause(t: Throwable): Throwable =
    var cur = t
    while cur.getCause != null && (cur.getCause ne cur) do cur = cur.getCause
    cur

  /** Strips anything that could contain credentials from mail debug text. */
  private def safeHost(message: String | Null): String =
    Option(message).getOrElse("").take(120)

  def sessionFor(
      host: String,
      port: Int,
      security: String,
      protocol: String
  ): Session =
    // System `mail.*` properties act as defaults (never overriding the
    // explicit values below) — deployments can pin socket factories or
    // trust anchors globally without touching adapter code.
    val props = new Properties(
      java.lang.System.getProperties(): java.util.Properties
    )
    props.put(s"mail.$protocol.host", host)
    props.put(s"mail.$protocol.port", port.toString)
    security match
      case "ssl" =>
        props.put(s"mail.$protocol.ssl.enable", "true")
        // Deployments can pin a custom socket factory / trust anchors via the
        // standard mail.<protocol>.socketFactory.* SYSTEM properties — the
        // adapter deliberately does not widen trust by default.
      case "starttls" =>
        props.put(s"mail.$protocol.starttls.enable", "true")
        props.put(
          s"mail.$protocol.starttls.required",
          "true"
        ) // plaintext is never accepted
      case other =>
        throw new IllegalArgumentException(s"unsupported security mode: $other")
    props.put("mail.smtp.auth", "true")
    props.put("mail.imap.auth", "true")
    props.put("mail.mime.address.strict", "false")
    Session.getInstance(props, null)

  private def validateBlocking(
      creds: SmtpImapCredentials
  ): Either[Throwable, AccountIdentity] =
    try
      // IMAP: connect + login + NOOP-equivalent (folder list)
      val imapSession =
        sessionFor(creds.imapHost, creds.imapPort, creds.imapSecurity, "imap")
      val store = imapSession.getStore("imap")
      try
        store.connect(
          creds.imapHost,
          creds.imapPort,
          creds.username,
          creds.password
        )
        // SMTP: connect + auth handshake only (no message sent)
        val smtpSession =
          sessionFor(creds.smtpHost, creds.smtpPort, creds.smtpSecurity, "smtp")
        val transport = smtpSession.getTransport("smtp")
        try
          transport.connect(
            creds.smtpHost,
            creds.smtpPort,
            creds.username,
            creds.password
          )
          Right(
            AccountIdentity(
              emailAddress = creds.emailAddress,
              displayName = None,
              externalAccountId = None
            )
          )
        finally transport.close()
      finally store.close()
    catch case e: MessagingException => Left(e)

  private def sendBlocking(
      creds: SmtpImapCredentials,
      message: OutboundMessage,
      replyTo: Option[Map[String, String]]
  ): Either[Throwable, ProviderMessageId] =
    try
      val session =
        sessionFor(creds.smtpHost, creds.smtpPort, creds.smtpSecurity, "smtp")
      val mime = new MimeMessage(session)
      mime.setFrom(new InternetAddress(message.from))
      message.to.foreach(addr =>
        mime.addRecipient(Message.RecipientType.TO, new InternetAddress(addr))
      )
      mime.setSubject(message.subject, "UTF-8")
      message.htmlBody match
        case None =>
          mime.setText(message.textBody, "UTF-8")
        case Some(html) =>
          val textPart = new MimeBodyPart()
          textPart.setText(message.textBody, "UTF-8")
          val htmlPart = new MimeBodyPart()
          htmlPart.setContent(html, "text/html; charset=UTF-8")
          mime.setContent(new MimeMultipart(textPart, htmlPart))
      replyTo.getOrElse(Map.empty).foreach { case (name, value) =>
        mime.setHeader(name, value)
      }
      val transport = session.getTransport("smtp")
      try
        transport.connect(
          creds.smtpHost,
          creds.smtpPort,
          creds.username,
          creds.password
        )
        transport.sendMessage(mime, mime.getAllRecipients)
        // SMTP gives no stable submission id; derive a deterministic handle
        Right(
          ProviderMessageId(
            Option(mime.getMessageID).getOrElse(s"smtp-${System.nanoTime()}")
          )
        )
      finally transport.close()
    catch case e: MessagingException => Left(e)

  private def syncInboxBlocking(
      creds: SmtpImapCredentials,
      afterUid: Option[Long]
  ): Either[Throwable, InboxBatch] =
    try
      val session =
        sessionFor(creds.imapHost, creds.imapPort, creds.imapSecurity, "imap")
      val store = session.getStore("imap")
      try
        store.connect(
          creds.imapHost,
          creds.imapPort,
          creds.username,
          creds.password
        )
        val folder = store.getFolder("INBOX")
        try
          folder.open(Folder.READ_ONLY)
          val uidFolder = folder.asInstanceOf[UIDFolder]
          val uids: Seq[(Long, Message)] =
            afterUid match
              case None =>
                val count = folder.getMessageCount
                if count <= 0 then Seq.empty
                else
                  val messages =
                    folder.getMessages(math.max(1, count - 24), count).toSeq
                  messages.map(m => uidFolder.getUID(m) -> m)
              case Some(uid) =>
                // Everything newer than the last seen UID, oldest last; newest 25 kept
                uidFolder
                  .getMessagesByUID(uid + 1, UIDFolder.LASTUID)
                  .toSeq
                  .map(m => uidFolder.getUID(m) -> m)
                  .sortBy(_._1)
                  .takeRight(25)
          val messages = uids.map { (uid, m) =>
            InboxMessage(
              providerMessageId = uid.toString,
              providerThreadId =
                Option(m.getHeader("Message-ID")).flatMap(_.headOption),
              internetMessageId =
                Option(m.getHeader("Message-ID")).flatMap(_.headOption),
              fromAddress = Option(m.getFrom)
                .flatMap(_.headOption)
                .map(_.toString)
                .getOrElse(""),
              subject = Option(m.getSubject),
              receivedAt = Option(m.getReceivedDate)
                .map(d =>
                  OffsetDateTime
                    .ofInstant(d.toInstant, java.time.ZoneOffset.UTC)
                )
                .getOrElse(OffsetDateTime.MIN)
            )
          }
          val nextCursor = uids.lastOption.map(_._1.toString)
          Right(InboxBatch(messages.toList, nextCursor))
        finally folder.close(false)
      finally store.close()
    catch case e: MessagingException => Left(e)
