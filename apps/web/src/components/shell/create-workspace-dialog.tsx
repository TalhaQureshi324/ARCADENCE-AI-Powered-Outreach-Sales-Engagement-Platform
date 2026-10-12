"use client";

import { useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Field } from "@/components/ui/field";
import { ApiError, workspacesApi, type WorkspaceDto } from "@/lib/api/client";

export function CreateWorkspaceDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (workspace: WorkspaceDto) => void;
}) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmed = name.trim();
    if (!trimmed || trimmed.length > 120) {
      setError("Workspace name must be 1–120 characters.");
      return;
    }
    setLoading(true);
    try {
      const workspace = await workspacesApi.create(trimmed);
      setName("");
      onCreated(workspace);
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Could not create the workspace. Is the API running?",
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!loading) onOpenChange(next);
      }}
      title="Create workspace"
      description="A workspace isolates one client or team — leads, campaigns and inbox live inside it."
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {error && <Alert variant="error">{error}</Alert>}
        <Field
          id="workspace-name"
          label="Workspace name"
          placeholder="Acme Outreach"
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
        />
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" loading={loading}>
            Create workspace
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
