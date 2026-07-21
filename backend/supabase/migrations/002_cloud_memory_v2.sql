create extension if not exists vector;
create extension if not exists pgcrypto;

do $$
begin
  if to_regclass('public.memories') is not null and exists (select 1 from public.memories limit 1) then
    raise exception 'Legacy public.memories contains data. Export and re-encrypt it before applying migration 002.';
  end if;
end
$$;

drop table if exists public.memories;
create sequence if not exists public.memory_sync_sequence;

create table public.memory_items (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  type text not null check (char_length(type) between 1 and 80),
  payload_ciphertext text not null,
  payload_nonce text not null,
  key_version integer not null check (key_version > 0),
  content_hmac text not null,
  source_package text,
  sensitivity text not null default 'normal',
  pinned boolean not null default false,
  expires_at timestamptz,
  embedding halfvec(384),
  embedding_model text,
  embedding_input_hash text,
  version integer not null default 1 check (version > 0),
  sync_seq bigint not null default nextval('public.memory_sync_sequence'),
  deleted boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (user_id, content_hmac)
);

create table public.memory_audit (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  memory_id uuid not null,
  operation text not null check (operation in ('CREATE', 'UPDATE', 'DELETE', 'MCP_PREPARE', 'MCP_COMMIT', 'MCP_REVOKE')),
  actor_client_id text,
  request_id uuid,
  created_at timestamptz not null default now()
);

create table public.memory_sync_state (
  user_id uuid primary key references auth.users(id) on delete cascade,
  last_client_cursor bigint not null default 0,
  last_cleanup_at timestamptz,
  updated_at timestamptz not null default now()
);

create table public.idempotency_records (
  user_id uuid not null references auth.users(id) on delete cascade,
  idempotency_key uuid not null,
  operation text not null,
  request_hash text not null,
  response jsonb,
  consumed_at timestamptz,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  primary key (user_id, idempotency_key)
);

create table public.mcp_grants (
  user_id uuid not null references auth.users(id) on delete cascade,
  client_id text not null,
  access_level text not null check (access_level in ('READ_ONLY', 'READ_WRITE')),
  revoked_at timestamptz,
  last_access_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, client_id)
);

create table public.user_integration_tokens (
  user_id uuid not null references auth.users(id) on delete cascade,
  provider text not null check (provider in ('GOOGLE_CALENDAR')),
  token_ciphertext text not null,
  token_nonce text not null,
  key_version integer not null,
  granted_scopes text[] not null default '{}',
  expires_at timestamptz,
  revoked_at timestamptz,
  updated_at timestamptz not null default now(),
  primary key (user_id, provider)
);

create table public.cloud_blobs (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  storage_path text not null unique,
  kind text not null,
  mime_type text not null,
  encrypted_size_bytes bigint not null check (encrypted_size_bytes >= 0),
  key_version integer not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  pinned boolean not null default false,
  deleted_at timestamptz
);

create or replace function public.touch_memory_item()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  new.updated_at = now();
  new.sync_seq = nextval('public.memory_sync_sequence');
  if new.deleted and old.deleted is false then
    new.deleted_at = now();
  elsif new.deleted is false then
    new.deleted_at = null;
  end if;
  return new;
end;
$$;

create trigger memory_items_touch
before update on public.memory_items
for each row execute function public.touch_memory_item();

create or replace function public.match_memory_items(
  query_embedding halfvec(384),
  match_count integer default 20
)
returns table (
  id uuid,
  type text,
  payload_ciphertext text,
  payload_nonce text,
  key_version integer,
  source_package text,
  sensitivity text,
  pinned boolean,
  expires_at timestamptz,
  embedding_model text,
  version integer,
  sync_seq bigint,
  created_at timestamptz,
  updated_at timestamptz,
  similarity double precision
)
language sql
stable
security invoker
set search_path = public
as $$
  select
    item.id,
    item.type,
    item.payload_ciphertext,
    item.payload_nonce,
    item.key_version,
    item.source_package,
    item.sensitivity,
    item.pinned,
    item.expires_at,
    item.embedding_model,
    item.version,
    item.sync_seq,
    item.created_at,
    item.updated_at,
    (-(item.embedding <#> query_embedding))::double precision as similarity
  from public.memory_items item
  where item.user_id = auth.uid()
    and item.deleted = false
    and (item.expires_at is null or item.expires_at > now() or item.pinned)
    and item.embedding is not null
  order by item.embedding <#> query_embedding
  limit greatest(1, least(match_count, 50));
$$;

create index memory_items_user_sync_idx on public.memory_items(user_id, sync_seq);
create index memory_items_user_updated_idx on public.memory_items(user_id, updated_at desc);
create index memory_items_expiry_idx on public.memory_items(expires_at) where expires_at is not null and pinned = false;
create index memory_items_embedding_hnsw_idx on public.memory_items using hnsw (embedding halfvec_ip_ops) where deleted = false;
create index memory_audit_user_created_idx on public.memory_audit(user_id, created_at desc);
create index idempotency_expiry_idx on public.idempotency_records(expires_at);
create index cloud_blobs_user_created_idx on public.cloud_blobs(user_id, created_at desc);

alter table public.memory_items enable row level security;
alter table public.memory_audit enable row level security;
alter table public.memory_sync_state enable row level security;
alter table public.idempotency_records enable row level security;
alter table public.mcp_grants enable row level security;
alter table public.user_integration_tokens enable row level security;
alter table public.cloud_blobs enable row level security;

create policy memory_items_owner_all on public.memory_items for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy memory_audit_owner_read on public.memory_audit for select
  using (auth.uid() = user_id);
create policy memory_audit_owner_insert on public.memory_audit for insert
  with check (auth.uid() = user_id);
create policy memory_sync_state_owner_all on public.memory_sync_state for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy idempotency_owner_all on public.idempotency_records for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy mcp_grants_owner_all on public.mcp_grants for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy integration_tokens_owner_all on public.user_integration_tokens for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy cloud_blobs_owner_all on public.cloud_blobs for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);

grant usage on sequence public.memory_sync_sequence to authenticated;
grant select, insert, update, delete on public.memory_items to authenticated;
grant select, insert on public.memory_audit to authenticated;
grant select, insert, update on public.memory_sync_state to authenticated;
grant select, insert, update, delete on public.idempotency_records to authenticated;
grant select, insert, update, delete on public.mcp_grants to authenticated;
grant select, insert, update, delete on public.user_integration_tokens to authenticated;
grant select, insert, update, delete on public.cloud_blobs to authenticated;
grant execute on function public.match_memory_items(halfvec, integer) to authenticated;
