#!/usr/bin/env python3
"""Stage, validate and transactionally update the existing Caesar relay; --rollback restores it.

Run as root with the reviewed JSON bundle on stdin. Never reads, copies or changes
cloud-free.env or Supabase secrets. No provider calls or backend/activation restart.
"""
from contextlib import contextmanager
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time

SERVICE = 'caesar-cloud-free.service'
MODEL = 'glm-4-flash-250414'
FILES = {
    'server.mjs': 'opt/campusai/cloud-free/server.mjs',
    'relay.mjs': 'opt/campusai/cloud-free/relay.mjs',
    'tools.mjs': 'opt/campusai/cloud-free/tools.mjs',
    'package.json': 'opt/campusai/cloud-free/package.json',
    SERVICE: 'etc/systemd/system/' + SERVICE,
    'caesar-cloud-free.nginx.conf': 'etc/nginx/snippets/caesar-cloud-free.conf',
    'site.conf': 'etc/nginx/sites-available/campusai',
}
MARKER = '    # Caesar cloud free streaming relay\n    include /etc/nginx/snippets/caesar-cloud-free.conf;\n'
ANCHOR = '    # Caesar verified APK mirror\n'
BACKUP = 'etc/campusai/cloud-free.rollback'


def run(*args, ok=(0,), timeout=30):
    # Explicit environment avoids forwarding caller credentials to preflight subprocesses.
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout,
                            env={'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
                                 'LANG': 'C.UTF-8'})
    if result.returncode not in ok:
        raise RuntimeError('command_failed:' + Path(args[0]).name)
    return result.stdout.strip()


def safe_path(path):
    for part in (path, *path.parents):
        if part.is_symlink():
            raise RuntimeError('symlink_target_refused')
    if path.exists() and not path.is_file():
        raise RuntimeError('non_regular_target_refused')


def require_environment(root):
    env = root / 'etc/campusai/cloud-free.env'
    safe_path(env)
    info = env.stat()  # No read_text/read_bytes, provisioning or permission repair.
    if info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
        raise RuntimeError('existing_environment_must_be_root_0600')


def save(path, data, mode=0o644, uid=None, gid=None):
    path = Path(path)
    safe_path(path)
    fd, temporary = tempfile.mkstemp(prefix='.cloud-free-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as handle:
            handle.write(data.encode('utf-8') if isinstance(data, str) else data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        if uid is not None and os.name == 'posix':
            os.chown(temporary, uid, gid)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def health(expected_model):
    """Bounded loopback configuration readiness; never authenticates or contacts a provider."""
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        connection = http.client.HTTPConnection('127.0.0.1', 8788, timeout=2)
        try:
            connection.request('GET', '/healthz')
            response = connection.getresponse()
            payload = response.read(4097)
            data = json.loads(payload) if len(payload) <= 4096 else {}
            if response.status == 200 and isinstance(data, dict) and data.get('ready') is True and isinstance(data.get('model'), str):
                model = data['model']
                if expected_model is None or model == expected_model:
                    return model
        except (OSError, ValueError, http.client.HTTPException):
            pass
        finally:
            connection.close()
        time.sleep(.25)
    raise RuntimeError('loopback_health_failed')


def candidate_site(original):
    if original.count(MARKER) == 1:
        return original
    if MARKER in original or 'caesar-cloud-free.conf' in original or original.count(ANCHOR) != 1:
        raise RuntimeError('unexpected_nginx_site_layout')
    return original.replace(ANCHOR, MARKER + ANCHOR)


def preflight(bundle, root, stage, command):
    if set(bundle) != set(FILES) - {'site.conf'} or any(not isinstance(v, str) for v in bundle.values()):
        raise ValueError('unexpected_deployment_files')
    package = json.loads(bundle['package.json'])
    if package.get('type') != 'module' or package.get('dependencies') or package.get('devDependencies'):
        raise ValueError('unexpected_package_configuration')
    if shutil.which('node') != '/usr/local/bin/node':
        raise RuntimeError('review_node_path_first')
    for relative in FILES.values():
        safe_path(root / relative)
    if any(not (root / relative).is_file() for name, relative in FILES.items() if name != 'tools.mjs'):
        raise RuntimeError('existing_relay_installation_required')
    site = root / FILES['site.conf']
    original = site.read_text(encoding='utf-8')
    candidate = candidate_site(original)
    for name, text in bundle.items():
        save(stage / name, text)
    for name in ('server.mjs', 'relay.mjs', 'tools.mjs'):
        command('/usr/local/bin/node', '--check', str(stage / name))
    # Link the reviewed modules without running server.mjs or exposing the real env file.
    module_url = (stage / 'relay.mjs').as_uri()
    command('/usr/local/bin/node', '--input-type=module', '-e',
            "const [major, minor] = process.versions.node.split('.').map(Number); "
            "if (major < 22 || (major === 22 && minor < 16)) process.exit(1); "
            f"const m = await import({json.dumps(module_url)}); if (m.MODEL !== '{MODEL}' || m.VISION_MODEL !== 'glm-4v-flash') process.exit(1)")
    command('systemd-analyze', 'verify', str(stage / SERVICE))
    command('nginx', '-t')

    # Validate the full candidate nginx tree without changing the installed site/snippet.
    main = (root / 'etc/nginx/nginx.conf').read_text(encoding='utf-8')
    pattern = r'(?m)^[ \t]*include[ \t]+/etc/nginx/sites-enabled/\*;[ \t]*$'
    if len(re.findall(pattern, main)) != 1:
        raise RuntimeError('unexpected_nginx_main_layout')
    enabled = stage / 'sites-enabled'
    enabled.mkdir()
    matched = 0
    for entry in (root / 'etc/nginx/sites-enabled').iterdir():
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', entry.name) or not entry.is_file():
            raise RuntimeError('unexpected_nginx_enabled_entry')
        if entry.samefile(site):
            matched += 1
            content = candidate.replace('/etc/nginx/snippets/caesar-cloud-free.conf',
                                        (stage / 'caesar-cloud-free.nginx.conf').as_posix())
        else:
            content = f'include "{entry.as_posix()}";\n'
        save(enabled / entry.name, content)
    if matched != 1:
        raise RuntimeError('nginx_site_not_enabled_once')
    staged_main = re.sub(pattern, f'    include "{enabled.as_posix()}/*";', main)
    save(stage / 'nginx.conf', staged_main)
    command('nginx', '-t', '-c', str(stage / 'nginx.conf'))
    return candidate


def snapshot(root, backup, command, health_check):
    active = command('systemctl', 'is-active', SERVICE, ok=(0, 3, 4))
    enabled = command('systemctl', 'is-enabled', SERVICE, ok=(0, 1, 3, 4))
    if active not in ('active', 'inactive', 'unknown') or enabled not in ('enabled', 'disabled', 'not-found'):
        raise RuntimeError('review_existing_service_state')
    model = health_check(None) if active == 'active' else None
    backup.mkdir(mode=0o700)  # One retained snapshot. Never overwrite earlier recovery evidence.
    manifest = {'schema': 1, 'state': 'prepared', 'active': active == 'active',
                'enabled': enabled == 'enabled', 'model': model, 'files': {}}
    for name, relative in FILES.items():
        source = root / relative
        exists = source.exists()
        record = {'exists': exists}
        if exists:
            info = source.stat()
            record.update(mode=stat.S_IMODE(info.st_mode), uid=info.st_uid, gid=info.st_gid)
            save(backup / name, source.read_bytes(), 0o600)
        manifest['files'][name] = record
    save(backup / 'manifest.json', json.dumps(manifest), 0o600)
    return manifest


def restore(root, backup, manifest, command, health_check, reload_nginx=True):
    if manifest.get('schema') != 1 or set(manifest.get('files', {})) != set(FILES) or \
            type(manifest.get('active')) is not bool or type(manifest.get('enabled')) is not bool or \
            manifest.get('state') not in ('prepared', 'applied', 'rolled_back'):
        raise RuntimeError('invalid_recovery_manifest')
    originals = {}
    for name in FILES:
        record = manifest['files'][name]
        if not isinstance(record, dict) or type(record.get('exists')) is not bool:
            raise RuntimeError('invalid_recovery_manifest')
        if record['exists']:
            if set(record) != {'exists', 'mode', 'uid', 'gid'} or \
                    any(type(record[key]) is not int for key in ('mode', 'uid', 'gid')) or \
                    not 0 <= record['mode'] <= 0o777 or record['uid'] < 0 or record['gid'] < 0:
                raise RuntimeError('invalid_recovery_metadata')
            safe_path(backup / name)
            originals[name] = (backup / name).read_bytes()
        safe_path(root / FILES[name])
    # Validate/read the complete recovery snapshot before stopping a running candidate.
    command('systemctl', 'stop', SERVICE, timeout=150)
    for name, relative in FILES.items():
        target = root / relative
        safe_path(target)
        record = manifest['files'][name]
        if record['exists']:
            save(target, originals[name], record['mode'], record['uid'], record['gid'])
        else:
            target.unlink(missing_ok=True)
    command('systemctl', 'daemon-reload')
    command('systemctl', 'enable' if manifest['enabled'] else 'disable', SERVICE)
    if manifest['active']:
        command('systemctl', 'start', SERVICE, timeout=150)
        health_check(manifest['model'])
    command('nginx', '-t')
    if reload_nginx:
        command('systemctl', 'reload', 'nginx')
    manifest['state'] = 'rolled_back'
    save(backup / 'manifest.json', json.dumps(manifest), 0o600)


def install(bundle, root=Path('/'), command=run, health_check=health):
    require_environment(root)
    backup = root / BACKUP
    if backup.exists() or backup.is_symlink():
        raise RuntimeError('review_existing_cloud_free_rollback_before_new_deployment')
    if command('systemctl', 'is-active', 'nginx', ok=(0, 3)) != 'active':
        raise RuntimeError('nginx_must_already_be_active')
    with tempfile.TemporaryDirectory(prefix='.cloud-free-stage-', dir=root / 'etc/campusai') as temporary:
        stage = Path(temporary)
        candidate = preflight(bundle, root, stage, command)
        manifest = snapshot(root, backup, command, health_check)
        reload_attempted = False
        try:
            command('systemctl', 'stop', SERVICE, timeout=150)
            for name, relative in FILES.items():
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                previous = manifest['files'][name]
                save(target, candidate if name == 'site.conf' else bundle[name],
                     previous.get('mode', 0o644), previous.get('uid'), previous.get('gid'))
            command('nginx', '-t')
            command('systemctl', 'daemon-reload')
            command('systemctl', 'enable', SERVICE)
            command('systemctl', 'start', SERVICE, timeout=150)
            health_check(MODEL)
            if command('systemctl', 'is-active', SERVICE, ok=(0, 3)) != 'active':
                raise RuntimeError('relay_not_active_after_start')
            reload_attempted = True
            command('systemctl', 'reload', 'nginx')
            manifest['state'] = 'applied'
            save(backup / 'manifest.json', json.dumps(manifest), 0o600)
        except Exception:
            try:
                restore(root, backup, manifest, command, health_check, reload_attempted)
            except Exception:
                raise RuntimeError('deployment_failed_and_rollback_incomplete_keep_recovery_snapshot') from None
            raise RuntimeError('deployment_failed_original_relay_restored') from None
    return {'installed': True, 'service': 'active', 'model': MODEL, 'rollbackSnapshot': '/' + BACKUP}


def check(bundle, root=Path('/'), command=run):
    require_environment(root)
    with tempfile.TemporaryDirectory(prefix='.cloud-free-stage-', dir=root / 'etc/campusai') as temporary:
        preflight(bundle, root, Path(temporary), command)
    return {'validated': True, 'installed': False, 'model': MODEL}


@contextmanager
def deployment_lock():
    import fcntl  # Linux only; tests import this module on Windows without taking a host lock.
    fd = os.open('/run/lock/caesar-cloud-free-install.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
            raise RuntimeError('unexpected_deployment_lock_owner_or_mode')
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(fd)


def main():
    if os.geteuid() != 0:
        raise RuntimeError('run_as_root')
    if sys.argv[1:] not in ([], ['--check'], ['--rollback']):
        raise ValueError('supported_options_are_check_or_rollback')
    def interrupted(*_):
        raise RuntimeError('deployment_interrupted')
    for name in ('SIGINT', 'SIGTERM', 'SIGHUP'):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), interrupted)
    with deployment_lock():
        if sys.argv[1:] == ['--rollback']:
            root = Path('/')
            require_environment(root)
            backup = root / BACKUP
            safe_path(backup / 'manifest.json')
            manifest = json.loads((backup / 'manifest.json').read_text(encoding='utf-8'))
            restore(root, backup, manifest, run, health)
            result = {'rolledBack': True, 'serviceWasActive': manifest['active']}
        elif sys.argv[1:] == ['--check']:
            result = check(json.load(sys.stdin))
        else:
            result = install(json.load(sys.stdin))
        print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # Do not print command stderr, bundle text, environment values or tracebacks.
        print(json.dumps({'installed': False, 'error': str(failure) if isinstance(failure, RuntimeError)
                          else type(failure).__name__}), file=sys.stderr)
        sys.exit(1)
