"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Building2,
  List as ListIcon,
  Plus,
  Search,
  Tags as TagsIcon,
  Trash2,
  Users,
  X,
} from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/components/ui/toast";
import { useWorkspaces } from "@/components/shell/workspace-context";
import {
  ApiError,
  companiesApi,
  contactsApi,
  customFieldsApi,
  leadListsApi,
  tagsApi,
  type CompanyDto,
  type ContactDto,
  type ContactPage,
  type CustomFieldDto,
  type LeadListDto,
  type TagDto,
} from "@/lib/api/client";

type LoadState<T> =
  { kind: "loading" } | { kind: "loaded"; data: T } | { kind: "error"; message: string };

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function isUnauthorized(e: unknown): boolean {
  return e instanceof ApiError && e.isUnauthorized;
}

function canEdit(role: string): boolean {
  return role !== "viewer";
}

const STATE_META: Record<string, { label: string; tone: BadgeTone }> = {
  active: { label: "Active", tone: "success" },
  unsubscribed: { label: "Unsubscribed", tone: "warning" },
  complained: { label: "Complained", tone: "danger" },
  bounced: { label: "Bounced", tone: "danger" },
};

const VERIFICATION_META: Record<string, { label: string; tone: BadgeTone }> = {
  verified: { label: "Verified", tone: "success" },
  invalid: { label: "Invalid", tone: "danger" },
  risky: { label: "Risky", tone: "warning" },
  unknown: { label: "Unknown", tone: "neutral" },
  pending: { label: "Pending", tone: "neutral" },
};

export function LeadsContent() {
  const { workspaces, selected } = useWorkspaces();

  if (workspaces === null) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-64" />
      </div>
    );
  }

  if (workspaces.length === 0 || !selected) {
    return (
      <EmptyState
        icon={<Users size={22} />}
        title="No workspace selected"
        description="Create a workspace from the selector above to manage leads."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader
          eyebrow={`Workspace · /${selected.slug}`}
          title="Leads"
          subtitle={`Contacts, companies, lists and tags for ${selected.name}.`}
        />
      </Card>
      <Tabs defaultValue="contacts">
        <TabsList>
          <TabsTrigger value="contacts">Contacts</TabsTrigger>
          <TabsTrigger value="companies">Companies</TabsTrigger>
          <TabsTrigger value="lists">Lists</TabsTrigger>
          <TabsTrigger value="tax">Tags &amp; Fields</TabsTrigger>
        </TabsList>
        <TabsContent value="contacts">
          <ContactsTab workspaceId={selected.id} myRole={selected.role} />
        </TabsContent>
        <TabsContent value="companies">
          <CompaniesTab workspaceId={selected.id} myRole={selected.role} />
        </TabsContent>
        <TabsContent value="lists">
          <ListsTab workspaceId={selected.id} myRole={selected.role} />
        </TabsContent>
        <TabsContent value="tax">
          <TagsFieldsTab workspaceId={selected.id} myRole={selected.role} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

// -- Contacts -----------------------------------------------------------------

function ContactsTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const router = useRouter();
  const { toast } = useToast();
  const editable = canEdit(myRole);
  const [page, setPage] = useState<LoadState<ContactPage>>({ kind: "loading" });
  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [sort, setSort] = useState("created_desc");
  const [cursorStack, setCursorStack] = useState<string[]>([]);
  const [currentCursor, setCurrentCursor] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(
    (cursor: string | null, q: string, sortKey: string) => {
      contactsApi
        .list(workspaceId, {
          q: q || undefined,
          sort: sortKey,
          limit: 25,
          cursor: cursor || undefined,
        })
        .then((data) => setPage({ kind: "loaded", data }))
        .catch((e) => setPage({ kind: "error", message: errorMessage(e) }));
    },
    [workspaceId],
  );

  useEffect(() => {
    let cancelled = false;
    contactsApi
      .list(workspaceId, {
        q: appliedQuery || undefined,
        sort,
        limit: 25,
        cursor: currentCursor || undefined,
      })
      .then((data) => {
        if (!cancelled) setPage({ kind: "loaded", data });
      })
      .catch((e) => {
        if (!cancelled) setPage({ kind: "error", message: errorMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId, appliedQuery, sort, currentCursor]);

  async function remove(contactId: string) {
    setBusyId(contactId);
    setError(null);
    try {
      await contactsApi.remove(workspaceId, contactId);
      toast("success", "Contact deleted.");
      load(currentCursor, appliedQuery, sort);
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setError(errorMessage(e));
    } finally {
      setBusyId(null);
    }
  }

  const data = page.kind === "loaded" ? page.data : null;

  return (
    <Card>
      <CardHeader
        eyebrow="Lead management"
        title="Contacts"
        subtitle={
          editable
            ? "Create, search and manage contacts. Duplicates by email are rejected."
            : "Read-only — editing requires the member role or above."
        }
      />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <form
          className="flex max-w-md flex-1 items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            setAppliedQuery(query);
            setCurrentCursor(null);
            setCursorStack([]);
            setPage({ kind: "loading" });
          }}
        >
          <Input
            aria-label="Search contacts"
            placeholder="Search email or name…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <Button type="submit" variant="secondary">
            <Search size={14} /> Search
          </Button>
        </form>
        <select
          aria-label="Sort contacts"
          value={sort}
          onChange={(e) => {
            setSort(e.target.value);
            setCurrentCursor(null);
            setCursorStack([]);
          }}
          className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
        >
          <option value="created_desc">Newest first</option>
          <option value="created_asc">Oldest first</option>
          <option value="email_asc">Email A→Z</option>
          <option value="email_desc">Email Z→A</option>
        </select>
        {editable && (
          <Button onClick={() => setCreateOpen(true)}>
            <Plus size={14} /> New contact
          </Button>
        )}
      </div>

      {page.kind === "loading" && <Skeleton className="h-40" />}
      {page.kind === "error" && <Alert variant="error">{page.message}</Alert>}
      {error && <Alert variant="error">{error}</Alert>}
      {data && data.items.length === 0 && (
        <EmptyState
          icon={<Users size={22} />}
          title="No contacts yet"
          description={
            appliedQuery
              ? `No contacts match “${appliedQuery}”.`
              : "Create your first contact, or wait for the CSV import (arriving in the next subphase)."
          }
          action={
            editable ? (
              <Button onClick={() => setCreateOpen(true)}>
                <Plus size={14} /> New contact
              </Button>
            ) : undefined
          }
        />
      )}
      {data && data.items.length > 0 && (
        <>
          <Table>
            <THead>
              <TR>
                <TH>Email</TH>
                <TH>Name</TH>
                <TH>Company</TH>
                <TH>Job title</TH>
                <TH>State</TH>
                <TH>Verification</TH>
                <TH>
                  <span className="sr-only">Actions</span>
                </TH>
              </TR>
            </THead>
            <TBody>
              {data.items.map((c) => {
                const state = STATE_META[c.state] ?? {
                  label: c.state,
                  tone: "neutral" as BadgeTone,
                };
                const ver = VERIFICATION_META[c.verificationStatus] ?? {
                  label: c.verificationStatus,
                  tone: "neutral" as BadgeTone,
                };
                const name = [c.firstName, c.lastName].filter(Boolean).join(" ");
                return (
                  <TR key={c.id}>
                    <TD>
                      <div className="text-foreground text-sm font-medium">
                        {c.emailDisplay || c.email}
                      </div>
                      {c.emailDisplay && (
                        <div className="text-muted-foreground text-xs">{c.email}</div>
                      )}
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">{name || "—"}</span>
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">{c.companyName ?? "—"}</span>
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">{c.jobTitle ?? "—"}</span>
                    </TD>
                    <TD>
                      <Badge tone={state.tone}>{state.label}</Badge>
                    </TD>
                    <TD>
                      <Badge tone={ver.tone}>{ver.label}</Badge>
                    </TD>
                    <TD>
                      {editable && (
                        <div className="flex items-center justify-end gap-1">
                          {busyId === c.id ? (
                            <span className="text-muted-foreground text-xs">Deleting…</span>
                          ) : (
                            <Button
                              variant="ghost"
                              aria-label={`Delete ${c.email}`}
                              onClick={() => remove(c.id)}
                            >
                              <Trash2 size={14} />
                            </Button>
                          )}
                        </div>
                      )}
                    </TD>
                  </TR>
                );
              })}
            </TBody>
          </Table>
          <div className="text-muted-foreground mt-3 flex items-center justify-between text-sm">
            <span>{data.total} contacts total</span>
            <div className="flex gap-2">
              <Button
                variant="ghost"
                disabled={cursorStack.length === 0}
                onClick={() => {
                  const stack = [...cursorStack];
                  const prev = stack.pop() ?? "";
                  setCursorStack(stack);
                  setCurrentCursor(prev || null);
                }}
              >
                Previous
              </Button>
              <Button
                variant="ghost"
                disabled={!data.nextCursor}
                onClick={() => {
                  if (data.nextCursor) {
                    if (currentCursor) setCursorStack((s) => [...s, currentCursor]);
                    setCurrentCursor(data.nextCursor);
                  }
                }}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}

      <ContactCreateDialog
        open={createOpen}
        workspaceId={workspaceId}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setPage({ kind: "loading" });
          load(currentCursor, appliedQuery, sort);
        }}
      />
    </Card>
  );
}

function ContactCreateDialog({
  open,
  workspaceId,
  onClose,
  onCreated,
}: {
  open: boolean;
  workspaceId: string;
  onClose: () => void;
  onCreated: () => void;
}) {
  const router = useRouter();
  const { toast } = useToast();
  const [form, setForm] = useState({
    email: "",
    firstName: "",
    lastName: "",
    jobTitle: "",
    phone: "",
    region: "",
    timezone: "",
    website: "",
    notes: "",
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function set(field: keyof typeof form, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      await contactsApi.create(workspaceId, {
        email: form.email.trim(),
        firstName: form.firstName.trim() || undefined,
        lastName: form.lastName.trim() || undefined,
        jobTitle: form.jobTitle.trim() || undefined,
        phone: form.phone.trim() || undefined,
        region: form.region.trim() || undefined,
        timezone: form.timezone.trim() || undefined,
        website: form.website.trim() || undefined,
        notes: form.notes.trim() || undefined,
      });
      toast("success", "Contact created.");
      setForm({
        email: "",
        firstName: "",
        lastName: "",
        jobTitle: "",
        phone: "",
        region: "",
        timezone: "",
        website: "",
        notes: "",
      });
      onClose();
      onCreated();
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setError(errorMessage(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title="New contact"
      description="Email addresses are deduplicated per workspace (case-insensitive)."
    >
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Input
          aria-label="Email"
          type="email"
          required
          value={form.email}
          onChange={(e) => set("email", e.target.value)}
        />
        <div className="grid grid-cols-2 gap-2">
          <Input
            aria-label="First name"
            value={form.firstName}
            onChange={(e) => set("firstName", e.target.value)}
          />
          <Input
            aria-label="Last name"
            value={form.lastName}
            onChange={(e) => set("lastName", e.target.value)}
          />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Input
            aria-label="Job title"
            value={form.jobTitle}
            onChange={(e) => set("jobTitle", e.target.value)}
          />
          <Input
            aria-label="Phone"
            value={form.phone}
            onChange={(e) => set("phone", e.target.value)}
          />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Input
            aria-label="Region"
            value={form.region}
            onChange={(e) => set("region", e.target.value)}
          />
          <Input
            aria-label="Timezone"
            value={form.timezone}
            onChange={(e) => set("timezone", e.target.value)}
          />
        </div>
        <Input
          aria-label="Website"
          value={form.website}
          onChange={(e) => set("website", e.target.value)}
        />
        {error && <Alert variant="error">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={submitting}>
            Create contact
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

// -- Companies ------------------------------------------------------------------

function CompaniesTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const editable = canEdit(myRole);
  const { toast } = useToast();
  const [companies, setCompanies] = useState<LoadState<CompanyDto[]>>({ kind: "loading" });
  const [name, setName] = useState("");
  const [domain, setDomain] = useState("");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    companiesApi
      .list(workspaceId)
      .then((data) => setCompanies({ kind: "loaded", data }))
      .catch((e) => setCompanies({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  useEffect(() => {
    reload();
  }, [reload]);

  async function create() {
    setCreating(true);
    setError(null);
    try {
      await companiesApi.create(workspaceId, {
        name: name.trim(),
        domain: domain.trim() || undefined,
      });
      setName("");
      setDomain("");
      toast("success", "Company created.");
      reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setCreating(false);
    }
  }

  async function remove(companyId: string) {
    try {
      await companiesApi.remove(workspaceId, companyId);
      toast("success", "Company deleted.");
      reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Lead management"
        title="Companies"
        subtitle="Group contacts by organization."
      />
      {editable && (
        <form
          className="mb-4 flex max-w-xl items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void create();
          }}
        >
          <Input
            aria-label="Company name"
            placeholder="Acme Inc."
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <Input
            aria-label="Domain"
            placeholder="acme.com"
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
          />
          <Button type="submit" loading={creating} disabled={!name.trim()}>
            <Plus size={14} /> Add
          </Button>
        </form>
      )}
      {error && <Alert variant="error">{error}</Alert>}
      {companies.kind === "loading" && <Skeleton className="h-32" />}
      {companies.kind === "error" && <Alert variant="error">{companies.message}</Alert>}
      {companies.kind === "loaded" &&
        (companies.data.length === 0 ? (
          <EmptyState
            icon={<Building2 size={22} />}
            title="No companies yet"
            description="Create a company to group its contacts."
          />
        ) : (
          <Table>
            <THead>
              <TR>
                <TH>Name</TH>
                <TH>Domain</TH>
                <TH>Contacts</TH>
                {editable && (
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                )}
              </TR>
            </THead>
            <TBody>
              {companies.data.map((c) => (
                <TR key={c.id}>
                  <TD>
                    <span className="text-foreground text-sm font-medium">{c.name}</span>
                  </TD>
                  <TD>
                    <span className="text-muted-foreground text-sm">{c.domain ?? "—"}</span>
                  </TD>
                  <TD>
                    <Badge tone="neutral">{c.contactCount}</Badge>
                  </TD>
                  {editable && (
                    <TD>
                      <div className="flex justify-end">
                        <Button
                          variant="ghost"
                          aria-label={`Delete ${c.name}`}
                          onClick={() => remove(c.id)}
                        >
                          <Trash2 size={14} />
                        </Button>
                      </div>
                    </TD>
                  )}
                </TR>
              ))}
            </TBody>
          </Table>
        ))}
    </Card>
  );
}

// -- Lists ----------------------------------------------------------------------

function ListsTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const editable = canEdit(myRole);
  const { toast } = useToast();
  const [lists, setLists] = useState<LoadState<LeadListDto[]>>({ kind: "loading" });
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openList, setOpenList] = useState<LeadListDto | null>(null);
  const [members, setMembers] = useState<LoadState<ContactPage>>({ kind: "loading" });
  const [available, setAvailable] = useState<LoadState<ContactPage>>({ kind: "loading" });
  const [selectedIds, setSelectedIds] = useState<string[]>([]);

  const reload = useCallback(() => {
    leadListsApi
      .list(workspaceId)
      .then((data) => setLists({ kind: "loaded", data }))
      .catch((e) => setLists({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  useEffect(() => {
    reload();
  }, [reload]);

  useEffect(() => {
    if (!openList) return;
    contactsApi
      .list(workspaceId, { listId: openList.id, limit: 50 })
      .then((data) => setMembers({ kind: "loaded", data }))
      .catch((e) => setMembers({ kind: "error", message: errorMessage(e) }));
  }, [openList, workspaceId]);

  useEffect(() => {
    if (!openList) return;
    contactsApi
      .list(workspaceId, { limit: 50 })
      .then((data) => setAvailable({ kind: "loaded", data }))
      .catch(() =>
        setAvailable({ kind: "loaded", data: { items: [], nextCursor: null, total: 0 } }),
      );
  }, [openList, workspaceId]);

  async function create() {
    setCreating(true);
    setError(null);
    try {
      await leadListsApi.create(workspaceId, {
        name: name.trim(),
        description: description.trim() || undefined,
      });
      setName("");
      setDescription("");
      toast("success", "List created.");
      reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setCreating(false);
    }
  }

  async function addMembers() {
    if (!openList || selectedIds.length === 0) return;
    try {
      const result = await leadListsApi.addMembers(workspaceId, openList.id, selectedIds);
      toast("success", `${result.added} contact(s) added to ${openList.name}.`);
      setSelectedIds([]);
      reload();
      setOpenList({ ...openList });
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function removeMember(contactId: string) {
    if (!openList) return;
    try {
      await leadListsApi.removeMember(workspaceId, openList.id, contactId);
      setOpenList({ ...openList });
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  const memberIds = new Set(members.kind === "loaded" ? members.data.items.map((m) => m.id) : []);
  const addable =
    available.kind === "loaded" ? available.data.items.filter((c) => !memberIds.has(c.id)) : [];

  return (
    <>
      <Card>
        <CardHeader
          eyebrow="Lead management"
          title="Lead lists"
          subtitle="Static lists group contacts for campaigns (campaigns arrive in Phase 4)."
        />
        {editable && (
          <form
            className="mb-4 flex max-w-xl items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void create();
            }}
          >
            <Input
              aria-label="List name"
              placeholder="Q3 outreach — SaaS founders"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <Input
              aria-label="List description"
              placeholder="Description (optional)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
            <Button type="submit" loading={creating} disabled={!name.trim()}>
              <Plus size={14} /> Add
            </Button>
          </form>
        )}
        {error && <Alert variant="error">{error}</Alert>}
        {lists.kind === "loading" && <Skeleton className="h-32" />}
        {lists.kind === "error" && <Alert variant="error">{lists.message}</Alert>}
        {lists.kind === "loaded" &&
          (lists.data.length === 0 ? (
            <EmptyState
              icon={<ListIcon size={22} />}
              title="No lists yet"
              description="Create a list to start grouping contacts."
            />
          ) : (
            <Table>
              <THead>
                <TR>
                  <TH>Name</TH>
                  <TH>Description</TH>
                  <TH>Members</TH>
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                </TR>
              </THead>
              <TBody>
                {lists.data.map((l) => (
                  <TR key={l.id}>
                    <TD>
                      <span className="text-foreground text-sm font-medium">{l.name}</span>
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">{l.description ?? "—"}</span>
                    </TD>
                    <TD>
                      <Badge tone="neutral">{l.memberCount}</Badge>
                    </TD>
                    <TD>
                      <div className="flex items-center justify-end gap-1">
                        {editable && (
                          <Button
                            variant="ghost"
                            onClick={() => {
                              setOpenList(l);
                              setSelectedIds([]);
                            }}
                          >
                            Members
                          </Button>
                        )}
                        {editable && (
                          <Button
                            variant="ghost"
                            aria-label={`Delete ${l.name}`}
                            onClick={async () => {
                              await leadListsApi.remove(workspaceId, l.id);
                              toast("success", "List deleted.");
                              reload();
                            }}
                          >
                            <Trash2 size={14} />
                          </Button>
                        )}
                      </div>
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          ))}
      </Card>

      <Dialog
        open={openList !== null}
        onOpenChange={(next) => {
          if (!next) setOpenList(null);
        }}
        title={openList ? `Members of ${openList.name}` : ""}
        description="Add contacts to this list or remove existing members."
      >
        <div className="max-h-96 space-y-4 overflow-y-auto">
          {editable && addable.length > 0 && (
            <div className="space-y-2">
              <div className="text-foreground text-sm font-semibold">Add contacts</div>
              {addable.map((c) => (
                <label key={c.id} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={selectedIds.includes(c.id)}
                    onChange={(e) =>
                      setSelectedIds((current) =>
                        e.target.checked ? [...current, c.id] : current.filter((id) => id !== c.id),
                      )
                    }
                  />
                  {c.emailDisplay || c.email}
                </label>
              ))}
              <Button onClick={addMembers} disabled={selectedIds.length === 0}>
                <Plus size={14} /> Add {selectedIds.length} to list
              </Button>
            </div>
          )}
          <div className="space-y-2">
            <div className="text-foreground text-sm font-semibold">Current members</div>
            {members.kind === "loading" && <Skeleton className="h-16" />}
            {members.kind === "loaded" && members.data.items.length === 0 && (
              <p className="text-muted-foreground text-sm">No members yet.</p>
            )}
            {members.kind === "loaded" &&
              members.data.items.map((m) => (
                <div key={m.id} className="flex items-center justify-between gap-2 text-sm">
                  <span>{m.emailDisplay || m.email}</span>
                  {editable && (
                    <Button
                      variant="ghost"
                      aria-label={`Remove ${m.email} from list`}
                      onClick={() => removeMember(m.id)}
                    >
                      <X size={14} />
                    </Button>
                  )}
                </div>
              ))}
          </div>
        </div>
      </Dialog>
    </>
  );
}

// -- Tags & custom fields ----------------------------------------------------------

function TagsFieldsTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const editable = canEdit(myRole);
  const { toast } = useToast();
  const [tags, setTags] = useState<LoadState<TagDto[]>>({ kind: "loading" });
  const [fields, setFields] = useState<LoadState<CustomFieldDto[]>>({ kind: "loading" });
  const [tagName, setTagName] = useState("");
  const [fieldName, setFieldName] = useState("");
  const [fieldType, setFieldType] = useState("text");
  const [error, setError] = useState<string | null>(null);

  const reloadTags = useCallback(() => {
    tagsApi
      .list(workspaceId)
      .then((data) => setTags({ kind: "loaded", data }))
      .catch((e) => setTags({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  const reloadFields = useCallback(() => {
    customFieldsApi
      .list(workspaceId)
      .then((data) => setFields({ kind: "loaded", data }))
      .catch((e) => setFields({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  useEffect(() => {
    reloadTags();
    reloadFields();
  }, [reloadTags, reloadFields]);

  async function createTag() {
    setError(null);
    try {
      await tagsApi.create(workspaceId, { name: tagName.trim() });
      setTagName("");
      toast("success", "Tag created.");
      reloadTags();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function createField() {
    setError(null);
    try {
      await customFieldsApi.create(workspaceId, {
        name: fieldName.trim(),
        fieldType,
      });
      setFieldName("");
      toast("success", "Custom field created.");
      reloadFields();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Lead management"
        title="Tags & custom fields"
        subtitle="Tags group contacts; custom fields define the user-defined variables on contacts."
      />
      {error && <Alert variant="error">{error}</Alert>}
      <div className="grid gap-8 md:grid-cols-2">
        <section aria-label="Tags">
          <div className="text-foreground mb-2 flex items-center gap-2 text-sm font-semibold">
            <TagsIcon size={14} /> Tags
          </div>
          {editable && (
            <form
              className="mb-3 flex items-center gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                void createTag();
              }}
            >
              <Input
                aria-label="New tag name"
                placeholder="e.g. warm-lead"
                required
                value={tagName}
                onChange={(e) => setTagName(e.target.value)}
              />
              <Button type="submit" disabled={!tagName.trim()}>
                <Plus size={14} /> Add
              </Button>
            </form>
          )}
          {tags.kind === "loading" && <Skeleton className="h-20" />}
          {tags.kind === "loaded" &&
            (tags.data.length === 0 ? (
              <p className="text-muted-foreground text-sm">No tags yet.</p>
            ) : (
              <ul className="divide-border divide-y">
                {tags.data.map((t) => (
                  <li key={t.id} className="flex items-center gap-2 py-2 text-sm">
                    <Badge tone="neutral">{t.name}</Badge>
                    <span className="text-muted-foreground text-xs">{t.contactCount} contacts</span>
                    <span className="flex-1" />
                    {editable && (
                      <Button
                        variant="ghost"
                        aria-label={`Delete tag ${t.name}`}
                        onClick={async () => {
                          await tagsApi.remove(workspaceId, t.id);
                          reloadTags();
                        }}
                      >
                        <Trash2 size={14} />
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            ))}
        </section>
        <section aria-label="Custom fields">
          <div className="text-foreground mb-2 text-sm font-semibold">Custom fields</div>
          {editable && (
            <form
              className="mb-3 flex items-center gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                void createField();
              }}
            >
              <Input
                aria-label="New field name"
                placeholder="e.g. company_size"
                required
                value={fieldName}
                onChange={(e) => setFieldName(e.target.value)}
              />
              <select
                aria-label="Field type"
                value={fieldType}
                onChange={(e) => setFieldType(e.target.value)}
                className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
              >
                <option value="text">text</option>
                <option value="number">number</option>
                <option value="date">date</option>
                <option value="boolean">boolean</option>
                <option value="url">url</option>
              </select>
              <Button type="submit" disabled={!fieldName.trim()}>
                <Plus size={14} /> Add
              </Button>
            </form>
          )}
          {fields.kind === "loading" && <Skeleton className="h-20" />}
          {fields.kind === "loaded" &&
            (fields.data.length === 0 ? (
              <p className="text-muted-foreground text-sm">No custom fields yet.</p>
            ) : (
              <ul className="divide-border divide-y">
                {fields.data.map((f) => (
                  <li key={f.id} className="flex items-center gap-2 py-2 text-sm">
                    <span className="text-foreground font-medium">{f.name}</span>
                    <Badge tone="neutral">{f.fieldType}</Badge>
                    <span className="flex-1" />
                    {editable && (
                      <Button
                        variant="ghost"
                        aria-label={`Delete field ${f.name}`}
                        onClick={async () => {
                          await customFieldsApi.remove(workspaceId, f.id);
                          toast("success", "Field deleted.");
                          reloadFields();
                        }}
                      >
                        <Trash2 size={14} />
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            ))}
        </section>
      </div>
    </Card>
  );
}
