insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'cloud-outputs',
  'cloud-outputs',
  false,
  10485760,
  array['application/octet-stream']
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create policy cloud_outputs_owner_read on storage.objects for select to authenticated
  using (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text);
create policy cloud_outputs_owner_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text);
create policy cloud_outputs_owner_update on storage.objects for update to authenticated
  using (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text);
create policy cloud_outputs_owner_delete on storage.objects for delete to authenticated
  using (bucket_id = 'cloud-outputs' and (storage.foldername(name))[1] = auth.uid()::text);
