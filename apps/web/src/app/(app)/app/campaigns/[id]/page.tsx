import { redirect } from "next/navigation";

import { CampaignDetailContent } from "@/components/campaigns/campaigns-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Campaign" };

export default async function CampaignDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  const { id } = await params;
  if (!user) redirect(`/login?next=/app/campaigns/${id}`);

  return <CampaignDetailContent campaignId={id} />;
}
