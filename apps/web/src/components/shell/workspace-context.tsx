"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { createClient } from "@/lib/supabase/client";
import { ApiError, workspacesApi, type WorkspaceDto } from "@/lib/api/client";

interface WorkspaceContextValue {
  workspaces: WorkspaceDto[] | null; // null = loading
  error: string | null;
  selected: WorkspaceDto | null;
  select: (ws: WorkspaceDto) => void;
  reload: () => Promise<void>;
  onCreated: (ws: WorkspaceDto) => void;
}

const WorkspaceContext = createContext<WorkspaceContextValue>({
  workspaces: null,
  error: null,
  selected: null,
  select: () => {},
  reload: async () => {},
  onCreated: () => {},
});

const SELECTED_KEY = "arcadence-workspace";

export function WorkspaceProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<WorkspaceDto | null>(null);

  const reload = useCallback(async () => {
    try {
      const list = await workspacesApi.list();
      setWorkspaces(list);
      setError(null);
      setSelected((current) => {
        if (current && list.some((w) => w.id === current.id)) return current;
        const storedId = (() => {
          try {
            return localStorage.getItem(SELECTED_KEY);
          } catch {
            return null;
          }
        })();
        return list.find((w) => w.id === storedId) ?? list[0] ?? null;
      });
    } catch (e) {
      if (e instanceof ApiError && e.isUnauthorized) {
        // Session expired: hard redirect; middleware will send back after login.
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setWorkspaces([]);
      setError(e instanceof Error ? e.message : "Could not load workspaces.");
    }
  }, [router]);

  useEffect(() => {
    let cancelled = false;
    workspacesApi
      .list()
      .then((list) => {
        if (cancelled) return;
        setWorkspaces(list);
        setError(null);
        setSelected((current) => {
          if (current && list.some((w) => w.id === current.id)) return current;
          const storedId = (() => {
            try {
              return localStorage.getItem(SELECTED_KEY);
            } catch {
              return null;
            }
          })();
          return list.find((w) => w.id === storedId) ?? list[0] ?? null;
        });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.isUnauthorized) {
          void createClient()
            .auth.signOut()
            .then(() => {
              router.push("/login?expired=1");
            });
          return;
        }
        setWorkspaces([]);
        setError(e instanceof Error ? e.message : "Could not load workspaces.");
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const select = useCallback((ws: WorkspaceDto) => {
    setSelected(ws);
    try {
      localStorage.setItem(SELECTED_KEY, ws.id);
    } catch {}
  }, []);

  const onCreated = useCallback((ws: WorkspaceDto) => {
    setWorkspaces((current) => [...(current ?? []), ws]);
    setSelected(ws);
    try {
      localStorage.setItem(SELECTED_KEY, ws.id);
    } catch {}
    window.dispatchEvent(new CustomEvent("arcadence:workspace-changed"));
  }, []);

  return (
    <WorkspaceContext.Provider value={{ workspaces, error, selected, select, reload, onCreated }}>
      {children}
    </WorkspaceContext.Provider>
  );
}

export function useWorkspaces() {
  return useContext(WorkspaceContext);
}
