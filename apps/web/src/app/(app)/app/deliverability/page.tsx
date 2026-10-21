import { redirect } from "next/navigation";

import { DeliverabilityContent } from "@/components/deliverability/deliverability-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Deliverability" };

export default async function DeliverabilityPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/deliverability");

  return <DeliverabilityContent />;
}
