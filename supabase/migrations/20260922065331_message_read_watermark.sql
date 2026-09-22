begin;

alter table public.conversation_members add column last_read_message_id uuid;

-- Stable cursors include an ID tie-breaker; tombstones are deliberately included.
create index if not exists time_entries_sync_cursor on public.time_entries(user_id, updated_at, id);
create index if not exists course_schedules_sync_cursor on public.course_schedules(user_id, updated_at, id);
create index if not exists messages_history_cursor on public.messages(conversation_id, created_at, id) where deleted_at is null;

-- Old clients must not acknowledge messages they never fetched.
revoke execute on function public.mark_conversation_read(uuid) from public, anon, authenticated;

create or replace function public.mark_conversation_read_through(target_conversation uuid, last_message uuid)
returns void
language plpgsql security definer set search_path = ''
as $$
declare
  visible_message public.messages%rowtype;
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  if not exists(select 1 from public.conversation_members
    where conversation_id = target_conversation and user_id = auth.uid()) then
    raise exception 'conversation_not_available' using errcode = '42501';
  end if;
  select * into visible_message from public.messages
    where id = last_message and conversation_id = target_conversation and deleted_at is null;
  if not found then
    raise exception 'message_not_available' using errcode = '22023';
  end if;
  update public.conversation_members
    set last_read_at = visible_message.created_at, last_read_message_id = visible_message.id
    where conversation_id = target_conversation and user_id = auth.uid()
      and (coalesce(last_read_at, '-infinity'::timestamptz),
           coalesce(last_read_message_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid))
          < (visible_message.created_at, visible_message.id);
end;
$$;
revoke all on function public.mark_conversation_read_through(uuid, uuid) from public, anon;
grant execute on function public.mark_conversation_read_through(uuid, uuid) to authenticated;

create or replace function public.list_conversation_summaries()
returns table(
  id uuid,
  listing_id uuid,
  listing_title text,
  other_user_id uuid,
  other_name text,
  last_message text,
  last_message_at timestamptz,
  unread_count integer
)
language sql stable security definer set search_path = ''
as $$
  select
    c.id,
    c.listing_id,
    coalesce(l.title, ''),
    peer.user_id,
    coalesce(p.display_name, 'CampusAI 用户'),
    coalesce(latest.body, ''),
    coalesce(latest.created_at, c.updated_at),
    coalesce(unread.total, 0)::integer
  from public.conversation_members self_member
  join public.conversations c on c.id = self_member.conversation_id
  left join public.listings l on l.id = c.listing_id
  left join lateral (
    select member.user_id
    from public.conversation_members member
    where member.conversation_id = c.id
      and member.user_id <> (select auth.uid())
    order by member.created_at
    limit 1
  ) peer on true
  left join public.profiles p on p.id = peer.user_id
  left join lateral (
    select message.body, message.created_at
    from public.messages message
    where message.conversation_id = c.id and message.deleted_at is null
    order by message.created_at desc, message.id desc
    limit 1
  ) latest on true
  left join lateral (
    select count(*)::integer as total
    from public.messages message
    where message.conversation_id = c.id
      and message.deleted_at is null
      and message.sender_id <> (select auth.uid())
      and (message.created_at, message.id) > (coalesce(self_member.last_read_at, '-infinity'::timestamptz), coalesce(self_member.last_read_message_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid))
  ) unread on true
  where self_member.user_id = (select auth.uid())
  order by coalesce(latest.created_at, c.updated_at) desc;
$$;

commit;
