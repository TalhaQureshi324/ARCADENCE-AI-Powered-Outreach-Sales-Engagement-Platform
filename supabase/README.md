# supabase/

Supabase project configuration and the version-controlled SQL migration set.

- `migrations/` — forward-only SQL migrations, named `<UTC-timestamp>_<snake_case_name>.sql`. Conventions in `docs/DATABASE.md` are binding (RLS on every tenant table, workspace-scoped keys, documented indexes).

**Status:** the migration workflow is finalized in Phase 0.2/0.3; no schema exists yet. Do not edit database state outside this directory.
