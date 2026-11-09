"use client";

import { useCallback, useEffect, useState } from "react";
import { BadgeCheck, Sparkles } from "lucide-react";

import { foundingApi } from "@/lib/api/client";

/** Founding Access membership card (Phase 14.2B.4) — shown in account
 * settings for confirmed founding members; truthful free-launch status for
 * everyone else. Includes the explicit no-auto-billing policy and a link to
 * reopen the offer details when eligible.
 */
export function FoundingMembershipCard() {
  const [membership, setMembership] = useState<{
    claimed: boolean;
    membershipState?: string;
    claimPosition?: number;
    activatedAt?: string;
    expiresAt?: string;
    autoBilling: boolean;
  } | null>(null);

  const load = useCallback(() => {
    foundingApi
      .me("")
      .then(setMembership)
      .catch(() => setMembership(null));
  }, []);

  useEffect(() => {
    Promise.resolve().then(load);
  }, [load]);

  if (membership === null) return null;

  if (!membership.claimed) {
    return (
      <div className="border-border bg-surface rounded-xl border p-5">
        <div className="flex items-center gap-2">
          <Sparkles size={16} className="text-primary" />
          <h3 className="text-foreground text-sm font-semibold">Founding Access</h3>
        </div>
        <p className="text-muted-foreground mt-2 text-sm">
          You are on the <strong>free launch</strong> plan — full access to the current feature set.
          Paid plans are not offered during the free launch period.
        </p>
        <a className="text-primary mt-3 inline-block text-xs underline" href="/app/aistudio">
          View the Founding 10,000 program
        </a>
      </div>
    );
  }

  return (
    <div className="border-primary/30 from-primary-soft to-surface rounded-xl border bg-gradient-to-br p-5">
      <div className="flex items-center gap-2">
        <BadgeCheck size={18} className="text-primary" />
        <h3 className="text-foreground text-sm font-semibold">Founding Access</h3>
        <span className="bg-primary-soft text-primary ml-auto rounded-full px-2.5 py-0.5 text-xs font-semibold">
          Founding Member
        </span>
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
        <div>
          <dt className="text-muted-foreground text-xs">Member since</dt>
          <dd className="text-foreground">
            {membership.activatedAt ? new Date(membership.activatedAt).toLocaleDateString() : "—"}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground text-xs">Complimentary until</dt>
          <dd className="text-foreground">
            {membership.expiresAt ? new Date(membership.expiresAt).toLocaleDateString() : "—"}
          </dd>
        </div>
      </dl>
      <p className="text-muted-foreground mt-3 text-xs">
        12 months of complimentary access. <strong>No automatic charges</strong> at expiry — no
        billing applies to founding membership.
      </p>
    </div>
  );
}
