"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { Mail } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { invitationsApi } from "@/lib/api/client";

type State =
  | { kind: "loading" }
  | { kind: "details"; email: string; workspaceName: string; role: string }
  | { kind: "accepted"; workspaceName: string }
  | { kind: "error"; message: string };

export function InvitationAcceptContent() {
  const params = useParams<{ token: string }>();
  const token = typeof params?.token === "string" ? params.token : "";
  const [state, setState] = useState<State>({ kind: "loading" });
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const load = async () => {
      if (!token) {
        setState({ kind: "error", message: "No invitation token in the link." });
        return;
      }
      try {
        const details = await invitationsApi.detailsByToken(token);
        setState({
          kind: "details",
          email: details.email,
          workspaceName: details.workspaceName,
          role: details.role,
        });
      } catch (e) {
        setState({
          kind: "error",
          message: e instanceof Error ? e.message : "This invitation link is not valid.",
        });
      }
    };
    void load();
  }, [token]);

  async function accept() {
    setLoading(true);
    try {
      const workspace = await invitationsApi.accept(token);
      setState({ kind: "accepted", workspaceName: workspace.name });
    } catch (e) {
      setState({ kind: "error", message: e instanceof Error ? e.message : "Could not accept." });
      setLoading(false);
    }
  }

  if (state.kind === "loading") {
    return (
      <Card>
        <Skeleton className="h-40" aria-busy="true" />
      </Card>
    );
  }

  if (state.kind === "accepted") {
    return (
      <Card>
        <CardHeader
          eyebrow="Invitation accepted"
          title={`You joined ${state.workspaceName}`}
          subtitle="The workspace now appears in your selector after you sign in."
        />
        <Alert variant="success">
          You&apos;re in. Head to your dashboard to see it in your workspace list.
        </Alert>
      </Card>
    );
  }

  if (state.kind === "error") {
    return (
      <Card>
        <CardHeader eyebrow="Invitation" title="This invitation is not available" />
        <Alert variant="warning">{state.message}</Alert>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Workspace invitation"
        title={`Join ${state.workspaceName}`}
        subtitle={`You were invited as ${state.role}.`}
      />
      <div className="mb-5 flex items-center gap-2">
        <Mail size={14} className="text-muted-foreground" />
        <span className="text-muted-foreground text-sm">{state.email}</span>
        <Badge>{state.role}</Badge>
      </div>
      <Button className="w-full" loading={loading} onClick={() => void accept()}>
        Accept invitation
      </Button>
    </Card>
  );
}
