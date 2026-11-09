"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, Sparkles, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";
import { useWorkspaces } from "@/components/shell/workspace-context";
import { ApiError, foundingApi } from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong - is the API running?";
}

/** Founding 10,000 offer modal (Phase 14.2B.3). Shows once after verified
 * onboarding when the user is eligible and hasn't claimed/dismissed/snoozed.
 * The claim CTA invokes the real API. Respectful of reduced-motion and
 * keyboard/focus accessibility.
 */
export function FoundingOfferModal() {
  const { selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";
  const { toast } = useToast();

  const [visible, setVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [claimed, setClaimed] = useState(false);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [checked, setChecked] = useState(false);
  const [closed, setClosed] = useState(false);

  const evaluate = useCallback(async () => {
    if (!workspaceId) return;
    setChecked(true);
    try {
      const status = await foundingApi.status();
      if (status.status !== "active") return;
      const me = await foundingApi.me(workspaceId);
      if (me.claimed) {
        setClaimed(true);
        if (me.expiresAt) setExpiresAt(me.expiresAt);
        return;
      }
      const dismissedAt = window.localStorage.getItem("arc-founding-dismissed");
      const snoozedUntil = window.localStorage.getItem("arc-founding-snoozed");
      if (dismissedAt || (snoozedUntil && Date.now() < Number(snoozedUntil))) return;
      setVisible(true);
    } catch {
      // program unavailable — stay silent
    }
  }, [workspaceId]);

  useEffect(() => {
    if (!workspaceId) return;
    const t = setTimeout(() => Promise.resolve().then(evaluate), 1200); // don't interrupt onboarding
    return () => clearTimeout(t);
  }, [workspaceId, evaluate]);

  async function claimNow() {
    setSubmitting(true);
    setError(null);
    try {
      const res = await foundingApi.claim(workspaceId);
      if (res.claimed) {
        setClaimed(true);
        if (res.expiresAt) setExpiresAt(res.expiresAt);
        setVisible(true); // switch modal into confirmation state
        toast("success", "Founding Access confirmed.");
      } else {
        setError(res.message || "Claim failed — please retry.");
      }
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSubmitting(false);
    }
  }

  function dismiss() {
    window.localStorage.setItem("arc-founding-dismissed", String(Date.now()));
    setVisible(false);
  }

  function snooze() {
    window.localStorage.setItem("arc-founding-snoozed", String(Date.now() + 7 * 24 * 3600 * 1000));
    setVisible(false);
  }

  if (!checked || !visible || (claimed && closed)) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="founding-title"
      onKeyDown={(e) => e.key === "Escape" && snooze()}
    >
      <div className="border-border bg-surface motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-4 relative w-full max-w-md rounded-2xl border p-8 text-center shadow-2xl">
        <button
          aria-label="Close and remind me later"
          className="text-muted-foreground hover:text-foreground absolute top-4 right-4"
          onClick={snooze}
        >
          <X size={16} />
        </button>

        <div className="mb-4 flex items-center justify-center gap-2">
          <Sparkles className="text-primary" size={16} />
          <span className="text-primary text-xs font-semibold tracking-widest uppercase">
            Introducing the Founding 10,000
          </span>
        </div>

        {claimed ? (
          <>
            <div className="text-success mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-full bg-green-500/10">
              <Check size={22} />
            </div>
            <h2 id="founding-title" className="text-foreground text-xl font-semibold">
              You&apos;re part of the beginning.
            </h2>
            <p className="text-muted-foreground mt-2 text-sm">
              Welcome to the Arcadence Founding 10,000. Your complimentary access is confirmed.
            </p>
            <div className="border-border bg-surface-sunken mt-6 grid grid-cols-2 gap-4 rounded-xl border p-4 text-left">
              <div>
                <div className="text-muted-foreground text-xs">Membership</div>
                <div className="text-foreground text-sm font-semibold">Founding Member</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">Complimentary access</div>
                <div className="text-foreground text-sm font-semibold">
                  12 months{expiresAt ? ` — until ${new Date(expiresAt).toLocaleDateString()}` : ""}
                </div>
              </div>
            </div>
            <Button
              className="mt-6 w-full"
              onClick={() => {
                setClosed(true);
                setVisible(false);
              }}
            >
              Continue to Arcadence
            </Button>
          </>
        ) : (
          <>
            <h2
              id="founding-title"
              className="text-foreground text-2xl font-semibold tracking-tight"
            >
              Your first year is on us.
            </h2>
            <p className="text-primary mt-2 text-sm font-medium">
              Great conversations shouldn&apos;t begin with a paywall.
            </p>
            <p className="text-muted-foreground mt-4 text-sm leading-relaxed">
              We&apos;re inviting our first 10,000 verified users to experience Arcadence with 12
              months of complimentary Founding Access.
            </p>
            <div className="border-border mt-6 space-y-2 rounded-xl border p-4 text-left">
              {[
                "12 months of complimentary access",
                "No credit card required",
                "No automatic charges",
              ].map((b) => (
                <div key={b} className="text-foreground flex items-center gap-2 text-sm">
                  <Check size={14} className="text-success shrink-0" /> {b}
                </div>
              ))}
            </div>
            {error && <p className="text-danger mt-3 text-xs">{error}</p>}
            <Button className="mt-6 w-full" onClick={claimNow} disabled={submitting}>
              <Sparkles size={15} className="mr-1.5" />
              {submitting ? "Claiming…" : "Join the Founding 10,000"}
            </Button>
            <button
              className="text-muted-foreground hover:text-foreground mt-3 text-xs underline"
              onClick={snooze}
            >
              Maybe later
            </button>
            <p className="text-muted-foreground mt-4 text-xs">
              Subject to the{" "}
              <a className="underline" href="/app/developers" onClick={dismiss}>
                Founding Access terms and usage allowances
              </a>
              . No automatic billing at expiry.
            </p>
          </>
        )}
      </div>
    </div>
  );
}
