begin;

-- Content and its attachments share the same access boundary, even after a visibility edit.
-- Existing object names/buckets stay in place; clients resolve both historical bucket locations.
update storage.buckets set public = false where id in ('post-media','listing-media');
alter policy public_media_read on storage.objects using (bucket_id in ('avatars','covers'));
drop policy private_community_media_boundary on storage.objects;

create policy community_media_read on storage.objects for select to authenticated using (
  bucket_id in ('post-media','post-private','listing-media','listing-private') and (
    (storage.foldername(name))[1] = (select auth.uid())::text
    or (bucket_id in ('post-media','post-private') and exists (
      select 1 from public.posts p where name = any(p.media_paths)
        and p.author_id::text = (storage.foldername(name))[1]
        and p.is_public and p.deleted_at is null and (p.moderation_status = 'approved' or (select private.is_staff()))))
    or (bucket_id in ('listing-media','listing-private') and exists (
      select 1 from public.listings l where (name = any(l.media_paths) or name = any(l.completion_media_paths))
        and l.seller_id::text = (storage.foldername(name))[1]
        and l.is_public and l.status not in ('removed','draft') and (l.moderation_status = 'approved' or (select private.is_staff()))))
  )
);
-- Restrict legacy staff delete/update grants as well as private-bucket uploads.
create policy community_media_insert_boundary on storage.objects as restrictive for insert to authenticated
with check (bucket_id not in ('post-media','post-private','listing-media','listing-private') or (storage.foldername(name))[1] = (select auth.uid())::text);
create policy community_media_update_boundary on storage.objects as restrictive for update to authenticated
using (bucket_id not in ('post-media','post-private','listing-media','listing-private') or (storage.foldername(name))[1] = (select auth.uid())::text)
with check (bucket_id not in ('post-media','post-private','listing-media','listing-private') or (storage.foldername(name))[1] = (select auth.uid())::text);
create policy community_media_delete_boundary on storage.objects as restrictive for delete to authenticated
using (bucket_id not in ('post-media','post-private','listing-media','listing-private') or (storage.foldername(name))[1] = (select auth.uid())::text);

create function public.edit_community_post_visibility(target_post uuid, post_body text, post_topic text, anonymous boolean, next_media text[], next_public boolean)
returns uuid language plpgsql security definer set search_path = '' as $$
begin
  if next_public is null then raise exception 'content_invalid' using errcode='22023'; end if;
  perform public.edit_community_post(target_post, post_body, post_topic, anonymous, next_media);
  update public.posts set is_public = next_public
    where id = target_post and author_id = auth.uid() and deleted_at is null;
  return target_post;
end $$;

create function public.edit_wish_visibility(target_listing uuid, wish_title text, wish_description text, wish_price integer, wish_location text, wish_date date, next_media text[], next_public boolean)
returns uuid language plpgsql security definer set search_path = '' as $$
begin
  if next_public is null then raise exception 'content_invalid' using errcode='22023'; end if;
  perform public.edit_wish(target_listing, wish_title, wish_description, wish_price, wish_location, wish_date, next_media);
  update public.listings set is_public = next_public
    where id = target_listing and seller_id = auth.uid() and status <> 'removed';
  return target_listing;
end $$;

revoke all on function public.edit_community_post_visibility(uuid,text,text,boolean,text[],boolean) from public,anon;
revoke all on function public.edit_wish_visibility(uuid,text,text,integer,text,date,text[],boolean) from public,anon;
grant execute on function public.edit_community_post_visibility(uuid,text,text,boolean,text[],boolean) to authenticated;
grant execute on function public.edit_wish_visibility(uuid,text,text,integer,text,date,text[],boolean) to authenticated;
commit;
