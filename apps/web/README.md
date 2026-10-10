# Arcadence Web (`apps/web`)

Next.js App Router frontend for Arcadence — TypeScript, Tailwind CSS v4, ESLint, Prettier, Vitest.

## Commands

```bash
npm run dev     # development server (http://localhost:3000)
npm run build   # production build
npm start       # serve the production build
npm run lint    # ESLint
npm test        # Vitest unit tests
npm run format  # Prettier write
```

Copy `.env.example` to `.env.local` for configuration. Only `NEXT_PUBLIC_*` variables reach the browser — see `docs/SECURITY.md` before adding any.

## What exists (Phase 0.1)

- `/` — system health page that probes the Scala API's `/health` and `/ready` endpoints with honest loading / up / degraded / unreachable states.
- Reusable primitives in `src/components/` (`StatusBadge`, `HealthDashboard`).
- API probing helpers in `src/lib/api.ts`.

Feature work starts in Phase 1; repository-wide rules are in the root [`AGENTS.md`](../../AGENTS.md).
