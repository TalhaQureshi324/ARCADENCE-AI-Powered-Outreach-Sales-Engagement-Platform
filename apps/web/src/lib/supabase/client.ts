import { createBrowserClient } from "@supabase/ssr";

/** Browser-side Supabase client (publishable key only — safe by design). */
export function createClient() {
  return createBrowserClient(
    process.env.NEXT_PUBLIC_SUPABASE_URL!,
    process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!,
  );
}
