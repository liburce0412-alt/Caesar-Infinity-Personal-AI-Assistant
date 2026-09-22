begin;

-- Server reports use the product's Asia/Shanghai reporting zone. Minute totals
-- truncate each completed entry, matching Android rather than combining fractions.
create or replace function public.admin_overview()
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare today date := (now() at time zone 'Asia/Shanghai')::date;
begin
  if not private.is_staff() then raise exception 'staff_required' using errcode='42501'; end if;
  return jsonb_build_object('users',(select count(*) from public.profiles),'records',(select count(*) from public.time_entries where deleted_at is null),
    'pending',(select count(*) from public.reports where status='pending'),'orders',(select count(*) from public.orders),
    'trend',(select jsonb_agg(jsonb_build_object('date',day::date,'minutes',coalesce(minutes,0)) order by day)
      from generate_series(today-6,today,interval '1 day') day left join
      (select (ends_at at time zone 'Asia/Shanghai')::date as date,sum(duration_seconds / 60) as minutes
       from public.time_entries where ends_at >= ((today-6)::timestamp at time zone 'Asia/Shanghai')
         and ends_at <= now() and duration_seconds >= 60 and deleted_at is null
       group by (ends_at at time zone 'Asia/Shanghai')::date) t on t.date=day::date));
end $$;

create or replace function private.refresh_time_achievements(target_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  entry_count integer := 0;
  total_minutes integer := 0;
  focus_count integer := 0;
  category_count integer := 0;
  longest_streak integer := 0;
begin
  select
    count(*)::integer,
    coalesce(sum(duration_seconds / 60), 0)::integer,
    count(*) filter(where category = '专注' and duration_seconds >= 1500)::integer,
    count(distinct nullif(trim(category), ''))::integer
  into entry_count,total_minutes,focus_count,category_count
  from public.time_entries
  where user_id=target_user and deleted_at is null and duration_seconds >= 60 and ends_at <= now();

  with days as (
    select distinct (ends_at at time zone 'Asia/Shanghai')::date as day
    from public.time_entries
    where user_id=target_user and deleted_at is null and duration_seconds >= 60 and ends_at <= now()
  ), grouped as (
    select day,day-(row_number() over(order by day))::integer as island
    from days
  ), streaks as (
    select count(*)::integer as length from grouped group by island
  )
  select coalesce(max(length),0) into longest_streak from streaks;

  insert into public.achievements(user_id,achievement_id,progress)
  select target_user,achievement_id,progress
  from (values
    ('first_light',entry_count >= 1,jsonb_build_object('timeEntries',entry_count)),
    ('focus_departure',focus_count >= 1,jsonb_build_object('focusSessions',focus_count)),
    ('steady_rhythm',longest_streak >= 7,jsonb_build_object('streakDays',longest_streak)),
    ('deep_orbit',total_minutes >= 600,jsonb_build_object('totalMinutes',total_minutes)),
    ('time_architect',entry_count >= 25,jsonb_build_object('timeEntries',entry_count)),
    ('full_spectrum',category_count >= 5,jsonb_build_object('categoryCount',category_count)),
    ('hundred_hours',total_minutes >= 6000,jsonb_build_object('totalMinutes',total_minutes))
  ) as candidate(achievement_id,unlocked,progress)
  where unlocked
  on conflict(user_id,achievement_id) do update set progress=excluded.progress;

  update public.profiles
  set streak_days=greatest(streak_days,longest_streak),updated_at=now()
  where id=target_user;
end;
$$;

commit;
