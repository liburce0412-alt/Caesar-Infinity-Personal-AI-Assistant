// Reconstruct a fresh destination from the read-only catalog/data export.
// The managed project is never modified. Run from the repository root.
import fs from 'node:fs'
import crypto from 'node:crypto'
const base = 'artifacts/alicloud-migration-20260908/private'
const schema = JSON.parse(fs.readFileSync(`${base}/source-schema.json`, 'utf8'))
// Table grants do not include explicit column ACLs. Reject an incomplete export
// before writing output; otherwise user-owned writes fail after a successful restore.
if (!Array.isArray(schema.column_grants) || !schema.column_grants.length) {
  throw Error('Source catalog is missing column_grants. Export explicit column ACLs (pg_attribute.attacl / aclexplode) before restoring; table_grants alone are insufficient.')
}
const data = JSON.parse(fs.readFileSync(`${base}/source-data.json`, 'utf8'))
const id = s => '"' + s.replaceAll('"', '""') + '"'
const lit = s => "'" + s.replaceAll("'", "''") + "'"
const out = ['BEGIN;', 'SET check_function_bodies = false;', 'SET search_path = public, extensions, pg_catalog;',
  'CREATE SCHEMA IF NOT EXISTS private;', 'REVOKE ALL ON SCHEMA private FROM PUBLIC, anon;',
  'GRANT USAGE ON SCHEMA private TO authenticated;', 'GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role;',
  'CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;']
for (const name of new Set(schema.enums.map(e => e.name))) {
  const values = schema.enums.filter(e => e.name === name).sort((a,b) => a.order-b.order).map(e => lit(e.value))
  out.push(`CREATE TYPE public.${id(name)} AS ENUM (${values.join(',')});`)
}
const tables = [...new Set(schema.columns.filter(c => c.table_schema === 'public').map(c => c.table_name))]
for (const table of tables) {
  const columns = schema.columns.filter(c => c.table_schema === 'public' && c.table_name === table)
  out.push(`CREATE TABLE public.${id(table)} (\n${columns.map(c => {
    if (c.is_generated !== 'NEVER' && !c.generation_expression) throw Error(`Missing generated expression ${table}.${c.column_name}`)
    let type = `${id(c.udt_schema)}.${id(c.udt_name.startsWith('_') ? c.udt_name.slice(1) : c.udt_name)}` + (c.data_type === 'ARRAY' ? '[]' : '')
    if (c.character_maximum_length) type += `(${c.character_maximum_length})`
    const identity = c.is_identity === 'YES' ? ` GENERATED ${c.identity_generation} AS IDENTITY` : c.is_generated !== 'NEVER' ? ` GENERATED ALWAYS AS (${c.generation_expression}) STORED` : ''
    return `${id(c.column_name)} ${type}${identity}${c.column_default ? ' DEFAULT '+c.column_default : ''}${c.is_nullable === 'NO' ? ' NOT NULL' : ''}`
  }).join(',\n')}\n);`)
}
for (const fn of schema.functions) out.push(fn.definition + ';')
for (const c of [...schema.constraints].sort((a,b) => Number(a.type==='f')-Number(b.type==='f'))) {
  out.push(`ALTER TABLE public.${id(c.table)} ADD CONSTRAINT ${id(c.name)} ${c.definition};`)
}
const constraintNames = new Set(schema.constraints.map(c => c.name))
for (const index of schema.indexes) if (!constraintNames.has(index.indexname)) out.push(index.indexdef+';')
for (const table of schema.rls) if (table.enabled) out.push(`ALTER TABLE public.${id(table.table)} ENABLE ROW LEVEL SECURITY;`)
for (const p of schema.policies) {
  const roles = p.roles.map(r => r === 'public' ? 'PUBLIC' : id(r)).join(', ')
  out.push(`CREATE POLICY ${id(p.policyname)} ON ${id(p.schemaname)}.${id(p.tablename)} AS ${p.permissive} FOR ${p.cmd} TO ${roles}${p.qual ? ' USING ('+p.qual+')' : ''}${p.with_check ? ' WITH CHECK ('+p.with_check+')' : ''};`)
}
for (const t of schema.triggers) out.push(t.definition+';')
for (const table of tables) out.push(`REVOKE ALL ON public.${id(table)} FROM PUBLIC, anon, authenticated, service_role;`)
for (const g of schema.table_grants) {
  if (['postgres','supabase_admin'].includes(g.grantee)) continue
  out.push(`GRANT ${g.privilege_type} ON ${id(g.table_schema)}.${id(g.table_name)} TO ${g.grantee==='PUBLIC'?'PUBLIC':id(g.grantee)}${g.is_grantable==='YES'?' WITH GRANT OPTION':''};`)
}
for (const g of schema.column_grants) {
  if (['postgres','supabase_admin'].includes(g.grantee)) continue
  if (!['SELECT','INSERT','UPDATE','REFERENCES'].includes(g.privilege_type)) throw Error('Unsupported column privilege')
  out.push(`GRANT ${g.privilege_type} (${id(g.column_name)}) ON ${id(g.table_schema)}.${id(g.table_name)} TO ${g.grantee==='PUBLIC'?'PUBLIC':id(g.grantee)}${g.is_grantable==='YES'?' WITH GRANT OPTION':''};`)
}
for (const fn of schema.functions) {
  const signature = `${id(fn.schema)}.${id(fn.name)}(${fn.identity})`
  out.push(`REVOKE ALL ON FUNCTION ${signature} FROM PUBLIC, anon, authenticated, service_role;`)
  // Catalog ACL items are grantee=privileges/grantor. These roles have simple names.
  for (const acl of fn.acl || ['=X/postgres']) {
    const match = acl.match(/^([^=]*)=([^/]*)\//)
    if (!match || !match[2].includes('X')) continue
    const role = match[1].replaceAll('"','') || 'PUBLIC'
    if (['postgres','supabase_admin'].includes(role)) continue
    out.push(`GRANT EXECUTE ON FUNCTION ${signature} TO ${role==='PUBLIC'?'PUBLIC':id(role)};`)
  }
}
out.push('COMMIT;')
fs.writeFileSync(`${base}/restore-schema.sql`, out.join('\n'))
// Schema alignment and auth column compatibility are validated on the destination.
// Old sessions remain in the rollback export; all clients reauthenticate after cutover.
const retained = Object.fromEntries(Object.entries(data).filter(([name]) => name.startsWith('public.') || ['auth.users','auth.identities','auth.mfa_factors','storage.buckets'].includes(name)))
fs.writeFileSync(`${base}/restore-data.json`, JSON.stringify(retained))
const counts = Object.fromEntries(Object.entries(data).map(([name, rows]) => [name, rows.length]))
const hashes = Object.fromEntries(['source-data.json','source-schema.json','restore-schema.sql','restore-data.json'].map(name => [name, crypto.createHash('sha256').update(fs.readFileSync(`${base}/${name}`)).digest('hex')]))
fs.writeFileSync(`${base}/export-manifest.json`, JSON.stringify({source:'mcpjecboqddqelgikvvc',counts,hashes},null,2))
console.log(`Prepared ${tables.length} tables, ${schema.functions.length} functions, ${schema.policies.length} policies; source data and catalog hashed.`)
