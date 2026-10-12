-- Adds the campaign manager role (product-owner role list, Phase 1.2):
-- owner, admin, campaign_manager, member, viewer.
-- Note: the new value is only used by application inserts after this
-- migration — ALTER TYPE ... ADD VALUE cannot be used by the same
-- transaction, which the migration runner respects.
alter type public.workspace_role add value if not exists 'campaign_manager' after 'admin';
