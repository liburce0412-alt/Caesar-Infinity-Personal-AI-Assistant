"""Compare restored rows using PostgreSQL's native types without printing their contents."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

root=Path('/opt/campusai/migration')
source=json.loads((root/'restore-data.json').read_text())
def sql(query):
    p=subprocess.run(['docker','exec','-i','supabase-db','psql','-U','postgres','-d','postgres','-X','-A','-t','-v','ON_ERROR_STOP=1'],input=query,text=True,capture_output=True)
    if p.returncode:
        raise RuntimeError('Verification SQL failed; no source records printed.')
    return p.stdout.strip()
def ident(s): return '"'+s.replace('"','""')+'"'
def literal(s): return "'"+s.replace("'","''")+"'"
columns=json.loads(sql("select json_agg(row_to_json(c)) from information_schema.columns c where table_schema in ('public','auth','storage');"))
results={}
for table,rows in source.items():
    namespace,name=table.split('.')
    qualified=ident(namespace)+'.'+ident(name)
    if not rows:
        passed=sql('select count(*)=0 from '+qualified)=='t'
    else:
        names=[c['column_name'] for c in columns if c['table_schema']==namespace and c['table_name']==name and all(c['column_name'] in r for r in rows)]
        selected=','.join(map(ident,names))
        payload=literal(json.dumps(rows,ensure_ascii=False))
        differences='(select * from actual except all select * from expected) union all (select * from expected except all select * from actual)'
        if table=='public.audit_logs' and '--allow-new-audit-events' in sys.argv:
            differences='select * from expected except all select * from actual'
        passed=sql(f'with expected as (select {selected} from jsonb_populate_recordset(null::{qualified},{payload}::jsonb)), actual as (select {selected} from {qualified}), differences as ({differences}) select count(*)=0 from differences;')=='t'
    mode='original_rows_preserved' if table=='public.audit_logs' and '--allow-new-audit-events' in sys.argv else 'identical'
    results[table]={'count':len(rows),mode:passed}
    if not passed:
        raise RuntimeError('Restored rows differ: '+table)
(root/'row-verification.json').write_text(json.dumps(results,indent=2))
print('Typed source-row verification passed for '+str(len(results))+' tables, including account IDs and password hashes; new audit events allowed only when explicitly requested.')
