"""Restore reviewed exported records transactionally, keeping existing auth hashes."""
import json
import subprocess
from pathlib import Path

root = Path('/opt/campusai/migration')
def sql(query):
    result = subprocess.run(['docker','exec','-i','supabase-db','psql','-U','postgres','-d','postgres','-X','-A','-t','-v','ON_ERROR_STOP=1'], input=query, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()
def identifier(value):
    return '"'+value.replace('"','""')+'"'
def literal(value):
    return "'"+value.replace("'","''")+"'"
source = json.loads((root/'restore-data.json').read_text())
source_columns = json.loads((root/'source-schema.json').read_text())['columns']
destination = json.loads(sql("select json_agg(row_to_json(c)) from information_schema.columns c where table_schema in ('public','auth','storage');"))
commands = ['BEGIN;', 'SET session_replication_role = replica;']
for table, rows in source.items():
    if not rows:
        continue
    namespace, name = table.split('.')
    dst = [c for c in destination if c['table_schema']==namespace and c['table_name']==name and c['is_generated']=='NEVER']
    names = [c['column_name'] for c in dst if any(c['column_name'] in row for row in rows)]
    if not names:
        raise RuntimeError('Missing destination table: '+table)
    extras = set().union(*(row.keys() for row in rows)) - set(c['column_name'] for c in destination if c['table_schema']==namespace and c['table_name']==name)
    for extra in extras:
        # Hosted Storage exposes versioning metadata which this pinned file
        # backend does not implement. All source buckets explicitly disable it.
        if table=='storage.buckets' and extra=='versioning_status' and all(row.get(extra)=='DISABLED' for row in rows):
            continue
        if any(row.get(extra) not in (None, '', False, {}, []) for row in rows):
            raise RuntimeError('Nonempty unsupported source field: '+table+'.'+extra)
    qualified = identifier(namespace)+'.'+identifier(name)
    columns = ','.join(map(identifier,names))
    payload = literal(json.dumps(rows,ensure_ascii=False))
    commands.append(f'INSERT INTO {qualified} ({columns}) OVERRIDING SYSTEM VALUE SELECT {columns} FROM jsonb_populate_recordset(NULL::{qualified},{payload}::jsonb);')
    for column in dst:
        if column['is_identity']=='YES':
            name=column['column_name']
            commands.append(f"SELECT setval(pg_get_serial_sequence({literal(table)},{literal(name)}),coalesce((SELECT max({identifier(name)}) FROM {qualified}),1),(SELECT count(*)>0 FROM {qualified}));")
commands.append('COMMIT;')
sql('\n'.join(commands))
checks={}
for table, rows in source.items():
    qualified='.'.join(map(identifier,table.split('.')))
    actual=int(sql(f'SELECT count(*) FROM {qualified};'))
    if actual != len(rows):
        raise RuntimeError('Count mismatch: '+table)
    checks[table]=actual
(root/'restored-counts.json').write_text(json.dumps(checks,indent=2))
print('Restored and reconciled '+str(len(checks))+' tables; auth sessions intentionally require re-login.')
