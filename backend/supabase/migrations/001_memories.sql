create extension if not exists vector;

create table if not exists public.memories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  type text not null,
  summary text not null,
  value jsonb not null,
  source_package text,
  provenance jsonb not null default '{}'::jsonb,
  sensitivity text not null default 'normal',
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  pinned boolean not null default false,
  embedding vector(1536)
);

alter table public.memories enable row level security;

create policy "Users read their own memories" on public.memories
  for select using (auth.uid() = user_id);
create policy "Users add their own memories" on public.memories
  for insert with check (auth.uid() = user_id);
create policy "Users update their own memories" on public.memories
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "Users delete their own memories" on public.memories
  for delete using (auth.uid() = user_id);

create index if not exists memories_user_created_idx on public.memories(user_id, created_at desc);
create index if not exists memories_expiry_idx on public.memories(expires_at) where expires_at is not null and pinned = false;

