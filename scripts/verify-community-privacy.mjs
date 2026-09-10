// Isolated PostgreSQL/WASM verification. Never connects to the configured Supabase project.
// npm install --prefix artifacts/privacy-db --no-save --package-lock=false @electric-sql/pglite
// node scripts/verify-community-privacy.mjs
import { PGlite } from '../artifacts/privacy-db/node_modules/@electric-sql/pglite/dist/index.js'
import { readFile, readdir } from 'node:fs/promises'
import assert from 'node:assert/strict'

const db = new PGlite()
let checks = 0
const owner = '10000000-0000-4000-8000-000000000001'
const stranger = '10000000-0000-4000-8000-000000000002'
const staff = '10000000-0000-4000-8000-000000000003'
let privatePost = '20000000-0000-4000-8000-000000000001'
let publicPost = '20000000-0000-4000-8000-000000000002'
let privateWish = '30000000-0000-4000-8000-000000000001'
let publicWish = '30000000-0000-4000-8000-000000000002'
let freeWish = '30000000-0000-4000-8000-000000000003'
const legacyPost = '20000000-0000-4000-8000-000000000003'
const run = sql => db.exec(sql)
const scalar = async sql => Object.values((await db.query(sql)).rows[0])[0]
const equal = async (sql, expected) => { assert.equal(await scalar(sql), expected, sql); checks++ }
const denied = async sql => {
  await assert.rejects(() => run(sql)); checks++
}
const asUser = async id => run(`reset role; set request.jwt.claim.sub = '${id}'; set role authenticated;`)

try {
  // Supabase service schemas, without a network service or credentials.
  await run(`
    create role anon; create role authenticated; create role service_role bypassrls;
    create schema auth; create schema storage;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    grant usage on schema auth, storage to authenticated, anon;
    create table storage.buckets(id text primary key, name text, public boolean, file_size_limit bigint, allowed_mime_types text[]);
    create table storage.objects(id uuid primary key default gen_random_uuid(), bucket_id text references storage.buckets(id), name text);
    alter table storage.objects enable row level security;
    grant select,insert,update,delete on storage.objects to authenticated;
    create function storage.foldername(text) returns text[] language sql immutable as $$ select string_to_array($1, '/') $$;
  `)
  for (const file of [
    '20260822010000_core_schema.sql', '20260822011000_security_rls.sql',
    '20260822012000_transactions_rpc.sql', '20260822014000_storage_and_seed.sql',
    '20260822054106_messaging_order_workflows.sql', '20260822060957_admin_workflows.sql',
    '20260822233000_community_interaction_consistency.sql',
  ]) {
    // PGlite has gen_random_uuid built in; search indexes are irrelevant to these access tests.
    const sql = (await readFile(`supabase/migrations/${file}`, 'utf8'))
      .replace(/^create extension .*;$/gm, '')
      .replace(/^create index .*gin_trgm_ops.*;$/gm, '')
    await run(sql)
  }
  await run(`insert into auth.users(id,email) values
    ('${owner}','owner@example.test'),('${stranger}','stranger@example.test'),('${staff}','staff@example.test');
    update profiles set role='admin' where id='${staff}';
    insert into posts(id,author_id,body,moderation_status) values('${legacyPost}','${owner}','Legacy public post','approved');`)
  const migration = (await readdir('supabase/migrations')).find(f => f.endsWith('_community_private_wishes.sql'))
  await run(await readFile(`supabase/migrations/${migration}`, 'utf8'))
  await equal(`select is_public from posts where id='${legacyPost}'`, true)
  await asUser(owner)
  privatePost = await scalar(`insert into posts(author_id,body) values('${owner}','Private note') returning id`)
  publicPost = await scalar(`insert into posts(author_id,body,is_public) values('${owner}','Public note',true) returning id`)
  privateWish = await scalar(`insert into listings(seller_id,title,category,condition,price_cents) values('${owner}','Private wish','其他','良好',null) returning id`)
  publicWish = await scalar(`insert into listings(seller_id,title,category,condition,price_cents,is_public) values('${owner}','Unpriced public wish','其他','良好',null,true) returning id`)
  freeWish = await scalar(`insert into listings(seller_id,title,category,condition,price_cents,is_public) values('${owner}','Free public wish','其他','良好',0,true) returning id`)
  await equal(`select is_public from posts where id='${privatePost}'`, false)
  await equal(`select is_public from listings where id='${privateWish}'`, false)
  await equal(`select price_cents is null from listings where id='${privateWish}'`, true)
  // Mark both public and private content approved to prove moderation cannot expose private data.
  await run(`reset role; update posts set moderation_status='approved'; update listings set moderation_status='approved';`)
  await asUser(owner)
  await run(`select create_comment('${privatePost}','Private comment');`)
  await run(`insert into storage.objects(bucket_id,name) values('post-private','${owner}/photo.jpg');`)
  await equal(`select count(*) from storage.objects where bucket_id='post-private'`, 1)
  for (const viewer of [stranger, staff]) {
    await asUser(viewer)
    await equal(`select count(*) from posts where id='${privatePost}'`, 0)
    await equal(`select count(*) from listings where id='${privateWish}'`, 0)
    await equal(`select count(*) from comments where post_id='${privatePost}'`, 0)
    await equal(`select count(*) from storage.objects where bucket_id='post-private'`, 0)
    await equal(`select count(*) from posts where id='${publicPost}'`, 1)
    for (const sql of [
      `select toggle_post_like('${privatePost}')`, `select toggle_post_bookmark('${privatePost}')`,
      `select create_comment('${privatePost}','Leak attempt')`, `select toggle_favorite('${privateWish}')`,
      `select open_conversation('${owner}','${privateWish}')`, `select create_order('${privateWish}')`,
      `select create_order('${publicWish}')`,
    ]) await denied(sql)
    await denied(`insert into storage.objects(bucket_id,name) values('post-private','${owner}/foreign.jpg')`)
    await equal(`with d as (delete from storage.objects where bucket_id='post-private' returning *) select count(*) from d`, 0)
  }
  await asUser(stranger)
  await run(`select toggle_post_like('${publicPost}'); select create_comment('${publicPost}','Hello');`)
  await run(`select open_conversation('${owner}','${publicWish}');`)
  await run(`select create_order('${freeWish}');`)
  await equal(`select price_cents from orders where listing_id='${freeWish}'`, 0)
  await asUser(owner)
  await equal(`select count(*) from posts where id='${privatePost}'`, 1)
  await equal(`select count(*) from listings where id='${privateWish}'`, 1)
  await equal(`select count(*) from storage.objects where bucket_id='post-private'`, 1)
  await denied(`update posts set is_public=true where id='${privatePost}'`)
  await run('reset role')
  const journalMigration = (await readdir('supabase/migrations')).find(f => f.endsWith('_wish_journal_management.sql'))
  await run(await readFile(`supabase/migrations/${journalMigration}`, 'utf8'))
  await asUser(owner)
  const recordedAt = await scalar(`select created_at::text from listings where id='${privateWish}'`)
  await run(`select edit_wish('${privateWish}','A meaningful wish','One small step',null,'','2026-10-01',null)`)
  await equal(`select target_date::text from listings where id='${privateWish}'`, '2026-10-01')
  await equal(`select created_at::text from listings where id='${privateWish}'`, recordedAt)
  await run(`select create_wish_comment('${privateWish}','Today I made progress')`)
  await equal(`select count(*) from listing_comments where listing_id='${privateWish}'`, 1)
  await run(`select complete_wish('${privateWish}','It finally happened',null)`)
  await equal(`select completed_at is not null and completion_note='It finally happened' from listings where id='${privateWish}'`, true)
  await run(`select edit_community_post('${privatePost}','Changed private note','',false,null)`)
  await equal(`select body from posts where id='${privatePost}'`, 'Changed private note')
  for (const viewer of [stranger, staff]) {
    await asUser(viewer)
    await equal(`select count(*) from listing_comments where listing_id='${privateWish}'`, 0)
    for (const sql of [
      `select edit_wish('${privateWish}','Hijack','',null,'',null,null)`,
      `select delete_wish('${privateWish}')`, `select complete_wish('${privateWish}','Hijack',null)`,
      `select create_wish_comment('${privateWish}','Leak')`,
      `select edit_community_post('${privatePost}','Hijack','',false,null)`, `select delete_community_post('${privatePost}')`,
    ]) await denied(sql)
  }
  await asUser(stranger)
  await run(`select create_wish_comment('${publicWish}','Wishing you well')`)
  await equal(`select count(*) from listing_comments where listing_id='${publicWish}'`, 1)
  await asUser(owner)
  // Match the Android PUBLIC and MINE predicates before applying the 50-row limit.
  await equal(`select count(*) from posts where is_public=true and moderation_status='approved' and id='${privatePost}'`, 0)
  await equal(`select count(*) from posts where author_id='${owner}' and id='${privatePost}'`, 1)
  await equal(`select count(*) from listings where seller_id='${owner}' and status<>'removed' and id='${privateWish}'`, 1)
  await equal(`select count(*) from listings where is_public=true and moderation_status='approved' and status='active' and id='${privateWish}'`, 0)
  await run(`select edit_wish('${privateWish}','Changed again','',null,'',null,'{}')`)
  await equal(`select target_date is null and cardinality(media_paths)=0 from listings where id='${privateWish}'`, true)
  await run(`select delete_wish('${privateWish}'); select delete_community_post('${privatePost}')`)
  await equal(`select count(*) from listings where id='${privateWish}'`, 0)
  await equal(`select count(*) from listing_comments where listing_id='${privateWish}'`, 0)
  await equal(`select count(*) from posts where id='${privatePost}'`, 0)
  await denied(`select create_wish_comment('${privateWish}','After deletion')`)
  await denied(`select toggle_favorite('${privateWish}')`)
  await run(`update posts set body='Edited public note' where id='${publicPost}'`)
  await equal(`select moderation_status from posts where id='${publicPost}'`, 'pending')
  await run(`update listings set title='Edited public wish' where id='${publicWish}'`)
  await equal(`select moderation_status from listings where id='${publicWish}'`, 'pending')
  await run('reset role')
  await run(await readFile('supabase/migrations/20260908123909_edit_community_visibility.sql', 'utf8'))
  await equal(`select count(*) from storage.buckets where id in ('post-media','listing-media','post-private','listing-private') and public`, 0)
  await asUser(owner)
  await run(`insert into storage.objects(bucket_id,name) values
    ('post-media','${owner}/legacy.jpg'), ('listing-private','${owner}/wish.jpg'), ('listing-media','${owner}/memory.jpg');`)
  await run(`select edit_community_post_visibility('${publicPost}','Changed and hidden','',false,array['${owner}/legacy.jpg'],false);`)
  await run(`select edit_wish_visibility('${publicWish}','Hidden wish','',null,'',null,array['${owner}/wish.jpg'],false);`)
  await run(`select complete_wish('${publicWish}','A memory',array['${owner}/memory.jpg']);`)
  await equal(`select is_public from posts where id='${publicPost}'`, false)
  await equal(`select is_public from listings where id='${publicWish}'`, false)
  await equal(`select count(*) from storage.objects where name in ('${owner}/legacy.jpg','${owner}/wish.jpg','${owner}/memory.jpg')`, 3)
  for (const viewer of [stranger, staff]) {
    await asUser(viewer)
    await equal(`select count(*) from posts where id='${publicPost}'`, 0)
    await equal(`select count(*) from listings where id='${publicWish}'`, 0)
    await equal(`select count(*) from storage.objects where name in ('${owner}/legacy.jpg','${owner}/wish.jpg','${owner}/memory.jpg')`, 0)
    await denied(`select edit_community_post_visibility('${publicPost}','Hijack','',false,null,true)`)
    await denied(`select edit_wish_visibility('${publicWish}','Hijack','',null,'',null,null,true)`)
    await equal(`with d as (delete from storage.objects where name='${owner}/legacy.jpg' returning *) select count(*) from d`, 0)
  }
  await asUser(owner)
  await run(`select edit_community_post_visibility('${publicPost}','Share again','',false,null,true);
    select edit_wish_visibility('${publicWish}','Share wish again','',null,'',null,null,true);`)
  await equal(`select moderation_status from posts where id='${publicPost}'`, 'pending')
  await equal(`select moderation_status from listings where id='${publicWish}'`, 'pending')
  await asUser(staff)
  await equal(`select count(*) from storage.objects where name in ('${owner}/legacy.jpg','${owner}/wish.jpg','${owner}/memory.jpg')`, 3)
  await asUser(stranger)
  await equal(`select count(*) from storage.objects where name in ('${owner}/legacy.jpg','${owner}/wish.jpg','${owner}/memory.jpg')`, 0)
  await run(`reset role; update posts set moderation_status='approved' where id='${publicPost}';
    update listings set moderation_status='approved' where id='${publicWish}';`)
  await asUser(stranger)
  await equal(`select count(*) from storage.objects where name in ('${owner}/legacy.jpg','${owner}/wish.jpg','${owner}/memory.jpg')`, 3)
  await asUser(owner)
  await denied(`select edit_community_post_visibility('${publicPost}','Invalid','',false,array['${stranger}/foreign.jpg'],false)`)
  await equal(`select is_public from posts where id='${publicPost}'`, true)
  await run(`select edit_community_post_visibility('${publicPost}','Hidden again','',false,null,false)`)
  await asUser(stranger)
  await equal(`select count(*) from storage.objects where name='${owner}/legacy.jpg'`, 0)
  console.log(`PASS: ${checks} privacy/price assertions; real migrations + RLS + RPCs in isolated PostgreSQL/WASM.`)
} finally {
  await db.close()
}
