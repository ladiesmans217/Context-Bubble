-- Development-project aid for the hackathon recording. Production deployments
-- must not enable the local debug backend that writes this plaintext catalog.
create table if not exists public.memory_demo_catalog (
  user_id uuid not null references auth.users(id) on delete cascade,
  memory_id uuid not null,
  summary text not null,
  value text not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, memory_id)
);

alter table public.memory_demo_catalog enable row level security;

create policy memory_demo_catalog_owner_all on public.memory_demo_catalog for all
  to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

grant select, insert, update, delete on public.memory_demo_catalog to authenticated;

comment on table public.memory_demo_catalog is
  'Development demo mirror. Contains approved shared-memory text only when written through the non-production local backend.';
