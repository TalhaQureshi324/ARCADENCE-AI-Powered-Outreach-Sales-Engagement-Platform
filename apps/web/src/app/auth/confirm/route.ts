import { NextResponse, type NextRequest } from "next/server";

import { sanitizeRedirect } from "@/lib/auth/redirects";
import { isOtpType } from "@/lib/auth/validation";
import { createClient } from "@/lib/supabase/server";

/**
 * Email-link landing endpoint. Handles both the PKCE `code` exchange and the
 * `token_hash` + `type` verification, then redirects to a sanitized target.
 * Any failure lands on /auth/error with a reason — never a silent drop.
 */
export async function GET(request: NextRequest) {
  const { searchParams, origin } = new URL(request.url);
  const code = searchParams.get("code");
  const tokenHash = searchParams.get("token_hash");
  const type = searchParams.get("type");
  const next = sanitizeRedirect(searchParams.get("next"));

  const supabase = await createClient();

  if (code) {
    const { error } = await supabase.auth.exchangeCodeForSession(code);
    if (!error) {
      // Recovery links land here via the verify redirect (type is preserved);
      // they must continue to the new-password form, not the default target.
      const target = type === "recovery" ? "/reset-password" : next;
      return NextResponse.redirect(new URL(target, origin));
    }
    return redirectToError(origin, "expired_link");
  }

  if (tokenHash && isOtpType(type)) {
    const { error } = await supabase.auth.verifyOtp({ type, token_hash: tokenHash });
    if (!error) {
      // Recovery links go straight to setting a new password.
      const target = type === "recovery" ? "/reset-password" : next;
      return NextResponse.redirect(new URL(target, origin));
    }
    return redirectToError(origin, "expired_link");
  }

  return redirectToError(origin, "invalid_link");
}

function redirectToError(origin: string, reason: "invalid_link" | "expired_link") {
  const url = new URL("/auth/error", origin);
  url.searchParams.set("reason", reason);
  return NextResponse.redirect(url);
}
