import { redirect } from "next/navigation";

import { AnalyticsContent } from "@/components/analytics/analytics-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Analytics" };

export default async function AnalyticsPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/analytics");

  return <AnalyticsContent />;
}
