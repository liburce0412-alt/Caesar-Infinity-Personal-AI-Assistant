// Isolated PostgreSQL/WASM regression harness. No network, credentials or production state.
// Setup: npm install --prefix artifacts/audit-validation --no-save --ignore-scripts @electric-sql/pglite@0.3.14
// Run: node scripts/test-audit-migrations.mjs
import { createRequire } from 'node:module'
import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const require = createRequire(new URL('../artifacts/audit-validation/package.json', import.meta.url))
const { PGlite } = require('@electric-sql/pglite')
const db = new PGlite()
const alice = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const bob = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
const stranger = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
const conversation = 'dddddddd-dddd-dddd-dddd-dddddddddddd'
const id = n => `00000000-0000-0000-0000-${String(n).padStart(12,'0')}`
const q = (sql, params=[]) => db.query(sql,params)
try {
  await db.exec(`
    create role anon; create role authenticated;
    create schema auth; create schema private;
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    create function private.is_staff() returns boolean language sql stable as $$select auth.uid()='${alice}'::uuid$$;
    grant usage on schema auth,private to authenticated;
    create table profiles(id uuid primary key,display_name text,streak_days integer default 0,updated_at timestamptz);
    create table listings(id uuid primary key,title text);
    create table conversations(id uuid primary key,listing_id uuid,updated_at timestamptz default now());
    create table conversation_members(conversation_id uuid,user_id uuid,last_read_at timestamptz,created_at timestamptz default now(),primary key(conversation_id,user_id));
    create table messages(id uuid primary key,conversation_id uuid,sender_id uuid,body text,created_at timestamptz,deleted_at timestamptz);
    create table time_entries(id uuid primary key default gen_random_uuid(),user_id uuid,updated_at timestamptz default now(),starts_at timestamptz,ends_at timestamptz,
      duration_seconds integer generated always as (greatest(0,extract(epoch from (ends_at-starts_at))::integer)) stored,deleted_at timestamptz,category text default '学习');
    create table course_schedules(id uuid primary key,user_id uuid,updated_at timestamptz);
    create table reports(status text); create table orders(id uuid);
    create table achievements(user_id uuid,achievement_id text,progress jsonb,primary key(user_id,achievement_id));
    create function mark_conversation_read(uuid) returns void language sql as $$select$$;
    insert into profiles(id,display_name) values('${alice}','Alice'),('${bob}','Bob');
    insert into conversations(id) values('${conversation}');
    insert into conversation_members(conversation_id,user_id) values('${conversation}','${alice}'),('${conversation}','${bob}');
    grant select on all tables in schema public to authenticated;
  `)
  for(const name of ['20260922065331_message_read_watermark.sql','20260922070550_completed_time_statistics.sql']) {
    await db.exec(await readFile(new URL(`../supabase/migrations/${name}`,import.meta.url),'utf8'))
  }
  await db.exec(`insert into messages(id,conversation_id,sender_id,body,created_at)
    select ('00000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,'${conversation}','${bob}',n::text,'2026-09-22T00:00:00Z' from generate_series(1,201) n;`)
  const page = await q('select * from messages order by created_at desc,id desc limit 200')
  assert.equal(page.rows[0].body,'201')
  assert.equal(page.rows.at(-1).body,'2')
  const older = await q('select * from messages where (created_at,id)<($1,$2) order by created_at desc,id desc limit 200',[page.rows.at(-1).created_at,id(2)])
  assert.equal(older.rows.length,1)
  assert.equal(older.rows[0].body,'1')
  await q("select set_config('request.jwt.claim.sub',$1,false)",[alice])
  await db.exec('set role authenticated')
  await q('select mark_conversation_read_through($1,$2)',[conversation,id(200)])
  assert.equal((await q('select unread_count from list_conversation_summaries()')).rows[0].unread_count,1)
  await q('select mark_conversation_read_through($1,$2)',[conversation,id(2)])
  assert.equal((await q('select unread_count from list_conversation_summaries()')).rows[0].unread_count,1)
  await assert.rejects(q('select mark_conversation_read($1)',[conversation]),/permission denied/)
  await assert.rejects(q('select mark_conversation_read_through($1,$2)',[conversation,id(999)]),/message_not_available/)
  await q("select set_config('request.jwt.claim.sub',$1,false)",[stranger])
  await assert.rejects(q('select mark_conversation_read_through($1,$2)',[conversation,id(201)]),/conversation_not_available/)
  await q("select set_config('request.jwt.claim.sub','',false)")
  await assert.rejects(q('select mark_conversation_read_through($1,$2)',[conversation,id(201)]),/authentication_required/)
  await db.exec('reset role')
  await q("select set_config('request.jwt.claim.sub',$1,false)",[alice])
  await db.exec(`
    insert into time_entries(user_id,starts_at,ends_at)
      select '${alice}', boundary-interval '10 minutes',boundary+interval '20 minutes'
      from (select (((now() at time zone 'Asia/Shanghai')::date-1)::timestamp at time zone 'Asia/Shanghai') boundary) t;
    insert into time_entries(user_id,starts_at,ends_at) values
      ('${alice}',now()+interval '1 day',now()+interval '2 days'),
      ('${alice}',now(),now()),
      ('${alice}',now()-interval '30 seconds',now());
  `)
  await db.exec('set role authenticated')
  const overview=(await q('select admin_overview() as value')).rows[0].value
  assert.equal(overview.trend.reduce((sum,day)=>sum+day.minutes,0),30)
  const yesterday = (await q("select ((now() at time zone 'Asia/Shanghai')::date-1)::text as day")).rows[0].day
  assert.equal(overview.trend.find(day=>day.date===yesterday).minutes,30)
  await db.exec('reset role')
  await q('select private.refresh_time_achievements($1)',[alice])
  const progress = (await q("select progress from achievements where achievement_id='first_light'")).rows[0].progress
  assert.equal(progress.timeEntries,1)
  console.log('PASS: 201-message paging; same-timestamp read cursor; no rewind; legacy revoke; missing message/nonmember/anonymous denied; completed-day and valid-minute aggregation.')
} finally { await db.close() }
