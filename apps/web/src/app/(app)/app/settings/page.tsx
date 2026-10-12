import { redirect } from "next/navigation";

import { SettingsContent } from "@/components/settings/settings-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Workspace settings" };

export default async function WorkspaceSettingsPage() {
  // Session-dependent: awaiting the database makes the route render per
  // request (mirrors the dashboard page's dynamic pattern).
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/settings");

  const { data: profile } = await supabase
    .from("profile")
    .select("display_name")
    .eq("id", user.id)
    .maybeSingle();

  return <SettingsContent email={user.email ?? ""} />;
}
