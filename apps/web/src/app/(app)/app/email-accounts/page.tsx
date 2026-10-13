import { redirect } from "next/navigation";

import { EmailAccountsContent } from "@/components/email-accounts/email-accounts-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Email accounts" };

export default async function EmailAccountsPage() {
  // Session-dependent: awaiting auth keeps the route dynamic (mirrors the
  // settings page's pattern).
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/email-accounts");

  return <EmailAccountsContent />;
}
