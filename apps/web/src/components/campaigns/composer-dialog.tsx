"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Redo2, Save, Send, Undo2 } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  ApiError,
  composerApi,
  contactsApi,
  type CampaignVariantDto,
  type ContactDto,
  type EmailTemplateDto,
  type PreviewResponseDto,
  type ReusableBlockDto,
} from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

const TOKEN_FIELDS = ["first_name", "last_name", "email", "company", "job_title", "region"];

export interface ComposerTarget {
  stepId: string;
  stepName: string;
  variantKey: string;
  variant: CampaignVariantDto;
}

/** Email composer (Phase 4.2): subject/preheader/body/signature editing with
 * debounced autosave, personalization tokens with per-token validation,
 * saved templates and reusable blocks, live preview against a REAL contact
 * (missing variables are shown, never fabricated), authorized test send to
 * the signed-in user's own address, and local undo/redo.
 */
export function ComposerDialog({
  open,
  workspaceId,
  campaignId,
  target,
  onClose,
}: {
  open: boolean;
  workspaceId: string;
  campaignId: string;
  target: ComposerTarget | null;
  onClose: () => void;
}) {
  const [subject, setSubject] = useState("");
  const [preheader, setPreheader] = useState("");
  const [bodyHtml, setBodyHtml] = useState("");
  const [bodyText, setBodyText] = useState("");
  const [signature, setSignature] = useState("");

  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [saveError, setSaveError] = useState<string | null>(null);
  const [unknown, setUnknown] = useState<string[]>([]);
  const [tokens, setTokens] = useState<{ field: string; fallback: string | null }[]>([]);

  const [templates, setTemplates] = useState<EmailTemplateDto[]>([]);
  const [blocks, setBlocks] = useState<ReusableBlockDto[]>([]);
  const [contacts, setContacts] = useState<ContactDto[]>([]);
  const [previewContact, setPreviewContact] = useState("");
  const [preview, setPreview] = useState<PreviewResponseDto | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [testBusy, setTestBusy] = useState(false);
  const [templateName, setTemplateName] = useState("");

  // Undo/redo stacks of the last editorial states.
  const undoStack = useRef<string[][]>([]);
  const redoStack = useRef<string[][]>([]);
  const skipHistory = useRef(false);

  const snapshot = useCallback(
    () => [subject, preheader, bodyHtml, bodyText, signature],
    [subject, preheader, bodyHtml, bodyText, signature],
  );

  const applySnapshot = useCallback((s: string[]) => {
    skipHistory.current = true;
    setSubject(s[0] ?? "");
    setPreheader(s[1] ?? "");
    setBodyHtml(s[2] ?? "");
    setBodyText(s[3] ?? "");
    setSignature(s[4] ?? "");
  }, []);

  // Load content + sidebars when opened.
  useEffect(() => {
    if (!open || !target) return;
    Promise.resolve().then(() => {
      setSubject(target.variant.subject);
      setPreheader(target.variant.preheader);
      setBodyHtml(target.variant.bodyHtml);
      setBodyText(target.variant.bodyText);
      setSignature(target.variant.signature);
      setSaveState("idle");
      setSaveError(null);
      setUnknown([]);
      setPreview(null);
      setPreviewError(null);
      setTemplateName("");
      undoStack.current = [];
      redoStack.current = [];
    });
    composerApi.templates
      .list(workspaceId)
      .then(setTemplates)
      .catch(() => setTemplates([]));
    composerApi.blocks
      .list(workspaceId)
      .then(setBlocks)
      .catch(() => setBlocks([]));
    contactsApi
      .list(workspaceId, { limit: 25 })
      .then((page) => setContacts(page.items))
      .catch(() => setContacts([]));
  }, [open, target, workspaceId]);

  // Debounced autosave.
  useEffect(() => {
    if (!open || !target) return;
    const handle = setTimeout(async () => {
      setSaveState("saving");
      try {
        const result = await composerApi.saveVariant(
          workspaceId,
          campaignId,
          target.stepId,
          target.variantKey,
          { subject, preheader, bodyHtml, bodyText, signature },
        );
        setUnknown(result.validation.unknown);
        setTokens(result.validation.tokens);
        setSaveState("saved");
        setSaveError(null);
      } catch (e) {
        setSaveState("error");
        setSaveError(errorMessage(e));
      }
    }, 1200);
    return () => clearTimeout(handle);
  }, [subject, preheader, bodyHtml, bodyText, signature, open, target, workspaceId, campaignId]);

  function pushHistory() {
    if (skipHistory.current) {
      skipHistory.current = false;
      return;
    }
    undoStack.current = [...undoStack.current.slice(-19), snapshot()];
    redoStack.current = [];
  }

  function undo() {
    const prev = undoStack.current.pop();
    if (!prev) return;
    redoStack.current = [...redoStack.current, snapshot()];
    applySnapshot(prev);
  }

  function redo() {
    const next = redoStack.current.pop();
    if (!next) return;
    undoStack.current = [...undoStack.current, snapshot()];
    applySnapshot(next);
  }

  async function refreshPreview() {
    if (!target || !previewContact) return;
    setPreviewBusy(true);
    setPreviewError(null);
    try {
      const result = await composerApi.preview(workspaceId, campaignId, {
        stepId: target.stepId,
        variantKey: target.variantKey,
        contactId: previewContact,
      });
      setPreview(result);
    } catch (e) {
      setPreviewError(errorMessage(e));
    } finally {
      setPreviewBusy(false);
    }
  }

  async function sendTest() {
    if (!target || !previewContact) return;
    setTestBusy(true);
    setPreviewError(null);
    try {
      const result = await composerApi.testSend(workspaceId, campaignId, {
        stepId: target.stepId,
        variantKey: target.variantKey,
        contactId: previewContact,
      });
      // The toast surfaces delivery info; the contact's data is used for
      // personalization but the email ALWAYS goes to the signed-in user.
      window.alert(`Test sent: ${result.from} → ${result.to}`);
    } catch (e) {
      setPreviewError(errorMessage(e));
    } finally {
      setTestBusy(false);
    }
  }

  async function saveAsTemplate() {
    if (!templateName.trim()) return;
    try {
      await composerApi.templates.create(workspaceId, {
        name: templateName.trim(),
        subject,
        preheader,
        bodyHtml,
        bodyText,
      });
      setTemplates(await composerApi.templates.list(workspaceId));
      setTemplateName("");
    } catch (e) {
      setPreviewError(errorMessage(e));
    }
  }

  if (!target) return null;

  const insertToken = (field: string) => {
    pushHistory();
    setBodyHtml((h) => `${h}{{${field}}}`);
    setBodyText((t) => `${t}{{${field}}}`);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title={`Compose · step “${target.stepName}” · variant ${target.variantKey}`}
      description="Autosaves as a draft. Tokens like {{first_name}} personalize per lead; {{field|fallback}} supplies a default. Preview always uses a real contact — nothing is invented."
    >
      <div className="max-h-[70vh] space-y-4 overflow-auto pr-1">
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="ghost" onClick={undo} aria-label="Undo">
            <Undo2 size={14} />
          </Button>
          <Button type="button" variant="ghost" onClick={redo} aria-label="Redo">
            <Redo2 size={14} />
          </Button>
          <span className="text-muted-foreground flex items-center gap-1 text-xs">
            <Save size={12} />
            {saveState === "saving"
              ? "Saving…"
              : saveState === "saved"
                ? "All changes saved"
                : saveState === "error"
                  ? "Save failed"
                  : "Autosave on"}
          </span>
          {tokens.length > 0 && (
            <span className="flex flex-wrap items-center gap-1">
              {tokens.map((t) => (
                <Badge key={t.field} tone="primary">
                  {t.field}
                  {t.fallback ? `|${t.fallback}` : ""}
                </Badge>
              ))}
            </span>
          )}
          {unknown.length > 0 && <Badge tone="danger">Unknown: {unknown.join(", ")}</Badge>}
        </div>

        <Input
          aria-label="Subject"
          placeholder="Subject"
          value={subject}
          onChange={(e) => {
            pushHistory();
            setSubject(e.target.value);
          }}
          maxLength={200}
        />
        <Input
          aria-label="Preheader"
          placeholder="Preheader (inbox preview text)"
          value={preheader}
          onChange={(e) => {
            pushHistory();
            setPreheader(e.target.value);
          }}
          maxLength={200}
        />

        <div className="flex flex-wrap items-center gap-1">
          <span className="text-muted-foreground text-xs">Insert:</span>
          {TOKEN_FIELDS.map((f) => (
            <Button key={f} type="button" variant="secondary" onClick={() => insertToken(f)}>
              {`{{${f}}}`}
            </Button>
          ))}
        </div>

        <textarea
          aria-label="Body HTML"
          placeholder="HTML body"
          value={bodyHtml}
          onChange={(e) => {
            pushHistory();
            setBodyHtml(e.target.value);
          }}
          rows={8}
          className="border-border bg-surface text-foreground w-full rounded-lg border px-3 py-2 font-mono text-xs"
        />
        <textarea
          aria-label="Plain-text body"
          placeholder="Plain-text alternative (generated from HTML when left empty)"
          value={bodyText}
          onChange={(e) => {
            pushHistory();
            setBodyText(e.target.value);
          }}
          rows={4}
          className="border-border bg-surface text-foreground w-full rounded-lg border px-3 py-2 font-mono text-xs"
        />
        <textarea
          aria-label="Signature"
          placeholder="Signature HTML (appended to every send)"
          value={signature}
          onChange={(e) => {
            pushHistory();
            setSignature(e.target.value);
          }}
          rows={2}
          className="border-border bg-surface text-foreground w-full rounded-lg border px-3 py-2 font-mono text-xs"
        />

        <div className="grid gap-3 md:grid-cols-2">
          <div className="space-y-2">
            <div className="text-foreground text-sm font-medium">Templates</div>
            <select
              aria-label="Load template"
              value=""
              onChange={(e) => {
                const t = templates.find((x) => x.id === e.target.value);
                if (t) {
                  pushHistory();
                  setSubject(t.subject);
                  setPreheader(t.preheader);
                  setBodyHtml(t.bodyHtml);
                  setBodyText(t.bodyText);
                }
              }}
              className="border-border bg-surface text-foreground w-full rounded-lg border px-2 py-2 text-sm"
            >
              <option value="">Load a saved template…</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
            <div className="flex gap-2">
              <Input
                aria-label="Template name"
                placeholder="Save current as…"
                value={templateName}
                onChange={(e) => setTemplateName(e.target.value)}
                maxLength={120}
              />
              <Button
                type="button"
                variant="secondary"
                onClick={() => void saveAsTemplate()}
                disabled={!templateName.trim()}
              >
                Save
              </Button>
            </div>
          </div>
          <div className="space-y-2">
            <div className="text-foreground text-sm font-medium">Reusable blocks</div>
            <div className="flex flex-wrap gap-1">
              {blocks.length === 0 && (
                <span className="text-muted-foreground text-xs">No blocks saved yet.</span>
              )}
              {blocks.map((b) => (
                <Button
                  key={b.id}
                  type="button"
                  variant="secondary"
                  onClick={() => {
                    pushHistory();
                    setBodyHtml((h) => `${h}${b.bodyHtml}`);
                    setBodyText((t) => `${t}${b.bodyText}`);
                  }}
                >
                  + {b.name}
                </Button>
              ))}
            </div>
            <Button
              type="button"
              variant="ghost"
              onClick={async () => {
                if (!bodyHtml.trim()) return;
                const name = window.prompt("Block name");
                if (!name?.trim()) return;
                await composerApi.blocks.create(workspaceId, {
                  name: name.trim(),
                  bodyHtml,
                  bodyText,
                });
                setBlocks(await composerApi.blocks.list(workspaceId));
              }}
            >
              Save body as block
            </Button>
          </div>
        </div>

        <div className="border-border space-y-2 rounded-lg border p-3">
          <div className="text-foreground text-sm font-medium">Preview &amp; test send</div>
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="Sample contact"
              value={previewContact}
              onChange={(e) => setPreviewContact(e.target.value)}
              className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
            >
              <option value="">Choose a real contact…</option>
              {contacts.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.email}
                </option>
              ))}
            </select>
            <Button
              type="button"
              variant="secondary"
              disabled={!previewContact || previewBusy}
              onClick={() => void refreshPreview()}
            >
              {previewBusy ? "Rendering…" : "Render preview"}
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={!previewContact || testBusy}
              onClick={() => void sendTest()}
            >
              <Send size={14} /> {testBusy ? "Sending…" : "Send test to me"}
            </Button>
          </div>
          {previewError && <Alert variant="error">{previewError}</Alert>}
          {preview && (
            <div className="space-y-2">
              <div className="text-muted-foreground text-sm">
                Subject: <span className="text-foreground">{preview.subject || "(empty)"}</span>
              </div>
              <div className="flex flex-wrap gap-1">
                {Object.entries(preview.report.resolved).map(([k, v]) => (
                  <Badge key={k} tone="success">
                    {k} = {v}
                  </Badge>
                ))}
                {preview.report.fellBack.map((k) => (
                  <Badge key={`fb-${k}`} tone="warning">
                    {k} → fallback
                  </Badge>
                ))}
                {preview.report.missing.map((k) => (
                  <Badge key={`ms-${k}`} tone="danger">
                    {k} missing
                  </Badge>
                ))}
              </div>
              <iframe
                title="Email preview"
                sandbox=""
                srcDoc={preview.html}
                className="border-border bg-surface h-64 w-full rounded-lg border"
              />
            </div>
          )}
        </div>

        {saveError && <Alert variant="error">{saveError}</Alert>}
      </div>
    </Dialog>
  );
}
