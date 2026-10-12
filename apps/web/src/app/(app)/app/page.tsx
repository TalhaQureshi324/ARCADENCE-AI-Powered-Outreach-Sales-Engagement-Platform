import { DashboardContent } from "@/components/dashboard/dashboard-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Dashboard" };

export default async function AppDashboardPage() {
  // Auth is enforced by middleware + the (app) layout; fetch identity for the
  // greeting and hand off to the client shell for live data.
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();

  const { data: profile } = user
    ? await supabase.from("profile").select("display_name").eq("id", user.id).maybeSingle()
    : { data: null };

  return (
    <DashboardContent
      userName={profile?.display_name ?? user?.email ?? "there"}
      email={user?.email ?? ""}
      needsOnboarding={!profile?.display_name}
    />
  );
}
