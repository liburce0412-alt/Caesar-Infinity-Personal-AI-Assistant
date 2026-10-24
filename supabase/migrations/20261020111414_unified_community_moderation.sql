begin;

-- Preserve existing moderation decisions; all new comments enter the same queue.
alter table public.listing_comments alter column moderation_status set default 'pending';

alter policy wish_comments_read on public.listing_comments
  using (
    deleted_at is null
    and (moderation_status = 'approved' or author_id = (select auth.uid()) or private.is_staff())
    -- Parent RLS also prevents staff from reading someone else's private wish.
    and exists(select 1 from public.listings l where l.id = listing_id)
  );

create or replace function private.sync_post_comment_count()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  -- Serialize changes to the same parent's counter, including moderation changes.
  perform 1 from public.posts
    where id in (
      case when tg_op <> 'INSERT' then old.post_id end,
      case when tg_op <> 'DELETE' then new.post_id end
    ) order by id for update;
  update public.posts p set comment_count = (
    select count(*)::integer from public.comments c
    where c.post_id = p.id and c.deleted_at is null and c.moderation_status = 'approved'
  ) where p.id in (
    case when tg_op <> 'INSERT' then old.post_id end,
    case when tg_op <> 'DELETE' then new.post_id end
  );
  return case when tg_op = 'DELETE' then old else new end;
end $$;

drop trigger comments_sync_post_count on public.comments;
create trigger comments_sync_post_count
after insert or delete or update of deleted_at, post_id, moderation_status on public.comments
for each row execute function private.sync_post_comment_count();

create or replace function public.moderate_content(
  target_type text, target_id uuid, next_status public.moderation_status
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_staff() then raise exception 'staff_required' using errcode = '42501'; end if;
  if next_status is null or next_status not in ('approved', 'rejected', 'removed') then
    raise exception 'invalid_moderation_status' using errcode = '22023';
  end if;
  -- Definer RPCs must enforce the same public-content boundary as the admin feed.
  case target_type
    when 'post' then
      update public.posts set moderation_status = next_status
        where id = target_id and is_public and deleted_at is null;
    when 'comment' then
      update public.comments c set moderation_status = next_status
        where c.id = target_id and c.deleted_at is null
          and exists(select 1 from public.posts p where p.id = c.post_id and p.is_public and p.deleted_at is null);
    when 'listing' then
      update public.listings set moderation_status = next_status
        where id = target_id and is_public and status <> 'removed';
    when 'listing_comment' then
      update public.listing_comments c set moderation_status = next_status
        where c.id = target_id and c.deleted_at is null
          and exists(select 1 from public.listings l where l.id = c.listing_id and l.is_public and l.status <> 'removed');
    else raise exception 'unsupported_content_type' using errcode = '22023';
  end case;
  if not found then raise exception 'content_not_available' using errcode = '42501'; end if;
  insert into public.audit_logs(actor_id, action, resource_type, resource_id, result, metadata)
  values(auth.uid(), 'MODERATE_CONTENT', target_type, target_id::text, 'success', jsonb_build_object('status', next_status));
end $$;

revoke all on function private.sync_post_comment_count() from public, anon, authenticated;
revoke all on function public.moderate_content(text,uuid,public.moderation_status) from public, anon;
grant execute on function public.moderate_content(text,uuid,public.moderation_status) to authenticated;

update public.posts p set comment_count = (
  select count(*)::integer from public.comments c
  where c.post_id = p.id and c.deleted_at is null and c.moderation_status = 'approved'
);

commit;
