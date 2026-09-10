begin;

alter table public.listings add column target_date date;
alter table public.listings add column completed_at timestamptz;
alter table public.listings add column completion_note text not null default '' check (char_length(completion_note) <= 2000);
alter table public.listings add column completion_media_paths text[] not null default '{}';
grant insert(target_date) on public.listings to authenticated;

-- The direct column-update API must obey the same review rule as the editor RPC.
create function private.review_edited_community_content() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.is_public and (
    (to_jsonb(new) - array['moderation_status','updated_at','like_count','comment_count','status','deleted_at'])
      is distinct from (to_jsonb(old) - array['moderation_status','updated_at','like_count','comment_count','status','deleted_at'])
  ) then new.moderation_status := 'pending'; end if;
  return new;
end $$;
create trigger posts_review_content before update on public.posts for each row execute function private.review_edited_community_content();
create trigger listings_review_content before update on public.listings for each row execute function private.review_edited_community_content();
revoke all on function private.review_edited_community_content() from public,anon,authenticated;

alter policy posts_visibility on public.posts
  using (deleted_at is null and (is_public or author_id = (select auth.uid())))
  with check (is_public or author_id = (select auth.uid()));
alter policy listings_visibility on public.listings
  using (status <> 'removed' and (is_public or seller_id = (select auth.uid())))
  with check (is_public or seller_id = (select auth.uid()));

-- Owner edits keep creation time and sharing scope, and re-enter review after public text/image changes.
create function public.edit_community_post(target_post uuid, post_body text, post_topic text, anonymous boolean, next_media text[] default null)
returns uuid language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if char_length(btrim(coalesce(post_body,''))) not between 1 and 5000 or char_length(coalesce(post_topic,'')) > 40
    or exists(select 1 from unnest(next_media) path where path not like auth.uid()::text || '/%') then
    raise exception 'content_invalid' using errcode='22023';
  end if;
  update public.posts set body=btrim(post_body), topic=nullif(btrim(post_topic),''), is_anonymous=anonymous,
    media_paths=coalesce(next_media,media_paths), moderation_status=case when is_public then 'pending'::public.moderation_status else moderation_status end
    where id=target_post and author_id=auth.uid() and deleted_at is null;
  if not found then raise exception 'content_not_owned' using errcode='42501'; end if;
  return target_post;
end $$;

create function public.edit_wish(target_listing uuid, wish_title text, wish_description text, wish_price integer, wish_location text, wish_date date, next_media text[] default null)
returns uuid language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if char_length(btrim(coalesce(wish_title,''))) not between 1 and 160 or char_length(coalesce(wish_description,'')) > 2000
    or wish_price < 0 or char_length(coalesce(wish_location,'')) > 80
    or exists(select 1 from unnest(next_media) path where path not like auth.uid()::text || '/%') then
    raise exception 'content_invalid' using errcode='22023';
  end if;
  update public.listings set title=btrim(wish_title), description=coalesce(wish_description,''), price_cents=wish_price,
    location=coalesce(wish_location,''), target_date=wish_date, media_paths=coalesce(next_media,media_paths),
    moderation_status=case when is_public then 'pending'::public.moderation_status else moderation_status end
    where id=target_listing and seller_id=auth.uid() and status<>'removed';
  if not found then raise exception 'content_not_owned' using errcode='42501'; end if;
  return target_listing;
end $$;

create function public.delete_community_post(target_post uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  update public.posts set deleted_at=now() where id=target_post and author_id=auth.uid() and deleted_at is null;
  if not found then raise exception 'content_not_owned' using errcode='42501'; end if;
end $$;

create function public.delete_wish(target_listing uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  update public.listings set status='removed' where id=target_listing and seller_id=auth.uid() and status<>'removed';
  if not found then raise exception 'content_not_owned' using errcode='42501'; end if;
end $$;

create function public.complete_wish(target_listing uuid, memory_note text, memory_media text[] default null) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if char_length(coalesce(memory_note,'')) > 2000
    or exists(select 1 from unnest(memory_media) path where path not like auth.uid()::text || '/%') then
    raise exception 'content_invalid' using errcode='22023';
  end if;
  update public.listings set completed_at=coalesce(completed_at,now()), completion_note=btrim(coalesce(memory_note,'')),
    completion_media_paths=coalesce(memory_media,completion_media_paths),
    moderation_status=case when is_public then 'pending'::public.moderation_status else moderation_status end
    where id=target_listing and seller_id=auth.uid() and status<>'removed';
  if not found then raise exception 'content_not_owned' using errcode='42501'; end if;
end $$;

create table public.listing_comments (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.listings(id) on delete cascade,
  author_id uuid not null references public.profiles(id),
  body text not null check (char_length(btrim(body)) between 1 and 2000),
  moderation_status public.moderation_status not null default 'approved',
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index listing_comments_listing_created_idx on public.listing_comments(listing_id,created_at);
create index listing_comments_author_idx on public.listing_comments(author_id);
alter table public.listing_comments enable row level security;
revoke all on public.listing_comments from anon,authenticated;
grant select on public.listing_comments to authenticated;
create policy wish_comments_read on public.listing_comments for select to authenticated
  using(deleted_at is null and (moderation_status='approved' or author_id=(select auth.uid()))
    and exists(select 1 from public.listings l where l.id=listing_id));

create function public.create_wish_comment(target_listing uuid, comment_body text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare result_id uuid;
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if char_length(btrim(coalesce(comment_body,''))) not between 1 and 2000 then raise exception 'comment_body_invalid' using errcode='22023'; end if;
  perform 1 from public.listings where id=target_listing and status<>'removed'
    and (seller_id=auth.uid() or (is_public and moderation_status='approved')) for share;
  if not found then raise exception 'listing_not_available' using errcode='42501'; end if;
  insert into public.listing_comments(listing_id,author_id,body) values(target_listing,auth.uid(),btrim(comment_body)) returning id into result_id;
  return result_id;
end $$;

revoke all on function public.edit_community_post(uuid,text,text,boolean,text[]),
  public.edit_wish(uuid,text,text,integer,text,date,text[]), public.delete_community_post(uuid), public.delete_wish(uuid),
  public.complete_wish(uuid,text,text[]), public.create_wish_comment(uuid,text) from public,anon;
grant execute on function public.edit_community_post(uuid,text,text,boolean,text[]),
  public.edit_wish(uuid,text,text,integer,text,date,text[]), public.delete_community_post(uuid), public.delete_wish(uuid),
  public.complete_wish(uuid,text,text[]), public.create_wish_comment(uuid,text) to authenticated;

create or replace function public.toggle_favorite(target_listing uuid) returns boolean
language plpgsql security definer set search_path=public as $$
declare favorited boolean;
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode='28000'; end if;
  if not exists(select 1 from listings where id=target_listing and status<>'removed' and (seller_id=auth.uid() or (is_public and ((moderation_status='approved' and status not in ('draft','removed')) or private.is_staff())))) then
    raise exception 'listing_not_available' using errcode='42501';
  end if;
  if exists(select 1 from favorites where listing_id=target_listing and user_id=auth.uid()) then
    delete from favorites where listing_id=target_listing and user_id=auth.uid(); favorited:=false;
  else
    insert into favorites(listing_id,user_id) values(target_listing,auth.uid()); favorited:=true;
  end if;
  return favorited;
end $$;


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
    where id = related_listing and is_public and status <> 'removed'
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
