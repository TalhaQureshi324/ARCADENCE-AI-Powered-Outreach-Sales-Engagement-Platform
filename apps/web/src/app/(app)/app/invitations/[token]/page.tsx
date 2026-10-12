import { InvitationAcceptContent } from "@/components/settings/invitation-accept";

export const metadata = { title: "Workspace invitation" };

export default function InvitationAcceptPage() {
  return (
    <main className="bg-background flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-md">
        <InvitationAcceptContent />
      </div>
    </main>
  );
}
