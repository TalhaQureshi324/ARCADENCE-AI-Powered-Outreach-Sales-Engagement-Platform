"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { BarChart3, Bell, Building2, Command, Inbox, LayoutDashboard, LogOut, Mail, Moon, Plus, Search, Settings, Shield, Sun, Users } from "lucide-react";

import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useTheme } from "@/components/theme";
import { CommandPalette } from "@/components/shell/command-palette";
import { CreateWorkspaceDialog } from "@/components/shell/create-workspace-dialog";
import { useWorkspaces } from "@/components/shell/workspace-context";
import type { WorkspaceDto } from "@/lib/api/client";

interface ShellUser {
  email: string;
  displayName: string;
}

const NAV_ITEMS: {
  href: string;
  label: string;
  icon: React.ComponentType<{ size?: number }>;
  active?: boolean;
  soon?: boolean;
}[] = [
  { href: "/app", label: "Dashboard", icon: LayoutDashboard, active: true },
  { href: "/app/inbox", label: "Inbox", icon: Inbox },
  { href: "/app/analytics", label: "Analytics", icon: BarChart3 },
  { href: "/app/deliverability", label: "Deliverability", icon: Shield },
  { href: "/app/campaigns", label: "Campaigns", icon: BarChart3 },
  { href: "/app/leads", label: "Leads", icon: Users },
  { href: "/app/email-accounts", label: "Email Accounts", icon: Mail },
  { href: "/app/settings", label: "Settings", icon: Settings },
];

export function AppShell({ user, children }: { user: ShellUser; children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const { theme, toggle } = useTheme();
  const { workspaces, selected, select, onCreated } = useWorkspaces();
  const [createOpen, setCreateOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    const onCreateRequest = () => setCreateOpen(true);
    window.addEventListener("keydown", onKey);
    window.addEventListener("arcadence:create-workspace", onCreateRequest);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("arcadence:create-workspace", onCreateRequest);
    };
  }, []);

  function onWorkspaceCreated(workspace: WorkspaceDto) {
    onCreated(workspace);
    setCreateOpen(false);
    router.refresh();
  }

  const nav = (
    <nav aria-label="Primary" className="flex flex-1 flex-col gap-1 px-3">
      {/* Settings lives under /app/settings; active-state matching below covers it */}
      {NAV_ITEMS.map((item) =>
        item.soon ? (
          <span
            key={item.href}
            aria-disabled="true"
            className="text-muted-foreground/60 flex cursor-not-allowed items-center gap-3 rounded-lg px-3 py-2 text-sm"
            title="Arrives in a later subphase"
          >
            <item.icon size={16} />
            {item.label}
            <Badge tone="neutral">soon</Badge>
          </span>
        ) : (
          <Link
            key={item.href}
            href={item.href}
            aria-current={
              pathname === item.href || (item.href !== "/app" && pathname.startsWith(item.href))
                ? "page"
                : undefined
            }
            className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
              pathname === item.href || (item.href !== "/app" && pathname.startsWith(item.href))
                ? "bg-primary-soft text-primary"
                : "text-muted-foreground hover:bg-surface-sunken hover:text-foreground"
            }`}
          >
            <item.icon size={16} />
            {item.label}
          </Link>
        ),
      )}
    </nav>
  );

  const brand = (
    <Link href="/app" className="flex items-center gap-2 px-5 py-5">
      <span className="bg-primary text-primary-foreground flex h-7 w-7 items-center justify-center rounded-lg">
        <Command size={14} />
      </span>
      <span className="text-foreground text-base font-semibold tracking-tight">Arcadence</span>
    </Link>
  );

  return (
    <div className="bg-background flex min-h-screen">
      {/* Sidebar — desktop */}
      <aside className="border-border bg-surface sticky top-0 hidden h-screen w-64 shrink-0 flex-col border-r md:flex">
        {brand}
        <div className="px-3 pb-3">
          <WorkspaceSelector
            workspaces={workspaces}
            selected={selected}
            onSelect={select}
            onCreate={() => setCreateOpen(true)}
          />
        </div>
        {nav}
        <div className="text-muted-foreground/70 px-3 pt-2 pb-4 text-xs">Phase 4 · campaigns</div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Topbar */}
        <header className="border-border bg-surface sticky top-0 z-30 flex h-14 items-center justify-between gap-3 border-b px-4 md:px-6">
          <div className="flex min-w-0 items-center gap-3">
            <span className="md:hidden">{brand}</span>
            <button
              type="button"
              onClick={() => setPaletteOpen(true)}
              className="border-border bg-surface text-muted-foreground hover:border-border-strong hidden items-center gap-2 rounded-lg border px-3 py-1.5 text-sm md:flex"
            >
              <Search size={14} />
              Search…
              <kbd className="border-border bg-surface-sunken rounded border px-1.5 text-xs">
                ⌘K
              </kbd>
            </button>
          </div>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={toggle}
              aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
              className="text-muted-foreground hover:bg-surface-sunken hover:text-foreground rounded-lg p-2"
            >
              {theme === "dark" ? <Sun size={16} /> : <Moon size={16} />}
            </button>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  aria-label="Notifications"
                  className="text-muted-foreground hover:bg-surface-sunken hover:text-foreground relative rounded-lg p-2"
                >
                  <Bell size={16} />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuLabel>Notifications</DropdownMenuLabel>
                <p className="text-muted-foreground px-3 py-4 text-center text-sm">
                  No notifications yet.
                </p>
              </DropdownMenuContent>
            </DropdownMenu>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  aria-label="Account menu"
                  className="hover:bg-surface-sunken flex items-center gap-2 rounded-lg p-1.5"
                >
                  <Avatar name={user.displayName || user.email} size="sm" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuLabel>{user.email}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => router.push("/onboarding")}>
                  <Settings size={14} /> Profile
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={toggle}>
                  {theme === "dark" ? <Sun size={14} /> : <Moon size={14} />} Switch theme
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  danger
                  onSelect={async () => {
                    const { createClient } = await import("@/lib/supabase/client");
                    await createClient().auth.signOut();
                    router.push("/login?signedOut=1");
                    router.refresh();
                  }}
                >
                  <LogOut size={14} /> Sign out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>

        {/* Mobile nav */}
        <nav
          aria-label="Primary mobile"
          className="border-border bg-surface flex gap-1 overflow-x-auto border-b px-3 py-2 md:hidden"
        >
          {NAV_ITEMS.filter((i) => !i.soon).map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="text-muted-foreground hover:bg-surface-sunken hover:text-foreground flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm font-medium whitespace-nowrap"
            >
              <item.icon size={14} />
              {item.label}
            </Link>
          ))}
        </nav>

        <main className="flex-1 p-4 md:p-6">{children}</main>
      </div>

      <CreateWorkspaceDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={onWorkspaceCreated}
      />
      <CommandPalette
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
        onCreateWorkspace={() => setCreateOpen(true)}
      />
    </div>
  );
}

function WorkspaceSelector({
  workspaces,
  selected,
  onSelect,
  onCreate,
}: {
  workspaces: WorkspaceDto[] | null;
  selected: WorkspaceDto | null;
  onSelect: (ws: WorkspaceDto) => void;
  onCreate: () => void;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label="Workspace selector"
          className="border-border bg-surface-sunken text-foreground hover:border-border-strong flex w-full items-center gap-2 rounded-lg border px-3 py-2 text-sm"
        >
          <Building2 size={14} className="text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate text-left">
            {selected ? selected.name : workspaces === null ? "Loading…" : "Choose workspace"}
          </span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuLabel>Workspaces</DropdownMenuLabel>
        {workspaces?.length ? (
          workspaces.map((ws) => (
            <DropdownMenuItem key={ws.id} onSelect={() => onSelect(ws)}>
              <span className="min-w-0 flex-1 truncate">{ws.name}</span>
              <Badge tone={ws.role === "owner" ? "primary" : "neutral"}>{ws.role}</Badge>
            </DropdownMenuItem>
          ))
        ) : (
          <p className="text-muted-foreground px-3 py-3 text-sm">No workspaces yet.</p>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onCreate}>
          <Plus size={14} /> Create workspace
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
