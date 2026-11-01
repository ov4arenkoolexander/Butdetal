-- BUTDETAL shared sync migration
-- Run once in Supabase Dashboard -> SQL Editor.
-- This keeps the existing roles unchanged. All authenticated staff accounts
-- receive the same read/write rights for shared catalogue records.

-- Shared product catalogue: allow every authenticated account to sync edits.
drop policy if exists "shared_records_insert_admin" on public.shared_records;
drop policy if exists "shared_records_update_admin" on public.shared_records;
drop policy if exists "shared_records_delete_admin" on public.shared_records;
drop policy if exists "butdetal_shared_records_insert_authenticated" on public.shared_records;
drop policy if exists "butdetal_shared_records_update_authenticated" on public.shared_records;
drop policy if exists "butdetal_shared_records_delete_authenticated" on public.shared_records;

create policy "butdetal_shared_records_insert_authenticated"
on public.shared_records for insert to authenticated
with check (true);

create policy "butdetal_shared_records_update_authenticated"
on public.shared_records for update to authenticated
using (true) with check (true);

create policy "butdetal_shared_records_delete_authenticated"
on public.shared_records for delete to authenticated
using (true);

-- Shared archive metadata: all signed-in staff can read; each uploader owns
-- their own metadata rows. Existing company read policy is retained.
drop policy if exists "archive_files_read_company" on public.archive_files;
create policy "archive_files_read_company"
on public.archive_files for select to authenticated
using (deleted_at is null);

-- Storage: allow signed-in staff to read all company files. Uploads stay
-- separated under each user's UUID folder; owners/admins can update/delete.
drop policy if exists "butdetal_storage_read_company" on storage.objects;
create policy "butdetal_storage_read_company"
on storage.objects for select to authenticated
using (bucket_id = 'butdetal-archive');

-- Ensure bucket exists with the expected private configuration.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('butdetal-archive', 'butdetal-archive', false, 52428800,
        array['image/jpeg','image/png','image/webp','application/pdf'])
on conflict (id) do update set
  public = false,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;
