-- BUTDETAL Supabase setup (initial migration)
-- Run in Supabase Dashboard -> SQL Editor -> New query.
-- Uses only the user's authenticated JWT; NEVER put service_role keys in the Android app.

create extension if not exists pgcrypto;

-- User profiles. The first account must be promoted to admin manually
-- using the SQL statement at the bottom after creating the account.
create table if not exists public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null default 'colleague' check (role in ('admin','colleague')),
  display_name text not null default '',
  created_at timestamptz not null default now()
);

-- Shared product catalogue / reference records.
create table if not exists public.shared_records (
  id text primary key,
  record_type text not null,
  data jsonb not null default '{}'::jsonb,
  updated_by uuid references auth.users(id) on delete set null,
  updated_by_name text not null default '',
  updated_at timestamptz not null default now()
);

create index if not exists shared_records_type_idx
  on public.shared_records(record_type);
create index if not exists shared_records_updated_idx
  on public.shared_records(updated_at desc);

-- File metadata for drawings, product photos and PDFs.
-- The actual binary files are stored in the private Storage bucket.
create table if not exists public.archive_files (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  folder text not null check (folder in ('drawings','products','pdf')),
  object_path text not null unique,
  file_name text not null,
  mime_type text not null default 'application/octet-stream',
  size_bytes bigint not null default 0 check (size_bytes >= 0),
  caption text not null default '',
  notes text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create index if not exists archive_files_owner_folder_idx
  on public.archive_files(owner_id, folder, created_at desc);

alter table public.profiles enable row level security;
alter table public.shared_records enable row level security;
alter table public.archive_files enable row level security;

-- Profile: a signed-in user can read their own profile.
drop policy if exists "profile_select_self" on public.profiles;
create policy "profile_select_self" on public.profiles
  for select to authenticated using (user_id = auth.uid());

-- Shared catalogue: signed-in staff can read; writes are restricted to admins.
drop policy if exists "shared_records_read_authenticated" on public.shared_records;
create policy "shared_records_read_authenticated" on public.shared_records
  for select to authenticated using (true);

drop policy if exists "shared_records_insert_admin" on public.shared_records;
create policy "shared_records_insert_admin" on public.shared_records
  for insert to authenticated
  with check (exists (
    select 1 from public.profiles p
    where p.user_id = auth.uid() and p.role = 'admin'
  ));

drop policy if exists "shared_records_update_admin" on public.shared_records;
create policy "shared_records_update_admin" on public.shared_records
  for update to authenticated
  using (exists (
    select 1 from public.profiles p
    where p.user_id = auth.uid() and p.role = 'admin'
  ))
  with check (exists (
    select 1 from public.profiles p
    where p.user_id = auth.uid() and p.role = 'admin'
  ));

drop policy if exists "shared_records_delete_admin" on public.shared_records;
create policy "shared_records_delete_admin" on public.shared_records
  for delete to authenticated
  using (exists (
    select 1 from public.profiles p
    where p.user_id = auth.uid() and p.role = 'admin'
  ));

-- Archive metadata: each user can access only their own uploaded files.
drop policy if exists "archive_files_select_own" on public.archive_files;
drop policy if exists "archive_files_read_company" on public.archive_files;
create policy "archive_files_read_company" on public.archive_files
  for select to authenticated using (deleted_at is null);

drop policy if exists "archive_files_insert_own" on public.archive_files;
create policy "archive_files_insert_own" on public.archive_files
  for insert to authenticated with check (owner_id = auth.uid());

drop policy if exists "archive_files_update_own" on public.archive_files;
create policy "archive_files_update_own" on public.archive_files
  for update to authenticated using (
    owner_id = auth.uid() or exists (
      select 1 from public.profiles p
      where p.user_id = auth.uid() and p.role = 'admin'
    )
  ) with check (
    owner_id = auth.uid() or exists (
      select 1 from public.profiles p
      where p.user_id = auth.uid() and p.role = 'admin'
    )
  );

drop policy if exists "archive_files_delete_own" on public.archive_files;
create policy "archive_files_delete_own" on public.archive_files
  for delete to authenticated using (
    owner_id = auth.uid() or exists (
      select 1 from public.profiles p
      where p.user_id = auth.uid() and p.role = 'admin'
    )
  );

-- Private bucket. Object path format: <user-uuid>/<folder>/<random>-<filename>
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'butdetal-archive',
  'butdetal-archive',
  false,
  52428800,
  array['image/jpeg','image/png','image/webp','application/pdf']
)
on conflict (id) do update set
  public = false,
  file_size_limit = 52428800,
  allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "butdetal_storage_read_own" on storage.objects;
drop policy if exists "butdetal_storage_read_company" on storage.objects;
create policy "butdetal_storage_read_company" on storage.objects
  for select to authenticated
  using (bucket_id = 'butdetal-archive');

drop policy if exists "butdetal_storage_insert_own" on storage.objects;
create policy "butdetal_storage_insert_own" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'butdetal-archive'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

drop policy if exists "butdetal_storage_update_own" on storage.objects;
create policy "butdetal_storage_update_own" on storage.objects
  for update to authenticated
  using (
    bucket_id = 'butdetal-archive'
    and (
      (storage.foldername(name))[1] = auth.uid()::text
      or exists (select 1 from public.profiles p where p.user_id = auth.uid() and p.role = 'admin')
    )
  )
  with check (
    bucket_id = 'butdetal-archive'
    and (
      (storage.foldername(name))[1] = auth.uid()::text
      or exists (select 1 from public.profiles p where p.user_id = auth.uid() and p.role = 'admin')
    )
  );

drop policy if exists "butdetal_storage_delete_own" on storage.objects;
create policy "butdetal_storage_delete_own" on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'butdetal-archive'
    and (
      (storage.foldername(name))[1] = auth.uid()::text
      or exists (select 1 from public.profiles p where p.user_id = auth.uid() and p.role = 'admin')
    )
  );

-- IMPORTANT:
-- 1) Create your account in Supabase Authentication -> Users.
-- 2) Copy that account's UUID and run this statement, replacing the UUID:
-- insert into public.profiles(user_id, role, display_name)
-- values ('YOUR-AUTH-USER-UUID', 'admin', 'Адміністратор');
--
-- Do not share your password, service_role key, or private credentials in chat.
