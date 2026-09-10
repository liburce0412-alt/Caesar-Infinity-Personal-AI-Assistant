begin;

-- Keep previously public content as-is. Only NEW content defaults to private.
alter table public.posts add column is_public boolean not null default true;
alter table public.listings add column is_public boolean not null default true;
alter table public.posts alter column is_public set default false;
alter table public.listings alter column is_public set default false;
alter table public.listings alter column price_cents drop not null;
grant insert(is_public) on public.posts, public.listings to authenticated;

-- Restrictive policies also fence existing staff/moderation policies. Service-role
-- administration remains privileged, but private content is absent from staff feeds.
create policy posts_visibility on public.posts as restrictive for all to authenticated
  using (is_public or author_id = (select auth.uid()))
  with check (is_public or author_id = (select auth.uid()));
create policy listings_visibility on public.listings as restrictive for all to authenticated
  using (is_public or seller_id = (select auth.uid()))
  with check (is_public or seller_id = (select auth.uid()));
create policy comments_parent_visibility on public.comments as restrictive for all to authenticated
  using (exists(select 1 from public.posts p where p.id = post_id))
  with check (exists(select 1 from public.posts p where p.id = post_id));
create policy likes_parent_visibility on public.post_likes as restrictive for select to authenticated
  using (exists(select 1 from public.posts p where p.id = post_id));
create policy bookmarks_parent_visibility on public.post_bookmarks as restrictive for select to authenticated
  using (exists(select 1 from public.posts p where p.id = post_id));
create policy favorites_parent_visibility on public.favorites as restrictive for select to authenticated
  using (exists(select 1 from public.listings l where l.id = listing_id));

-- Private attachments never pass through a public bucket, even before the row exists.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values
  ('post-private','post-private',false,15728640,array['image/jpeg','image/png','image/webp']),
  ('listing-private','listing-private',false,15728640,array['image/jpeg','image/png','image/webp']);
create policy private_community_media_read on storage.objects for select to authenticated
  using(bucket_id in ('post-private','listing-private') and (storage.foldername(name))[1]=(select auth.uid())::text);
create policy private_community_media_insert on storage.objects for insert to authenticated
  with check(bucket_id in ('post-private','listing-private') and (storage.foldername(name))[1]=(select auth.uid())::text);
create policy private_community_media_delete on storage.objects for delete to authenticated
  using(bucket_id in ('post-private','listing-private') and (storage.foldername(name))[1]=(select auth.uid())::text);
-- The older staff-delete policy must not grant access to private attachments.
create policy private_community_media_boundary on storage.objects as restrictive for all to authenticated
  using(bucket_id not in ('post-private','listing-private') or (storage.foldername(name))[1]=(select auth.uid())::text)
  with check(bucket_id not in ('post-private','listing-private') or (storage.foldername(name))[1]=(select auth.uid())::text);

-- SECURITY DEFINER RPCs need the same boundary explicitly; RLS alone is insufficient.
create or replace function public.toggle_post_like(target_post uuid) returns jsonb
language plpgsql security definer set search_path=public as $$
declare liked boolean; total integer;
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if not exists(select 1 from posts where id=target_post and deleted_at is null and (author_id=auth.uid() or (is_public and (moderation_status='approved' or private.is_staff())))) then
    raise exception 'post_not_available' using errcode='42501';
  end if;
  if exists(select 1 from post_likes where post_id=target_post and user_id=auth.uid()) then
    delete from post_likes where post_id=target_post and user_id=auth.uid(); liked:=false;
  else
    insert into post_likes(post_id,user_id) values(target_post,auth.uid()); liked:=true;
  end if;
  select count(*)::integer into total from post_likes where post_id=target_post;
  update posts set like_count=total where id=target_post;
  return jsonb_build_object('liked',liked,'count',total);
end $$;

create or replace function public.toggle_post_bookmark(target_post uuid) returns boolean
language plpgsql security definer set search_path=public as $$
declare bookmarked boolean;
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if not exists(select 1 from posts where id=target_post and deleted_at is null and (author_id=auth.uid() or (is_public and (moderation_status='approved' or private.is_staff())))) then
    raise exception 'post_not_available' using errcode='42501';
  end if;
  if exists(select 1 from post_bookmarks where post_id=target_post and user_id=auth.uid()) then
    delete from post_bookmarks where post_id=target_post and user_id=auth.uid(); bookmarked:=false;
  else
    insert into post_bookmarks(post_id,user_id) values(target_post,auth.uid()); bookmarked:=true;
  end if;
  return bookmarked;
end $$;

create or replace function public.toggle_favorite(target_listing uuid) returns boolean
language plpgsql security definer set search_path=public as $$
declare favorited boolean;
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if not exists(select 1 from listings where id=target_listing and (seller_id=auth.uid() or (is_public and ((moderation_status='approved' and status not in ('draft','removed')) or private.is_staff())))) then
    raise exception 'listing_not_available' using errcode='42501';
  end if;
  if exists(select 1 from favorites where listing_id=target_listing and user_id=auth.uid()) then
    delete from favorites where listing_id=target_listing and user_id=auth.uid(); favorited:=false;
  else
    insert into favorites(listing_id,user_id) values(target_listing,auth.uid()); favorited:=true;
  end if;
  return favorited;
end $$;

create or replace function public.create_order(target_listing uuid) returns uuid
language plpgsql security definer set search_path=public as $$
declare item listings%rowtype; new_order uuid;
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  select * into item from listings where id=target_listing for update;
  if not found then raise exception 'listing_not_found'; end if;
  if item.seller_id=auth.uid() then raise exception 'cannot_buy_own_listing'; end if;
  if not item.is_public or item.price_cents is null or item.status<>'active' or item.moderation_status<>'approved' then raise exception 'listing_unavailable'; end if;
  update listings set status='reserved' where id=item.id;
  insert into orders(listing_id,buyer_id,seller_id,price_cents) values(item.id,auth.uid(),item.seller_id,item.price_cents) returning id into new_order;
  insert into audit_logs(actor_id,action,resource_type,resource_id,result) values(auth.uid(),'CREATE_ORDER','order',new_order::text,'success');
  return new_order;
end $$;

create or replace function public.create_comment(
  target_post uuid,
  comment_body text,
  parent_comment uuid default null
)
returns public.comments
language plpgsql security definer set search_path = ''
as $$
declare
  created_comment public.comments%rowtype;
  normalized_body text := btrim(coalesce(comment_body, ''));
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  if char_length(normalized_body) not between 1 and 2000 then
    raise exception 'comment_body_invalid' using errcode = '22023';
  end if;
  if not exists(
    select 1 from public.posts post
    where post.id = target_post and post.deleted_at is null
      and (post.author_id = auth.uid() or (post.is_public and (post.moderation_status = 'approved' or private.is_staff())))
  ) then
    raise exception 'post_not_available' using errcode = '42501';
  end if;
  if parent_comment is not null and not exists(
    select 1 from public.comments comment
    where comment.id = parent_comment and comment.post_id = target_post and comment.deleted_at is null
  ) then
    raise exception 'parent_comment_not_available' using errcode = '22023';
  end if;

  insert into public.comments(post_id, author_id, parent_id, body)
  values(target_post, auth.uid(), parent_comment, normalized_body)
  returning * into created_comment;

  return created_comment;
end;
$$;

create or replace function public.open_conversation(other_user uuid, related_listing uuid default null)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
  found_id uuid;
  listing_seller uuid;
begin
  if auth.uid() is null or other_user = auth.uid() then
    raise exception 'invalid_participants';
  end if;
  if not exists(select 1 from public.profiles where id = other_user and not is_blocked) then
    raise exception 'participant_not_available';
  end if;
  if related_listing is not null then
    select seller_id into listing_seller
    from public.listings
    where id = related_listing and is_public
      and (moderation_status = 'approved' or seller_id = auth.uid());
    if listing_seller is null or (auth.uid() <> listing_seller and other_user <> listing_seller) then
      raise exception 'listing_not_available' using errcode = '42501';
    end if;
  end if;

  perform pg_advisory_xact_lock(hashtextextended(
    least(auth.uid()::text, other_user::text)
    || greatest(auth.uid()::text, other_user::text)
    || coalesce(related_listing::text, ''), 0
  ));

  select conversation.id into found_id
  from public.conversations conversation
  join public.conversation_members self_member
    on self_member.conversation_id = conversation.id and self_member.user_id = auth.uid()
  join public.conversation_members peer_member
    on peer_member.conversation_id = conversation.id and peer_member.user_id = other_user
  where conversation.listing_id is not distinct from related_listing
  limit 1;

  if found_id is null then
    insert into public.conversations(listing_id) values(related_listing) returning id into found_id;
    insert into public.conversation_members(conversation_id, user_id)
    values(found_id, auth.uid()), (found_id, other_user);
  end if;
  return found_id;
end;
$$;

commit;
