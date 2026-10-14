import { redirect } from "next/navigation";

import { LeadsContent } from "@/components/leads/leads-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Leads" };

export default async function LeadsPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/leads");

  return <LeadsContent />;
}
