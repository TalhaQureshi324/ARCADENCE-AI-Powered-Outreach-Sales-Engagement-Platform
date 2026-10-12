"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { LayoutDashboard, Moon, Plus, Search } from "lucide-react";

import { useTheme } from "@/components/theme";
import { Dialog } from "@/components/ui/dialog";

interface Command {
  id: string;
  label: string;
  icon: typeof Search;
  run: () => void;
}

export function CommandPalette({
  open,
  onOpenChange,
  onCreateWorkspace,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreateWorkspace: () => void;
}) {
  const router = useRouter();
  const { theme, toggle } = useTheme();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);

  const commands: Command[] = [
    {
      id: "dashboard",
      label: "Go to Dashboard",
      icon: LayoutDashboard,
      run: () => router.push("/app"),
    },
    { id: "create-workspace", label: "Create workspace", icon: Plus, run: onCreateWorkspace },
    {
      id: "theme",
      label: theme === "dark" ? "Switch to light theme" : "Switch to dark theme",
      icon: Moon,
      run: toggle,
    },
  ];

  const filtered = commands.filter((c) => c.label.toLowerCase().includes(query.toLowerCase()));

  // Reset the query when the palette opens (in the open-change callback,
  // not an effect).
  function handleOpenChange(next: boolean) {
    if (next) {
      setQuery("");
      setActive(0);
    }
    onOpenChange(next);
  }

  function runCommand(command: Command | undefined) {
    if (!command) return;
    handleOpenChange(false);
    command.run();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange} title="Command palette">
      <div className="space-y-2">
        <div className="border-border bg-surface flex items-center gap-2 rounded-lg border px-3 py-2">
          <Search size={14} className="text-muted-foreground" />
          <input
            autoFocus
            role="combobox"
            aria-expanded="true"
            aria-controls="command-palette-list"
            aria-label="Search commands"
            placeholder="Type a command…"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
            }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setActive((a) => Math.min(a + 1, filtered.length - 1));
              } else if (e.key === "ArrowUp") {
                e.preventDefault();
                setActive((a) => Math.max(a - 1, 0));
              } else if (e.key === "Enter") {
                e.preventDefault();
                runCommand(filtered[active]);
              }
            }}
            className="text-foreground placeholder:text-muted-foreground/70 w-full bg-transparent text-sm outline-none"
          />
        </div>
        <ul id="command-palette-list" role="listbox" aria-label="Commands" className="space-y-1">
          {filtered.map((command, index) => (
            <li key={command.id}>
              <button
                type="button"
                role="option"
                aria-selected={index === active}
                onMouseEnter={() => setActive(index)}
                onClick={() => runCommand(command)}
                className={`flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm ${
                  index === active ? "bg-primary-soft text-primary" : "text-foreground"
                }`}
              >
                <command.icon size={14} />
                {command.label}
              </button>
            </li>
          ))}
          {filtered.length === 0 && (
            <li className="text-muted-foreground px-3 py-4 text-center text-sm">
              No matching commands.
            </li>
          )}
        </ul>
        <p className="text-muted-foreground pt-1 text-center text-xs">
          ↑↓ to navigate · Enter to run · Esc to close
        </p>
      </div>
    </Dialog>
  );
}
