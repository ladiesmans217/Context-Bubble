create table public.usage_daily (
  owner_key text not null,
  usage_day date not null default current_date,
  metric text not null check (metric in ('ASSIST_REQUEST', 'IMAGE_REQUEST', 'REALTIME_SECOND', 'TRANSCRIPTION_SECOND')),
  used bigint not null default 0 check (used >= 0),
  updated_at timestamptz not null default now(),
  primary key (owner_key, usage_day, metric)
);

alter table public.usage_daily enable row level security;
revoke all on public.usage_daily from anon, authenticated;

create table public.mcp_confirmation_nonces (
  user_id uuid not null references auth.users(id) on delete cascade,
  nonce uuid not null,
  client_id text not null,
  operation text not null check (operation in ('UPSERT', 'DELETE')),
  consumed_at timestamptz not null default now(),
  expires_at timestamptz not null,
  primary key (user_id, nonce)
);

alter table public.mcp_confirmation_nonces enable row level security;
revoke all on public.mcp_confirmation_nonces from anon, authenticated;

create table public.action_confirmation_nonces (
  user_id uuid not null references auth.users(id) on delete cascade,
  nonce uuid not null,
  operation text not null check (operation in ('CALENDAR_CREATE', 'CALENDAR_UPDATE', 'CALENDAR_DELETE')),
  consumed_at timestamptz not null default now(),
  expires_at timestamptz not null,
  primary key (user_id, nonce)
);

alter table public.action_confirmation_nonces enable row level security;
revoke all on public.action_confirmation_nonces from anon, authenticated;

create or replace function public.reserve_context_bubble_usage(
  requested_owner_key text,
  requested_metric text,
  requested_amount bigint,
  requested_limit bigint
)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
  resulting_usage bigint;
begin
  if char_length(requested_owner_key) <> 64 then
    raise exception 'invalid usage owner';
  end if;
  if requested_metric not in ('ASSIST_REQUEST', 'IMAGE_REQUEST', 'REALTIME_SECOND', 'TRANSCRIPTION_SECOND') then
    raise exception 'invalid usage metric';
  end if;
  if requested_amount <= 0 or requested_limit < 0 then
    raise exception 'invalid usage amount';
  end if;

  insert into public.usage_daily(owner_key, usage_day, metric, used)
  values (requested_owner_key, current_date, requested_metric, requested_amount)
  on conflict (owner_key, usage_day, metric)
  do update set used = public.usage_daily.used + excluded.used, updated_at = now()
  returning used into resulting_usage;

  if resulting_usage > requested_limit then
    raise exception using errcode = 'P0001', message = 'daily_usage_limit_exceeded';
  end if;
  return resulting_usage;
end;
$$;

revoke all on function public.reserve_context_bubble_usage(text, text, bigint, bigint) from public, anon, authenticated;
grant execute on function public.reserve_context_bubble_usage(text, text, bigint, bigint) to service_role;

-- OAuth access tokens include client_id and otherwise have the authenticated role. Block those
-- tokens from direct PostgREST/Storage access; the validated MCP Edge Function uses service_role
-- and always applies an explicit user_id filter after grant checks.
drop policy if exists memory_items_owner_all on public.memory_items;
create policy memory_items_phone_owner_all on public.memory_items for all
  using (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null)
  with check (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);
drop policy if exists memory_audit_owner_read on public.memory_audit;
drop policy if exists memory_audit_owner_insert on public.memory_audit;
create policy memory_audit_phone_owner_read on public.memory_audit for select
  using (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);
create policy memory_audit_phone_owner_insert on public.memory_audit for insert
  with check (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);
drop policy if exists memory_sync_state_owner_all on public.memory_sync_state;
create policy memory_sync_state_phone_owner_all on public.memory_sync_state for all
  using (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null)
  with check (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);
drop policy if exists idempotency_owner_all on public.idempotency_records;
create policy idempotency_phone_owner_all on public.idempotency_records for all
  using (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null)
  with check (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);
drop policy if exists mcp_grants_owner_all on public.mcp_grants;
create policy mcp_grants_phone_owner_all on public.mcp_grants for all
  using (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null)
  with check (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);
drop policy if exists integration_tokens_owner_all on public.user_integration_tokens;
create policy integration_tokens_phone_owner_all on public.user_integration_tokens for all
  using (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null)
  with check (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);
drop policy if exists cloud_blobs_owner_all on public.cloud_blobs;
create policy cloud_blobs_phone_owner_all on public.cloud_blobs for all
  using (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null)
  with check (auth.uid() = user_id and auth.jwt() ->> 'client_id' is null);

drop policy if exists cloud_outputs_owner_read on storage.objects;
drop policy if exists cloud_outputs_owner_insert on storage.objects;
drop policy if exists cloud_outputs_owner_update on storage.objects;
drop policy if exists cloud_outputs_owner_delete on storage.objects;
create policy cloud_outputs_phone_owner_read on storage.objects for select to authenticated
  using (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text and auth.jwt() ->> 'client_id' is null);
create policy cloud_outputs_phone_owner_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text and auth.jwt() ->> 'client_id' is null);
create policy cloud_outputs_phone_owner_update on storage.objects for update to authenticated
  using (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text and auth.jwt() ->> 'client_id' is null)
  with check (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text and auth.jwt() ->> 'client_id' is null);
create policy cloud_outputs_phone_owner_delete on storage.objects for delete to authenticated
  using (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text and auth.jwt() ->> 'client_id' is null);

create or replace function public.match_memory_items_for_user(
  requested_user_id uuid,
  query_embedding halfvec(384),
  match_count integer default 20,
  requested_model text default 'gte-small'
)
returns table (
  id uuid, user_id uuid, type text, payload_ciphertext text, payload_nonce text,
  key_version integer, source_package text, sensitivity text, pinned boolean,
  expires_at timestamptz, embedding_model text, version integer, sync_seq bigint,
  deleted boolean, created_at timestamptz, updated_at timestamptz, similarity double precision
)
language sql
stable
security invoker
set search_path = public
as $$
  select
    item.id, item.user_id, item.type, item.payload_ciphertext, item.payload_nonce,
    item.key_version, item.source_package, item.sensitivity, item.pinned, item.expires_at,
    item.embedding_model, item.version, item.sync_seq, item.deleted, item.created_at,
    item.updated_at, (-(item.embedding <#> query_embedding))::double precision
  from public.memory_items item
  where item.user_id = requested_user_id
    and item.deleted = false
    and (item.expires_at is null or item.expires_at > now() or item.pinned)
    and item.embedding is not null
    and item.embedding_model = requested_model
  order by item.embedding <#> query_embedding
  limit greatest(1, least(match_count, 50));
$$;

revoke all on function public.match_memory_items_for_user(uuid, halfvec, integer, text) from public, anon, authenticated;
grant execute on function public.match_memory_items_for_user(uuid, halfvec, integer, text) to service_role;

create or replace function public.match_memory_items(
  query_embedding halfvec(384),
  match_count integer default 20,
  requested_model text default 'gte-small'
)
returns table (
  id uuid, type text, payload_ciphertext text, payload_nonce text, key_version integer,
  source_package text, sensitivity text, pinned boolean, expires_at timestamptz,
  embedding_model text, version integer, sync_seq bigint, deleted boolean,
  created_at timestamptz, updated_at timestamptz, similarity double precision
)
language sql
stable
security invoker
set search_path = public
as $$
  select
    item.id, item.type, item.payload_ciphertext, item.payload_nonce, item.key_version,
    item.source_package, item.sensitivity, item.pinned, item.expires_at, item.embedding_model,
    item.version, item.sync_seq, item.deleted, item.created_at, item.updated_at,
    (-(item.embedding <#> query_embedding))::double precision
  from public.memory_items item
  where item.user_id = auth.uid()
    and auth.jwt() ->> 'client_id' is null
    and item.deleted = false
    and (item.expires_at is null or item.expires_at > now() or item.pinned)
    and item.embedding is not null
    and item.embedding_model = requested_model
  order by item.embedding <#> query_embedding
  limit greatest(1, least(match_count, 50));
$$;

revoke all on function public.match_memory_items(halfvec, integer) from authenticated;
grant execute on function public.match_memory_items(halfvec, integer, text) to authenticated;

create or replace function public.cleanup_context_bubble_data()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  lock_acquired boolean;
  removed_tombstones integer := 0;
  removed_idempotency integer := 0;
  removed_audit integer := 0;
  removed_usage integer := 0;
  removed_confirmations integer := 0;
  removed_action_confirmations integer := 0;
begin
  lock_acquired := pg_try_advisory_xact_lock(hashtext('context-bubble-daily-cleanup'));
  if not lock_acquired then return jsonb_build_object('skipped', true); end if;

  delete from public.memory_items
    where deleted = true and deleted_at < now() - interval '30 days';
  get diagnostics removed_tombstones = row_count;

  delete from public.idempotency_records where expires_at < now();
  get diagnostics removed_idempotency = row_count;

  delete from public.memory_audit where created_at < now() - interval '365 days';
  get diagnostics removed_audit = row_count;

  delete from public.usage_daily where usage_day < current_date - 35;
  get diagnostics removed_usage = row_count;

  delete from public.mcp_confirmation_nonces where expires_at < now() - interval '1 day';
  get diagnostics removed_confirmations = row_count;

  delete from public.action_confirmation_nonces where expires_at < now() - interval '1 day';
  get diagnostics removed_action_confirmations = row_count;

  return jsonb_build_object(
    'skipped', false,
    'tombstones', removed_tombstones,
    'idempotency', removed_idempotency,
    'audit', removed_audit,
    'usage', removed_usage,
    'confirmations', removed_confirmations + removed_action_confirmations
  );
end;
$$;

revoke all on function public.cleanup_context_bubble_data() from public, anon, authenticated;
grant execute on function public.cleanup_context_bubble_data() to service_role;

-- Enable pg_cron in the Supabase dashboard, then schedule this once daily:
-- select cron.schedule('context-bubble-cleanup', '17 3 * * *', $$select public.cleanup_context_bubble_data();$$);
