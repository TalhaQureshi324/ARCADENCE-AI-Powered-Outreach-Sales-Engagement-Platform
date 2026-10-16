import io

p = 'e2e/campaigns.spec.ts'
s = io.open(p, encoding='utf-8').read()
s = s.replace(
    "select id, 'smtp_imap', 'sender-4-1@arcadence.test' from public.workspace",
    "select id, 'smtp_imap', 'sender-4-1@arcadence.test', 'connected' from public.workspace",
)
s = s.replace(
    "insert into public.email_accounts (workspace_id, provider, email_address) ",
    "insert into public.email_accounts (workspace_id, provider, email_address, status) ",
)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('e2e seed fixed')
