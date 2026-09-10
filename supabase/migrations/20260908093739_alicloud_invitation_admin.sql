begin;

create table private.invitations (
  id uuid primary key default gen_random_uuid(),
  code_hash text not null unique,
  code_hint text not null,
  note text not null default '' check (char_length(note) <= 200),
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  revoked_at timestamptz,
  used_by uuid unique references auth.users(id),
  used_at timestamptz
);
alter table private.invitations enable row level security;
revoke all on private.invitations from public, anon, authenticated;

create function public.admin_create_invitations(count integer, days integer, note text default '')
returns jsonb language plpgsql security definer set search_path = '' as $$
declare code text; result jsonb := '[]'; expires timestamptz;
begin
  if not private.is_admin() then raise exception 'admin_required' using errcode='42501'; end if;
  if count is null or days is null or count not between 1 and 50 or days not between 1 and 365 or char_length(coalesce(note,'')) > 200 then
    raise exception 'invalid_invitation_parameters' using errcode='22023';
  end if;
  expires := now() + make_interval(days => days);
  for i in 1..count loop
    code := upper(encode(extensions.gen_random_bytes(12),'hex'));
    insert into private.invitations(code_hash,code_hint,note,created_by,expires_at)
      values(encode(extensions.digest(code,'sha256'),'hex'),right(code,4),coalesce(note,''),auth.uid(),expires);
    result := result || jsonb_build_array(jsonb_build_object('code',code,'expires_at',expires,'note',coalesce(note,'')));
  end loop;
  insert into public.audit_logs(actor_id,action,resource_type,result,metadata)
    values(auth.uid(),'CREATE_INVITATIONS','invitation','success',jsonb_build_object('count',count,'days',days));
  return result;
end $$;

create function public.admin_revoke_invitation(target_invitation uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_admin() then raise exception 'admin_required' using errcode='42501'; end if;
  update private.invitations set revoked_at=now() where id=target_invitation and used_at is null and revoked_at is null;
  if not found then raise exception 'invitation_not_available'; end if;
  insert into public.audit_logs(actor_id,action,resource_type,resource_id,result)
    values(auth.uid(),'REVOKE_INVITATION','invitation',target_invitation::text,'success');
end $$;

create function private.consume_signup_invitation()
returns trigger language plpgsql security definer set search_path = '' as $$
declare code text;
begin
  code := upper(regexp_replace(coalesce(new.raw_user_meta_data->>'invite_code',''),'[[:space:]-]','','g'));
  if char_length(code) <> 24 then raise exception 'valid_invitation_required' using errcode='22023'; end if;
  update private.invitations set used_by=new.id, used_at=now()
    where code_hash=encode(extensions.digest(code,'sha256'),'hex')
      and used_at is null and revoked_at is null and expires_at>now();
  if not found then raise exception 'invitation_invalid_expired_or_used' using errcode='22023'; end if;
  update auth.users set raw_user_meta_data=raw_user_meta_data-'invite_code' where id=new.id;
  return new;
end $$;
revoke all on function private.consume_signup_invitation() from public,anon,authenticated;
create trigger require_signup_invitation after insert on auth.users
  for each row execute function private.consume_signup_invitation();

-- Auth updates the user again while creating the first session. Prevent that
-- update (and later client metadata updates) from persisting the consumed code.
create function private.strip_invitation_metadata()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.raw_user_meta_data := new.raw_user_meta_data-'invite_code';
  return new;
end $$;
revoke all on function private.strip_invitation_metadata() from public,anon,authenticated;
create trigger strip_invitation_metadata before update on auth.users
  for each row execute function private.strip_invitation_metadata();

create function public.admin_me()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_staff() then raise exception 'staff_required' using errcode='42501'; end if;
  return (select jsonb_build_object('id',p.id,'role',p.role,'is_blocked',p.is_blocked,'display_name',p.display_name,'email',u.email)
    from public.profiles p join auth.users u on u.id=p.id where p.id=auth.uid());
end $$;

create function private.admin_user_rows()
returns setof jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_admin() then raise exception 'admin_required' using errcode='42501'; end if;
  return query select jsonb_build_object('id',p.id,'display_name',p.display_name,'handle',p.handle,'role',p.role,
    'is_blocked',p.is_blocked,'created_at',p.created_at,'email',u.email,
    'invite_note',i.note,'invite_hint',i.code_hint,'registration_source',case when i.id is null then '历史账号' else '邀请码' end)
    from public.profiles p join auth.users u on u.id=p.id left join private.invitations i on i.used_by=p.id;
end $$;

create function private.admin_invitation_rows()
returns setof jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_admin() then raise exception 'admin_required' using errcode='42501'; end if;
  return query select jsonb_build_object('id',i.id,'code_hint',i.code_hint,'note',i.note,'expires_at',i.expires_at,
    'created_at',i.created_at,'used_at',i.used_at,'used_email',u.email,
    'status',case when i.used_at is not null then 'used' when i.revoked_at is not null then 'revoked' when i.expires_at<=now() then 'expired' else 'available' end)
    from private.invitations i left join auth.users u on u.id=i.used_by;
end $$;
revoke all on function private.admin_user_rows(),private.admin_invitation_rows() from public,anon;
grant execute on function private.admin_user_rows(),private.admin_invitation_rows() to authenticated;

create function public.admin_records(kind text, search text default '', status text default '', page integer default 0, ascending boolean default false)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare source text; result jsonb;
begin
  if not private.is_staff() then raise exception 'staff_required' using errcode='42501'; end if;
  if page is null or page not between 0 and 10000 or char_length(search)>200 then raise exception 'invalid_query' using errcode='22023'; end if;
  if kind in ('users','invites','announcements','releases','audit') and not private.is_admin() then
    raise exception 'admin_required' using errcode='42501';
  end if;
  source := case kind
    when 'users' then 'select r as row from private.admin_user_rows() r'
    when 'invites' then 'select r as row from private.admin_invitation_rows() r'
    when 'content' then 'select to_jsonb(p)||jsonb_build_object(''content_type'',''post'') as row from public.posts p where p.deleted_at is null union all select to_jsonb(c)||jsonb_build_object(''content_type'',''comment'') from public.comments c where c.deleted_at is null union all select to_jsonb(c)||jsonb_build_object(''content_type'',''listing_comment'') from public.listing_comments c where c.deleted_at is null'
    when 'listings' then 'select to_jsonb(t) as row from public.listings t'
    when 'orders' then 'select to_jsonb(t) as row from public.orders t'
    when 'reports' then 'select to_jsonb(t) as row from public.reports t'
    when 'announcements' then 'select to_jsonb(t) as row from public.announcements t'
    when 'releases' then 'select to_jsonb(t) as row from public.app_releases t'
    when 'audit' then 'select to_jsonb(t) as row from public.audit_logs t'
    else null end;
  if source is null then raise exception 'invalid_record_kind' using errcode='22023'; end if;
  execute 'with source as ('||source||'), filtered as (select row from source where ($1='''' or position(lower($1) in lower(row::text))>0) and ($2='''' or case when $3=''users'' then case when (row->>''is_blocked'')::boolean then ''blocked'' else ''active'' end else coalesce(row->>''moderation_status'',row->>''status'',row->>''result'') end=$2)), paged as (select row from filtered order by row->>''created_at'' '||case when ascending then 'asc' else 'desc' end||', row->>''id'' limit 25 offset $4) select jsonb_build_object(''rows'',coalesce((select jsonb_agg(row) from paged),''[]''::jsonb),''total'',(select count(*) from filtered))'
    into result using coalesce(search,''),coalesce(status,''),kind,page*25;
  return result;
end $$;

create function public.admin_overview()
returns jsonb language plpgsql security invoker set search_path = '' as $$
begin
  if not private.is_staff() then raise exception 'staff_required' using errcode='42501'; end if;
  return jsonb_build_object('users',(select count(*) from public.profiles),'records',(select count(*) from public.time_entries),
    'pending',(select count(*) from public.reports where status='pending'),'orders',(select count(*) from public.orders),
    'trend',(select jsonb_agg(jsonb_build_object('date',day::date,'minutes',coalesce(minutes,0)) order by day) from generate_series(current_date-6,current_date,interval '1 day') day left join
      (select starts_at::date as date,sum(duration_seconds)/60 as minutes from public.time_entries where starts_at>=current_date-6 and deleted_at is null group by starts_at::date) t on t.date=day::date));
end $$;

create or replace function public.admin_set_user_role(target_user uuid, next_role public.app_role)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_super_admin() then raise exception 'super_admin_required' using errcode='42501'; end if;
  if target_user=auth.uid() then raise exception 'cannot_change_own_role'; end if;
  update public.profiles set role=next_role where id=target_user;
  if not found then raise exception 'profile_not_found'; end if;
  delete from auth.sessions where user_id=target_user;
  insert into public.audit_logs(actor_id,action,resource_type,resource_id,result,metadata)
    values(auth.uid(),'SET_USER_ROLE','profile',target_user::text,'success',jsonb_build_object('role',next_role));
end $$;

create function public.admin_save_draft(kind text, id uuid, "values" jsonb)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_admin() then raise exception 'admin_required' using errcode='42501'; end if;
  if kind='announcements' then
    if char_length(btrim(coalesce("values"->>'title',''))) not between 1 and 160 or char_length(btrim(coalesce("values"->>'body',''))) not between 1 and 10000 then raise exception 'invalid_announcement'; end if;
    update public.announcements set title=btrim("values"->>'title'),body=btrim("values"->>'body') where announcements.id=admin_save_draft.id and status='draft';
  elsif kind='releases' then
    if coalesce("values"->>'apk_url','') !~ '^https://' or coalesce("values"->>'checksum','') !~* '^[a-f0-9]{64}$' or coalesce("values"->>'version_code','') !~ '^[1-9][0-9]{0,9}$' or char_length(btrim(coalesce("values"->>'version_name',''))) not between 1 and 40 then raise exception 'invalid_release'; end if;
    update public.app_releases set version_code=("values"->>'version_code')::integer,version_name=btrim("values"->>'version_name'),notes=coalesce("values"->>'notes',''),apk_url="values"->>'apk_url',checksum_sha256=lower("values"->>'checksum') where app_releases.id=admin_save_draft.id and status='draft';
  else raise exception 'invalid_draft_kind'; end if;
  if not found then raise exception 'draft_not_found_or_already_published'; end if;
  insert into public.audit_logs(actor_id,action,resource_type,resource_id,result) values(auth.uid(),'SAVE_DRAFT',kind,id::text,'success');
end $$;

revoke all on function public.admin_create_invitations(integer,integer,text),public.admin_revoke_invitation(uuid),public.admin_me(),public.admin_records(text,text,text,integer,boolean),public.admin_overview(),public.admin_set_user_role(uuid,public.app_role),public.admin_save_draft(text,uuid,jsonb) from public,anon;
grant execute on function public.admin_create_invitations(integer,integer,text),public.admin_revoke_invitation(uuid),public.admin_me(),public.admin_records(text,text,text,integer,boolean),public.admin_overview(),public.admin_set_user_role(uuid,public.app_role),public.admin_save_draft(text,uuid,jsonb) to authenticated;
notify pgrst,'reload schema';
commit;
