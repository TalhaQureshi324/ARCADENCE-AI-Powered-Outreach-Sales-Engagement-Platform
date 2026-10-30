import { redirect } from "next/navigation";

import { AiStudioContent } from "@/components/ai/ai-studio-content";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "AI Studio" };

export default async function AiStudioPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app/aistudio");

  return <AiStudioContent />;
}
