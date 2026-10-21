import { redirect } from "next/navigation";

import { LeadFinderContent } from "@/components/leads/lead-finder-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Lead Finder" };

export default async function LeadFinderPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/leadfinder");

  return <LeadFinderContent />;
}
