import io

p = r"D:\Semester 07\Office Work\Arcadence-Platform\apps\api\src\main\scala\com\arcadence\api\WorkspaceRoutes.scala"
s = io.open(p, encoding="utf-8").read()

old_vb = '''  private def verifyBearer(authorization: String): IO[Either[Envelope, AuthenticatedUser]] =
    if !authorization.startsWith("Bearer ") then IO.pure(Left(ApiError.Unauthorized.toEnvelope))
    else
      val token = authorization.stripPrefix("Bearer ").trim
      authenticator.verify(token).map(_.leftMap(e => Envelope(e.code, e.message)))'''
new_vb = '''  private def verifyBearer(authorization: Option[String]): IO[Either[Envelope, AuthenticatedUser]] =
    authorization match
      case Some(token) if token.startsWith("Bearer ") =>
        authenticator
          .verify(token.stripPrefix("Bearer ").trim)
          .map(_.leftMap(e => Envelope(e.code, e.message)))
      case _ => IO.pure(Left(ApiError.Unauthorized.toEnvelope))'''
assert old_vb in s, "verifyBearer pattern"
s = s.replace(old_vb, new_vb, 1)

s = s.replace('.in(header[String]("Authorization"))', '.in(header[Option[String]]("Authorization"))')

replacements = [
    ("(authorization: String): IO[Either[Envelope, List[WorkspaceDto]]]",
     "(authorization: Option[String]): IO[Either[Envelope, List[WorkspaceDto]]]"),
    ("(authorization: String, request: CreateWorkspaceRequest)",
     "(authorization: Option[String], request: CreateWorkspaceRequest)"),
    ("(authorization: String, workspaceId: UUID)",
     "(authorization: Option[String], workspaceId: UUID)"),
    ("(authorization: String, workspaceId: UUID, request: UpdateWorkspaceRequest)",
     "(authorization: Option[String], workspaceId: UUID, request: UpdateWorkspaceRequest)"),
    ("(authorization: String, workspaceId: UUID, targetUserId: UUID, request: UpdateMemberRequest)",
     "(authorization: Option[String], workspaceId: UUID, targetUserId: UUID, request: UpdateMemberRequest)"),
    ("(authorization: String, workspaceId: UUID, targetUserId: UUID)",
     "(authorization: Option[String], workspaceId: UUID, targetUserId: UUID)"),
    ("(authorization: String, workspaceId: UUID, request: CreateInvitationRequest)",
     "(authorization: Option[String], workspaceId: UUID, request: CreateInvitationRequest)"),
    ("(authorization: String, workspaceId: UUID, invitationId: UUID)",
     "(authorization: Option[String], workspaceId: UUID, invitationId: UUID)"),
    ("(authorization: String, token: UUID)",
     "(authorization: Option[String], token: UUID)"),
]
for old, new in replacements:
    s = s.replace(old, new)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("optional header wired:", s.count("authorization: Option[String]"))
