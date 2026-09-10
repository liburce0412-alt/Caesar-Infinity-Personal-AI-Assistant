begin;

-- The 2026-09-08 catalog restore preserved table ACLs but omitted column ACLs.
-- Restore only the client fields granted by the existing historical migrations.
-- RLS and server-owned fields (roles, moderation, counters, workflow state) stay protected.
grant update(display_name,handle,avatar_path,cover_path,bio,settings,updated_at)
  on public.profiles to authenticated;
grant insert(author_id,body,topic,tags,media_paths,is_anonymous,is_public)
  on public.posts to authenticated;
grant update(body,topic,tags,media_paths,is_anonymous)
  on public.posts to authenticated;
grant insert(seller_id,title,description,price_cents,original_price_cents,category,condition,location,media_paths,is_public,target_date)
  on public.listings to authenticated;
grant update(title,description,price_cents,original_price_cents,category,condition,location,media_paths)
  on public.listings to authenticated;
grant insert(reporter_id,target_type,target_id,reason,details,content_digest)
  on public.reports to authenticated;

commit;
