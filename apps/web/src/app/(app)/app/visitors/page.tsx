import { redirect } from "next/navigation";

import { VisitorsContent } from "@/components/visitors/visitors-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Website visitors" };

export default async function VisitorsPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/visitors");

  return <VisitorsContent />;
}
