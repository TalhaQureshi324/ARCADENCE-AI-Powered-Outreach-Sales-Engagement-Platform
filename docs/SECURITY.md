# Arcadence — Security Posture

> Living document. Records security-relevant decisions and invariants. Related: `AGENTS.md` §5, ADR-0003, ADR-0013.

**Last updated:** 2026-10-09

---

## 1. Secrets & credentials

**Invariants (enforced from day one):**

- No API keys, OAuth tokens, passwords, Supabase secret keys, or other credentials are ever committed. `.gitignore` blocks `.env*` (templates named `.env.example` are allowed and contain placeholders only).
- Browser-visible environment variables carry **only** public Supabase configuration (`NEXT_PUBLIC_*` / anon key).
- The **Supabase service-role key bypasses RLS**. It is used exclusively inside server-side code with tightly scoped purpose (e.g. admin jobs) and **never** substitutes for tenant authorization checks. It never reaches the browser, the frontend bundle, or logs.
- Secrets are injected per environment (local `.env.local` git-ignored; host/Vercel/VPS secret managers in deployment).

## 2. Authentication & session security

- Supabase Auth with the supported SSR integration (ADR-0003).
- The Scala API independently verifies every JWT: **issuer, audience, expiration, signature, and authorized algorithms**, using Supabase's JWKS endpoint. No `alg=none`, no unverified claims, no trust in frontend-provided identity beyond the token.
- Session/refresh-token handling follows current Supabase SSR guidance; token leakage detection and rotation settings are reviewed at the auth phase.

## 3. Multi-tenant isolation (defense in depth)

1. **RLS** on every tenant table, keyed to workspace membership via JWT claims.
2. **Scala-side authorization**: request → verified JWT → user → workspace membership → role check, performed for **every** read and write. Written as middleware + query-layer guards, not per-endpoint discretion.
3. **Cross-tenant isolation test suites** are mandatory for each new resource type; a resource without isolation tests is not done.
4. Service-role usage is enumerated and code-reviewed; each use site must justify why RLS-scoped access is insufficient.

## 4. Data protection

- **Provider credentials** (OAuth refresh tokens, SMTP/IMAP passwords) are stored encrypted at rest with AES-256-GCM (`CredentialEncryptor`, arcadence-core; ADR-0020): 12-byte random nonces, 16-byte auth tags, key held in the `EMAIL_ENCRYPTION_KEY` environment variable (base64, 32 bytes, validated at startup), ciphertexts tagged with `encryption_key_version` for future rotation. The `account_credentials` table has RLS enabled with **no policies** (PostgREST can never read it); disconnecting purges credentials; no API response includes credential fields (integration-tested). Wrong-key/tamper detections are indistinguishable by design. Gmail OAuth state parameters are HMAC-signed (domain-separated key derived from `EMAIL_ENCRYPTION_KEY`, 10-minute expiry) and the callback re-verifies workspace permission before completing a connection (ADR-0021). Gmail uses the minimum scopes (`gmail.send`, `gmail.readonly`, `userinfo.email`) — the first two are Google **restricted** scopes requiring OAuth verification + security assessment for production; a testing app is not production approval. Refreshed access tokens live in memory only; disconnect revokes at the provider best-effort (Google token revocation; Microsoft `revokeSignInSessions`), then purges local ciphertext. Microsoft Graph uses delegated `Mail.Send`/`Mail.Read`/`User.Read` with `offline_access`; tenant admins can block the app, and consent failures surface as typed permission errors (ADR-0022).
- **Storage:** private buckets only; access via short-lived signed URLs minted after authorization (ADR-0006).
- **PII discipline:** leads/contacts contain personal data — least-privilege defaults, no PII or credentials in logs, deletion pathways honored (GDPR-aligned retention rules defined in the compliance workstream).

## 5. Outbound email compliance & abuse posture

- First-class **consent, suppression, opt-out/unsubscribe** controls; suppression is checked on every send path.
- **One-click unsubscribe** headers and honest sender identity per current bulk-sender requirements (Google/Yahoo guidelines) are treated as baseline.
- **Never built:** mechanisms specifically intended to evade email-provider abuse controls — fake-engagement warmup networks, fingerprint/identity rotation, spam-filter evasion (ADR-0013). Deliverability features are monitoring + best-practice guidance only.
- **Delivery uncertainty:** network submission is at-least-once; unknown outcomes are recorded as unknown and reconciled via provider feedback loops — never presented to users as confirmed delivery (ADR-0005).

## 6. Application security practices

- All input validated at the edge (Tapir request decoding) and at the domain boundary; parameterized SQL only (Doobie) — no string-built queries.
- Uniform error responses without internal detail leakage; correlation IDs for supportability.
- Audit trail tables for security-relevant actions (membership/role changes, credential add/remove, exports, workspace deletion).
- Rate limiting and webhook signature verification land with the respective phases and are recorded here.
- Dependency vulnerability scanning (sbt/Scala + npm audits) in CI from Phase 0 CI onward; secrets scanning (e.g. gitleaks) planned alongside.

## 7. Operational security

- TLS everywhere (Vercel/host defaults + VPS ingress); database access restricted to Supabase poolers/connection strings held as secrets.
- Backups: Supabase-hosted backups; PITR tier decided at deployment phase.
- Least-privilege GitHub: branch protection on `main` (and `develop`) configured by the product owner (`docs/MANUAL_SETUP.md`); no shared credentials in repo or issues.

## 8. Current limitations (honest register)

- No application code exists yet; everything above is policy until the respective phase implements and tests it.
- Branch protection is **not yet enabled** on GitHub (product owner action).
- Threat-model workshop and pentest pass are scheduled for the hardening phase (Phase 13).
