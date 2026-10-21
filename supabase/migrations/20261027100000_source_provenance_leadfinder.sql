-- ---------------------------------------------------------------------------
-- Migration 20261027100000 — Lead Finder follow-up (Phase 10)
-- source_provenance.source gains 'leadfinder' so contacts added from
-- Lead Finder carry their origin in the provenance table.
-- ---------------------------------------------------------------------------

alter table public.source_provenance drop constraint source_provenance_source_check;
alter table public.source_provenance
  add constraint source_provenance_source_check
  check (source in ('manual', 'import', 'api', 'verification', 'leadfinder'));
