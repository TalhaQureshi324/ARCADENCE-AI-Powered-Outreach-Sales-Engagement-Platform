import io

p = 'apps/web/src/components/email-accounts/email-accounts-content.tsx'
s = io.open(p, encoding='utf-8').read()

# 1. imports: emailAccountsApi.create used later; add PencilIcon already imported (Pencil). Add KeyRound for password icon? skip.

# 2. Connect SMTP/IMAP dialog + ProvidersSection wiring
old = '''function ProvidersSection({
  workspaceId,
  myRole,
  onAccountCreated,
}: {
  workspaceId: string;
  myRole: string;
  onAccountCreated: () => void;
}) {
  const router = useRouter();
  const manageable = canManage(myRole);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  const [smtpFormOpen, setSmtpFormOpen] = useState(false);
  const [providers, setProviders] = useState<LoadState<EmailProviderDto[]>>({
    kind: "loading",
  });

  async function connect(providerId: string) {
    if ((OAUTH_PROVIDERS as readonly string[]).includes(providerId)) {
      setConnecting(true);
      setConnectError(null);
      try {
        await startProviderConnect(workspaceId, providerId); // navigates away on success
      } catch (e) {
        if (isUnauthorized(e)) {
          const { createClient } = await import("@/lib/supabase/client");
          await createClient().auth.signOut();
          router.push("/login?expired=1");
          return;
        }
        setConnectError(errorMessage(e));
        setConnecting(false);
      }
    } else if (providerId === "smtp_imap") {
      setSmtpFormOpen(true);
    }
  }'''
new = '''function ProvidersSection({
  workspaceId,
  myRole,
  onAccountCreated,
}: {
  workspaceId: string;
  myRole: string;
  onAccountCreated: () => void;
}) {
  const router = useRouter();
  const manageable = canManage(myRole);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  const [smtpFormOpen, setSmtpFormOpen] = useState(false);
  const [providers, setProviders] = useState<LoadState<EmailProviderDto[]>>({
    kind: "loading",
  });

  async function connect(providerId: string) {
    if ((OAUTH_PROVIDERS as readonly string[]).includes(providerId)) {
      setConnecting(true);
      setConnectError(null);
      try {
        await startProviderConnect(workspaceId, providerId); // navigates away on success
      } catch (e) {
        if (isUnauthorized(e)) {
          const { createClient } = await import("@/lib/supabase/client");
          await createClient().auth.signOut();
          router.push("/login?expired=1");
          return;
        }
        setConnectError(errorMessage(e));
        setConnecting(false);
      }
    } else if (providerId === "smtp_imap") {
      setSmtpFormOpen(true);
    }
  }'''
assert old in s, 'providers head'
s = s.replace(old, new)

# 3. footer copy: smtp unconfigured case never happens; adjust the else branch text
old = '''                <>
                  <Badge tone="warning">Setup arrives in {p.connectFlow}</Badge>
                  <p className="text-muted-foreground mt-2 text-xs">
                    The connect flow is built in {p.connectFlow}; provider-console prerequisites
                    are listed in docs/MANUAL_SETUP.md.
                  </p>
                </>'''
new = '''                <>
                  <Badge tone="warning">Not yet available</Badge>
                  <p className="text-muted-foreground mt-2 text-xs">
                    This provider's connect flow has not shipped yet.
                  </p>
                </>'''
assert old in s, 'footer'
s = s.replace(old, new)

# 4. render the SMTP form dialog inside ProvidersSection
old = '''              {connectError && (
                <div className="mt-2">
                  <Alert variant="error">{connectError}</Alert>
                </div>
              )}
            </div>
          </Card>
        ))}
      </div>
    </section>
  );
}'''
new = '''              {connectError && (
                <div className="mt-2">
                  <Alert variant="error">{connectError}</Alert>
                </div>
              )}
            </div>
          </Card>
        ))}
      </div>
      <ConnectSmtpImapDialog
        open={smtpFormOpen}
        workspaceId={workspaceId}
        onClose={() => setSmtpFormOpen(false)}
        onConnected={onAccountCreated}
      />
    </section>
  );
}

// -- SMTP/IMAP connect form ---------------------------------------------------

const SECURITY_OPTIONS = [
  { value: "ssl", label: "SSL/TLS (implicit)" },
  { value: "starttls", label: "STARTTLS (upgrade)" },
];

function ConnectSmtpImapDialog({
  open,
  workspaceId,
  onClose,
  onConnected,
}: {
  open: boolean;
  workspaceId: string;
  onClose: () => void;
  onConnected: () => void;
}) {
  const router = useRouter();
  const { toast } = useToast();
  const [form, setForm] = useState({
    emailAddress: "",
    displayName: "",
    username: "",
    password: "",
    smtpHost: "",
    smtpPort: "587",
    smtpSecurity: "starttls",
    imapHost: "",
    imapPort: "993",
    imapSecurity: "ssl",
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function set(field: keyof typeof form, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      await emailAccountsApi.create(workspaceId, {
        emailAddress: form.emailAddress.trim(),
        displayName: form.displayName.trim() || undefined,
        username: form.username.trim(),
        password: form.password,
        smtpHost: form.smtpHost.trim(),
        smtpPort: Number(form.smtpPort),
        smtpSecurity: form.smtpSecurity,
        imapHost: form.imapHost.trim(),
        imapPort: Number(form.imapPort),
        imapSecurity: form.imapSecurity,
      });
      toast("success", "SMTP/IMAP account connected and verified.");
      setForm({
        emailAddress: "",
        displayName: "",
        username: "",
        password: "",
        smtpHost: "",
        smtpPort: "587",
        smtpSecurity: "starttls",
        imapHost: "",
        imapPort: "993",
        imapSecurity: "ssl",
      });
      onClose();
      onConnected();
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setError(errorMessage(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title="Connect an SMTP/IMAP account"
      description="Arcadence verifies the credentials by signing in to both servers (TLS required) before storing them encrypted. Use an app password where the provider demands one — never disable two-factor protection."
    >
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Input
          aria-label="Email address"
          placeholder="sender@yourdomain.com"
          type="email"
          required
          value={form.emailAddress}
          onChange={(e) => set("emailAddress", e.target.value)}
        />
        <Input
          aria-label="Display name (optional)"
          placeholder="e.g. Primary sender"
          value={form.displayName}
          onChange={(e) => set("displayName", e.target.value)}
        />
        <Input
          aria-label="Username"
          placeholder="usually the email address"
          required
          value={form.username}
          onChange={(e) => set("username", e.target.value)}
        />
        <Input
          aria-label="Password"
          type="password"
          required
          value={form.password}
          onChange={(e) => set("password", e.target.value)}
        />
        <div className="grid grid-cols-3 gap-2">
          <Input
            aria-label="SMTP host"
            placeholder="smtp.example.com"
            required
            value={form.smtpHost}
            onChange={(e) => set("smtpHost", e.target.value)}
          />
          <Input
            aria-label="SMTP port"
            type="number"
            required
            value={form.smtpPort}
            onChange={(e) => set("smtpPort", e.target.value)}
          />
          <select
            aria-label="SMTP security"
            value={form.smtpSecurity}
            onChange={(e) => set("smtpSecurity", e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          >
            {SECURITY_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-3 gap-2">
          <Input
            aria-label="IMAP host"
            placeholder="imap.example.com"
            required
            value={form.imapHost}
            onChange={(e) => set("imapHost", e.target.value)}
          />
          <Input
            aria-label="IMAP port"
            type="number"
            required
            value={form.imapPort}
            onChange={(e) => set("imapPort", e.target.value)}
          />
          <select
            aria-label="IMAP security"
            value={form.imapSecurity}
            onChange={(e) => set("imapSecurity", e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          >
            {SECURITY_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        {error && <Alert variant="error">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={submitting}>
            Verify and connect
          </Button>
        </div>
      </form>
    </Dialog>
  );
}'''
assert old in s, 'dialog render'
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('part 1 done')
