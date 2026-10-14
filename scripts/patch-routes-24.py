import io

p = 'apps/api/src/main/scala/com/arcadence/api/EmailAccountRoutes.scala'
s = io.open(p, encoding='utf-8').read()
bs = chr(92)

# DTO block
old = '''  final case class EmailAccountDto(
      id: UUID,
      accountGroupId: Option[UUID],
      accountGroupName: Option[String],
      provider: String,
      emailAddress: String,
      displayName: Option[String],
      status: String,
      statusMessage: Option[String],
      dailySendCap: Option[Int],
      isDefault: Boolean,
      lastSyncedAt: Option[OffsetDateTime],
      createdAt: OffsetDateTime
  )'''
new = '''  final case class EmailAccountDto(
      id: UUID,
      accountGroupId: Option[UUID],
      accountGroupName: Option[String],
      provider: String,
      emailAddress: String,
      displayName: Option[String],
      status: String,
      statusMessage: Option[String],
      dailySendCap: Option[Int],
      signature: Option[String],
      isDefault: Boolean,
      lastSyncedAt: Option[OffsetDateTime],
      syncStatus: Option[String],
      syncError: Option[String],
      createdAt: OffsetDateTime
  )

  /** Connect-by-credentials request (SMTP/IMAP). The password is validated
    * server-side against the real servers, then stored encrypted — it is
    * never echoed back, logged, or included in any response.
    */
  final case class SmtpImapConnectRequest(
      emailAddress: String,
      displayName: Option[String],
      username: String,
      password: String,
      smtpHost: String,
      smtpPort: Int,
      smtpSecurity: String,
      imapHost: String,
      imapPort: Int,
      imapSecurity: String
  )
  object SmtpImapConnectRequest:
    given Encoder[SmtpImapConnectRequest] = deriveEncoder
    given Decoder[SmtpImapConnectRequest] = deriveDecoder
    given Schema[SmtpImapConnectRequest] = Schema.derived'''
assert old in s, 'dto'
s = s.replace(old, new)

old = '''  final case class UpdateAccountRequest(
      displayName: Option[Option[String]] = None,
      accountGroupId: Option[Option[UUID]] = None,
      dailySendCap: Option[Option[Int]] = None
  )'''
new = '''  final case class UpdateAccountRequest(
      displayName: Option[Option[String]] = None,
      accountGroupId: Option[Option[UUID]] = None,
      dailySendCap: Option[Option[Int]] = None,
      signature: Option[Option[String]] = None
  )'''
assert old in s, 'update req'
s = s.replace(old, new)

old = '''        dailySendCap <- field[Int]("dailySendCap")
      yield UpdateAccountRequest(displayName, accountGroupId, dailySendCap)'''
new = '''        dailySendCap <- field[Int]("dailySendCap")
        signature <- field[String]("signature")
      yield UpdateAccountRequest(displayName, accountGroupId, dailySendCap, signature)'''
assert old in s, 'decoder'
s = s.replace(old, new)

old = '''      dailySendCap = a.dailySendCap,
      isDefault = a.isDefault,
      lastSyncedAt = a.lastSyncedAt,
      createdAt = a.createdAt
    )'''
new = '''      dailySendCap = a.dailySendCap,
      signature = a.signature,
      isDefault = a.isDefault,
      lastSyncedAt = a.lastSyncedAt,
      syncStatus = a.syncStatus,
      syncError = a.syncError,
      createdAt = a.createdAt
    )'''
assert old in s, 'accountDto'
s = s.replace(old, new)

old = '''            yield repos.AccountPatch(
              displayName = displayName,
              accountGroupId = request.accountGroupId,
              dailySendCap = cap
            )'''
new = '''            yield repos.AccountPatch(
              displayName = displayName,
              accountGroupId = request.accountGroupId,
              dailySendCap = cap,
              signature = request.signature
            )'''
assert old in s, 'patch'
s = s.replace(old, new)

# connect endpoint
anchor = '  // -- authorized test send (provider-agnostic via the adapter registry) ------'
regex = '"^[^' + bs + bs + 's@]+@[^' + bs + bs + 's@]+' + bs + bs + '.[^' + bs + bs + 's@]+$"'
new_endpoint = '''  // -- connect by credentials (SMTP/IMAP, Phase 2.4) ---------------------------

  private val connectSmtpImap = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "email-accounts")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[SmtpImapConnectRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[EmailAccountDto])
    .errorOut(errorOut)

  private def connectSmtpImapLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: SmtpImapConnectRequest
  ): IO[Either[(SttpStatus, Envelope), EmailAccountDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageEmailAccounts)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val email = request.emailAddress.trim.toLowerCase
          if !email.matches(''' + repr(regex) + ''') then
            IO.pure(Left(failed(ApiError.Invalid("Enter a valid email address."))))
          else
            val creds = SmtpImapCredentials(
              emailAddress = email,
              username = request.username.trim,
              password = request.password,
              smtpHost = request.smtpHost.trim,
              smtpPort = request.smtpPort,
              smtpSecurity = request.smtpSecurity.trim.toLowerCase,
              imapHost = request.imapHost.trim,
              imapPort = request.imapPort,
              imapSecurity = request.imapSecurity.trim.toLowerCase
            )
            SmtpImapCredentials.validate(creds) match
              case Left(detail) => IO.pure(Left(failed(ApiError.Invalid(detail))))
              case Right(())    =>
                registry match
                  case None =>
                    IO.pure(Left(failed(ApiError.ProviderNotConfigured("No adapter registry configured."))))
                  case Some(reg) =>
                    reg.adapter(com.arcadence.core.email.EmailProvider.SmtpImap) match
                      case None =>
                        IO.pure(Left(failed(ApiError.ProviderNotConfigured("SMTP/IMAP adapter unavailable."))))
                      case Some(adapter) =>
                        (for
                          existing <- repos.listAccounts(workspaceId)
                          duplicate = existing.exists(_.emailAddress.equalsIgnoreCase(email))
                          validated <-
                            if duplicate then
                              IO.pure(Left[ProviderError, Unit](ProviderError.InvalidCredentials("duplicate")))
                            else adapter.validateConnection(IO.pure(Some(creds)))
                          result <- validated match
                            case Left(providerError) =>
                              // Never echo the password; safe detail only.
                              IO.pure(Left(failed(ApiError.Provider(providerError.detail.take(200)))))
                            case Right(_) =>
                              (for
                                accountId <- repos.createSmtpImapAccount(
                                  workspaceId,
                                  email,
                                  request.displayName.map(_.trim).filter(_.nonEmpty)
                                )
                                _ <- repos.ensureSyncState(accountId)
                                _ <- reg.credentialStorer(repos, accountId)(creds)
                                _ <- repos.recordHealthEvent(
                                  workspaceId,
                                  accountId,
                                  "connected",
                                  Some("SMTP/IMAP account connected; server authentication verified.")
                                )
                                _ <- repos.audit(
                                  workspaceId,
                                  user.id,
                                  "email_account.connected",
                                  "email_account",
                                  accountId.toString,
                                  Json.obj("provider" -> Json.fromString("smtp_imap"))
                                )
                                view <- repos.findAccount(workspaceId, accountId)
                              yield view).map {
                                case None => Left(failed(ApiError.NotFound("email account")))
                                case Some(v) => Right(accountDto(v))
                              }
                        yield result).flatten
      }

''' + anchor
assert anchor in s, 'endpoint anchor'
s = s.replace(anchor, new_endpoint, 1)

old = '''      testSend.serverLogic((workspaceId, accountId, auth, req) =>
        testSendLogic(workspaceId, accountId, auth, req)
      )
    )
  )'''
new = '''      testSend.serverLogic((workspaceId, accountId, auth, req) =>
        testSendLogic(workspaceId, accountId, auth, req)
      ),
      connectSmtpImap.serverLogic((workspaceId, auth, req) =>
        connectSmtpImapLogic(workspaceId, auth, req)
      )
    )
  )'''
assert old in s, 'list'
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('routes complete')
