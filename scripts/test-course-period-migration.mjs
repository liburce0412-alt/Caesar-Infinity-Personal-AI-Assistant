// Isolated PostgreSQL regression: additive course schema, old client compatibility, ownership.
// Setup: npm install --prefix artifacts/audit-validation --no-save --ignore-scripts @electric-sql/pglite@0.3.14
// Run: node scripts/test-course-period-migration.mjs
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../artifacts/audit-validation/package.json', import.meta.url));
const {PGlite} = require('@electric-sql/pglite');
const db = new PGlite();
const owner='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const core=await readFile('supabase/migrations/20260822010000_core_schema.sql','utf8');
try {
 await db.exec(`create role anon; create role authenticated; create role service_role; create schema auth;
 create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
 create table profiles(id uuid primary key); insert into profiles values('${owner}');
 create type public.sync_state as enum ('local_only','pending','synced','conflict','failed');
 create function public.soft_delete_time_entry(uuid,integer) returns integer language sql as $$select 1$$;`);
 for(const table of ['time_entries','course_schedules']) await db.exec(core.match(new RegExp(`create table if not exists public.${table} \\([\\s\\S]*?\\n\\);`))[0]);
 await db.exec(await readFile('supabase/migrations/20260822061625_offline_sync_rpc.sql','utf8'));
 await db.exec(`select set_config('request.jwt.claim.sub','${owner}',false)`);
 const call=(n,start=480,end=540,periods=false)=>db.query(`select sync_course_schedule($1::uuid,$2::text,1::smallint,$3::smallint,$4::smallint,'A','Teacher','Week5',$5::text,1,now()${periods?',6::smallint,7::smallint,\'480,530,595,645,695,840,890,955,1005,1120,1170,1220\'':''}) as result`,[`00000000-0000-0000-0000-${String(n).padStart(12,'0')}`,'Course'+n,start,end,String(n).padStart(64,'0')]);
 await call(1);
 await db.exec(await readFile('supabase/migrations/20261004144535_course_period_layout.sql','utf8'));
 assert.equal((await db.query('select period_start from course_schedules')).rows[0].period_start,0);
 await db.exec('set role authenticated');
 const a=(await call(2,840,-1,true)).rows[0].result.entry;
 assert.equal(a.period_start,6); assert.equal(a.period_end,7); assert.equal(a.end_minute,-1);
 assert.equal(a.period_start_times.split(',').length,12);
 assert.equal((await call(3)).rows[0].result.entry.period_start,0);
 await assert.rejects(call(4,-1,-1,false),/invalid_course_schedule/);
 await assert.rejects(call(4,1500,-1,true),/invalid_course_schedule/);
 await db.exec(`select set_config('request.jwt.claim.sub','',false)`);
 await assert.rejects(call(5),/authentication_required/);
 console.log('PASS: existing rows preserved, period/axis roundtrip, unknown end preserved, old 11-argument client works, invalid coordinates and anonymous calls rejected.');
} finally { await db.close(); }
