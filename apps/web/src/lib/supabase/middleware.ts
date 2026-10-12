import { createServerClient } from "@supabase/ssr";
import { NextResponse, type NextRequest } from "next/server";

import { sanitizeRedirect } from "@/lib/auth/redirects";

const PROTECTED_PREFIXES = ["/app", "/onboarding"];
// Signed-in users are steered away from these. /reset-password is deliberately
// excluded: a recovery link creates a session whose whole purpose is to reach
// the new-password form; that page manages its own session states.
const AUTH_PAGES = ["/login", "/signup", "/forgot-password"];

/** Refreshes the Supabase session and enforces route protection at the edge. */
export async function updateSession(request: NextRequest) {
  let response = NextResponse.next({ request });

  const supabase = createServerClient(
    process.env.NEXT_PUBLIC_SUPABASE_URL!,
    process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!,
    {
      cookies: {
        getAll() {
          return request.cookies.getAll();
        },
        setAll(cookiesToSet) {
          cookiesToSet.forEach(({ name, value }) => request.cookies.set(name, value));
          response = NextResponse.next({ request });
          cookiesToSet.forEach(({ name, value, options }) =>
            response.cookies.set(name, value, options),
          );
        },
      },
    },
  );

  // getUser() revalidates the token against Supabase Auth (never trusts the
  // cookie payload alone) and rotates refresh tokens as needed.
  const {
    data: { user },
  } = await supabase.auth.getUser();

  const path = request.nextUrl.pathname;
  const isProtected = PROTECTED_PREFIXES.some((p) => path === p || path.startsWith(`${p}/`));

  if (isProtected && !user) {
    const target = path + (request.nextUrl.search || "");
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("next", target);
    return NextResponse.redirect(loginUrl);
  }

  if (user && AUTH_PAGES.includes(path)) {
    const next = sanitizeRedirect(request.nextUrl.searchParams.get("next"));
    return NextResponse.redirect(new URL(next, request.url));
  }

  return response;
}
