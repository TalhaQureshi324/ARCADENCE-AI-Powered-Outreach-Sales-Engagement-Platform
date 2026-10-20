import { redirect } from "next/navigation";

import { InboxContent } from "@/components/inbox/inbox-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Inbox" };

export default async function InboxPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/inbox");

  return <InboxContent />;
}
