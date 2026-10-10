-- Private storage buckets (imports, exports, attachments).
-- Access is granted only via short-lived signed URLs minted by the Scala API
-- after authorization (ADR-0006); no public objects.

-- Conditional: plain PostgreSQL (CI) has no storage schema.
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    insert into storage.buckets (id, name, public)
    values
      ('imports',     'imports',     false),
      ('exports',     'exports',     false),
      ('attachments', 'attachments', false)
    on conflict (id) do nothing;
  end if;
end;
$$;
