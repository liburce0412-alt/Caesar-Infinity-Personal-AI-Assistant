begin;

-- Preserve all existing courses and allow screenshots with periods but no end clocks.
alter table public.course_schedules
  add column period_start smallint not null default 0,
  add column period_end smallint not null default 0,
  add column period_start_times text not null default '';
alter table public.course_schedules drop constraint course_schedules_start_minute_check;
alter table public.course_schedules drop constraint course_schedules_end_minute_check;
alter table public.course_schedules drop constraint course_schedules_check;
alter table public.course_schedules add constraint course_schedule_coordinates check (
  (start_minute between 0 and 1439 and end_minute between 1 and 1440 and end_minute > start_minute)
  or (period_start between 1 and 24 and period_end between period_start and 24
      and start_minute between -1 and 1439 and end_minute = -1)
);
alter table public.course_schedules add constraint course_period_coordinates check (
  (period_start = 0 and period_end = 0) or (period_start between 1 and 24 and period_end between period_start and 24)
);
alter table public.course_schedules add constraint course_period_axis_size check (length(period_start_times) <= 160);

drop function public.sync_course_schedule(uuid, text, smallint, smallint, smallint, text, text, text, text, integer, timestamptz);
create or replace function public.sync_course_schedule(
  client_course uuid,
  course_name text,
  course_weekday smallint,
  course_start_minute smallint,
  course_end_minute smallint,
  course_location text,
  course_teacher text,
  course_weeks text,
  course_source_hash text,
  client_version integer,
  client_updated_at timestamptz,
  course_period_start smallint default 0,
  course_period_end smallint default 0,
  course_period_start_times text default ''
)
returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  current_course public.course_schedules%rowtype;
  is_conflict boolean := false;
begin
  if auth.uid() is null then raise exception 'authentication_required' using errcode = '28000'; end if;
  if char_length(btrim(coalesce(course_name, ''))) not between 1 and 160
    or course_weekday not between 1 and 7
    or not ((course_start_minute between 0 and 1439 and course_end_minute between 1 and 1440 and course_end_minute > course_start_minute)
      or (course_period_start between 1 and 24 and course_period_end between course_period_start and 24
        and course_start_minute between -1 and 1439 and course_end_minute = -1))
    or course_source_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid_course_schedule' using errcode = '22023';
  end if;

  select * into current_course from public.course_schedules
  where user_id = auth.uid() and (client_id = client_course or source_hash = course_source_hash)
  order by case when client_id = client_course then 0 else 1 end
  limit 1 for update;

  if not found then
    insert into public.course_schedules(user_id, client_id, name, weekday, start_minute, end_minute, location, teacher, weeks, source_hash, version, updated_at, period_start, period_end, period_start_times)
    values(auth.uid(), client_course, btrim(course_name), course_weekday, course_start_minute, course_end_minute, coalesce(course_location, ''), coalesce(course_teacher, ''), coalesce(course_weeks, ''), course_source_hash, greatest(client_version, 1), coalesce(client_updated_at, now()), course_period_start, course_period_end, course_period_start_times)
    returning * into current_course;
  elsif current_course.deleted_at is not null then
    is_conflict := true;
  elsif client_version >= current_course.version or client_updated_at >= current_course.updated_at then
    update public.course_schedules
    set name = btrim(course_name), weekday = course_weekday, start_minute = course_start_minute,
        end_minute = course_end_minute, location = coalesce(course_location, ''), teacher = coalesce(course_teacher, ''),
        weeks = coalesce(course_weeks, ''), source_hash = course_source_hash,
        period_start = course_period_start, period_end = course_period_end, period_start_times = course_period_start_times,
        version = greatest(current_course.version + 1, client_version), deleted_at = null,
        updated_at = greatest(coalesce(client_updated_at, now()), now())
    where id = current_course.id returning * into current_course;
  else
    is_conflict := true;
  end if;

  return jsonb_build_object('conflict', is_conflict, 'entry', to_jsonb(current_course));
end;
$$;


revoke all on function public.sync_course_schedule(uuid, text, smallint, smallint, smallint, text, text, text, text, integer, timestamptz, smallint, smallint, text) from public, anon;
grant execute on function public.sync_course_schedule(uuid, text, smallint, smallint, smallint, text, text, text, text, integer, timestamptz, smallint, smallint, text) to authenticated, service_role;

commit;
