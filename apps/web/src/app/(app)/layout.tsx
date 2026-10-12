import { redirect } from "next/navigation";
import { connection } from "next/server";
import { Suspense } from "react";

import { AppShell } from "@/components/shell/app-shell";
import { WorkspaceProvider } from "@/components/shell/workspace-context";
import { createClient } from "@/lib/supabase/server";

export default function AppGroupLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<main className="bg-background min-h-screen" aria-busy="true" />}>
      <AppGroupContent>{children}</AppGroupContent>
    </Suspense>
  );
}

async function AppGroupContent({ children }: { children: React.ReactNode }) {
  // Session-dependent shell: force request-time rendering so the layout's
  // auth calls never run inside a prerender pass (cacheComponents).
  await connection();
  // Middleware guards these routes; re-check here (defense in depth).
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app");

  const { data: profile } = await supabase
    .from("profile")
    .select("display_name")
    .eq("id", user.id)
    .maybeSingle();

  return (
    <WorkspaceProvider>
      <AppShell user={{ email: user.email ?? "", displayName: profile?.display_name ?? "" }}>
        {children}
      </AppShell>
    </WorkspaceProvider>
  );
}
