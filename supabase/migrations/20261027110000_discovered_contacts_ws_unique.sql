-- ---------------------------------------------------------------------------
-- Migration 20261027110000 — discovered_contacts uniqueness per workspace
-- The (provider, provider_contact_id) unique constraint was global: two
-- workspaces searching the same provider fixture would collide, hiding
-- results from the second workspace. Scope uniqueness to the workspace.
-- ---------------------------------------------------------------------------

alter table public.discovered_contacts
  drop constraint discovered_contacts_provider_provider_contact_id_key;
alter table public.discovered_contacts
  add constraint discovered_contacts_ws_provider_contact_key
  unique (workspace_id, provider, provider_contact_id);
