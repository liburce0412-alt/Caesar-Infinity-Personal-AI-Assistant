"""Keep only services used by CampusAI, with a 1408 MiB container memory budget."""
from pathlib import Path
import yaml

root=Path('/opt/campusai/supabase')
file=root/'docker-compose.yml'
if not (root/'docker-compose.full.yml').exists():
    (root/'docker-compose.full.yml').write_text(file.read_text())
compose=yaml.safe_load(file.read_text())
limits={'db':'512m','auth':'128m','rest':'128m','storage':'512m','api-gw':'128m'}
compose['services']={name:service for name,service in compose['services'].items() if name in limits}
for name,service in compose['services'].items():
    service['mem_limit']=limits[name]
    service['memswap_limit']=limits[name]
    service['depends_on']={key:value for key,value in service.get('depends_on',{}).items() if key in limits}
compose['services']['storage']['environment']['ENABLE_IMAGE_TRANSFORMATION']='false'
compose['services']['storage']['environment'].pop('IMGPROXY_URL',None)
compose['services']['auth']['environment']['GOTRUE_PASSWORD_MIN_LENGTH']='8'
compose['services']['rest']['environment']['PGRST_DB_POOL']='10'
file.write_text(yaml.safe_dump(compose,sort_keys=False))
print('Configured 5 core services with 1408 MiB total hard memory limits; extra tools removed.')
