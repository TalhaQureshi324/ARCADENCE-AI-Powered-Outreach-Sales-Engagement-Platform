import { redirect } from "next/navigation";

import { CampaignsListContent } from "@/components/campaigns/campaigns-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Campaigns" };

export default async function CampaignsPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/campaigns");

  return <CampaignsListContent />;
}
