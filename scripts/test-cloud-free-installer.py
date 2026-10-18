"""Offline rollback tests. No SSH, service manager, nginx, provider, or real credentials."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('installer', REPO / 'deploy/alicloud/install-cloud-free.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class Host:
    def __init__(self, root, failure=None, active=True, enabled=True):
        self.root, self.failure = root, failure
        self.active, self.enabled = active, enabled
        self.calls, self.health_calls = [], []
        self.failed = False
        self.bundle = {}
        self.original = {}
        for name, relative in installer.FILES.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if name == 'site.conf':
                content = 'server {\n' + installer.ANCHOR + '}\n'
            else:
                source = REPO / ('apps/cloud-free/' if name in ('server.mjs', 'relay.mjs', 'tools.mjs', 'package.json') else 'deploy/alicloud/') / name
                self.bundle[name] = source.read_text(encoding='utf-8')
                content = 'old-' + name
            if name != 'tools.mjs':  # Upgrade from production which did not include the new module.
                path.write_text(content, encoding='utf-8')
            self.original[name] = path.read_bytes() if path.exists() else None
        nginx = root / 'etc/nginx'
        (nginx / 'nginx.conf').write_text('events {}\nhttp {\n include /etc/nginx/sites-enabled/*;\n}\n')
        (nginx / 'sites-enabled').mkdir()
        # Hard links work in Windows tests without symlink privileges; production uses the existing symlink.
        os.link(root / installer.FILES['site.conf'], nginx / 'sites-enabled/campusai')
        self.activation = nginx / 'sites-enabled/activation'
        self.activation.write_text('server { listen 8443; }\n')
        self.env = root / 'etc/campusai/cloud-free.env'
        self.env.parent.mkdir(parents=True, exist_ok=True)
        self.env.write_bytes(b'SYNTHETIC_DO_NOT_READ_OR_COPY=example\n')
        self.env_bytes = self.env.read_bytes()

    def fail_once(self, stage):
        if self.failure == stage and not self.failed:
            self.failed = True
            raise RuntimeError('injected_' + stage)

    def command(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == '/usr/local/bin/node':
            self.assert_original()
            self.fail_once('node_preflight')
        if args[0] == 'systemd-analyze':
            self.assert_original()
            self.fail_once('unit_preflight')
        if args[:2] == ('nginx', '-t'):
            if '-c' in args:
                self.assert_original()
                candidate = Path(args[-1])
                if 'sites-enabled/*' not in candidate.read_text():
                    raise AssertionError('candidate nginx include missing')
                staged_site = candidate.parent / 'sites-enabled/campusai'
                if (candidate.parent / 'caesar-cloud-free.nginx.conf').as_posix() not in staged_site.read_text():
                    raise AssertionError('candidate points to live snippet')
                self.fail_once('nginx_preflight')
            elif (self.root / installer.FILES['relay.mjs']).read_text() == self.bundle['relay.mjs']:
                self.fail_once('nginx_after_replace')
        if args[:2] == ('systemctl', 'is-active'):
            return 'active' if args[2] == 'nginx' or self.active else 'inactive'
        if args[:2] == ('systemctl', 'is-enabled'):
            return 'enabled' if self.enabled else 'disabled'
        if args[:2] == ('systemctl', 'stop'):
            self.active = False
        if args[:2] == ('systemctl', 'start'):
            self.fail_once('start')
            self.active = True
        if args[:2] == ('systemctl', 'enable'):
            self.enabled = True
        if args[:2] == ('systemctl', 'disable'):
            self.enabled = False
        if args == ('systemctl', 'reload', 'nginx'):
            self.fail_once('reload')
        return ''

    def health(self, model):
        self.health_calls.append(model)
        if model == installer.MODEL:
            self.fail_once('health')
        return model or 'glm-4.7-flash'

    def assert_original(self):
        for name, relative in installer.FILES.items():
            target = self.root / relative
            actual = target.read_bytes() if target.exists() else None
            if actual != self.original[name]:
                raise AssertionError('changed original: ' + name)

    def install(self):
        with patch.object(installer, 'require_environment'), patch.object(installer.shutil, 'which', return_value='/usr/local/bin/node'):
            return installer.install(self.bundle, self.root, self.command, self.health)


class CloudFreeInstallerTest(unittest.TestCase):
    def test_command_path_includes_ubuntu_sbin_without_inheriting_credentials(self):
        with patch.dict(os.environ, {'ZHIPU_API_KEY': 'synthetic-not-forwarded', 'NODE_OPTIONS': 'synthetic-not-forwarded'}), \
                patch.object(installer.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='ok\n')) as command:
            self.assertEqual('ok', installer.run('nginx', '-t'))
        args, options = command.call_args
        self.assertEqual((('nginx', '-t'),), args)
        self.assertEqual({'PATH', 'LANG'}, set(options['env']))
        self.assertEqual(['/usr/local/sbin', '/usr/local/bin', '/usr/sbin', '/usr/bin', '/sbin', '/bin'],
                         options['env']['PATH'].split(':'))
        self.assertEqual('C.UTF-8', options['env']['LANG'])

    def setUp(self):
        if os.name == 'nt':
            # Windows Python's 0700 DACL excludes the restricted test token. Keep inherited
            # ACLs for synthetic fixture dirs only; POSIX modes are checked separately below.
            original_mkdir = os.mkdir
            def fixture_mkdir(path, mode=0o777, *, dir_fd=None):
                return original_mkdir(path, 0o755 if mode == 0o700 else mode, dir_fd=dir_fd)
            inherited_acl = patch.object(os, 'mkdir', side_effect=fixture_mkdir)
            inherited_acl.start()
            self.addCleanup(inherited_acl.stop)

    def test_preflight_failures_leave_live_files_services_and_backup_untouched(self):
        for failure in ('node_preflight', 'unit_preflight', 'nginx_preflight'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                host = Host(Path(temporary), failure)
                with self.assertRaises(RuntimeError):
                    host.install()
                host.assert_original()
                self.assertTrue(host.active)
                self.assertTrue(host.enabled)
                self.assertFalse((host.root / installer.BACKUP).exists())
                self.assertFalse(any(args[:2] == ('systemctl', 'stop') for args in host.calls))
                self.assertEqual([], list((host.root / 'etc/campusai').glob('.cloud-free-stage-*')))

    def test_check_only_validates_candidate_without_backup_or_service_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(Path(temporary))
            with patch.object(installer, 'require_environment'), patch.object(installer.shutil, 'which', return_value='/usr/local/bin/node'):
                result = installer.check(host.bundle, host.root, host.command)
            self.assertEqual({'validated': True, 'installed': False, 'model': installer.MODEL}, result)
            host.assert_original()
            self.assertFalse((host.root / installer.BACKUP).exists())
            self.assertFalse(any(args[0] == 'systemctl' for args in host.calls))

    def test_commit_failures_restore_all_files_and_original_service_state(self):
        for failure in ('nginx_after_replace', 'start', 'health', 'reload'):
            for active, enabled in ((True, True), (False, False)):
                with self.subTest(failure=failure, active=active), tempfile.TemporaryDirectory() as temporary:
                    host = Host(Path(temporary), failure, active, enabled)
                    with self.assertRaisesRegex(RuntimeError, 'original_relay_restored'):
                        host.install()
                    host.assert_original()
                    self.assertEqual(active, host.active)
                    self.assertEqual(enabled, host.enabled)
                    self.assertEqual(host.env_bytes, host.env.read_bytes())
                    self.assertEqual('server { listen 8443; }\n', host.activation.read_text())
                    manifest = json.loads((host.root / installer.BACKUP / 'manifest.json').read_text())
                    self.assertEqual('rolled_back', manifest['state'])
                    self.assertFalse(manifest['files']['tools.mjs']['exists'])
                    self.assertFalse(any('supabase' in ' '.join(args) or 'activation' in ' '.join(args) for args in host.calls))

    def test_partial_file_replacement_failure_rolls_back_the_whole_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(Path(temporary))
            original_save = installer.save
            failed = False
            def interrupted(path, *args, **kwargs):
                nonlocal failed
                if Path(path) == host.root / installer.FILES['tools.mjs'] and not failed:
                    failed = True
                    raise OSError('synthetic disk failure')
                return original_save(path, *args, **kwargs)
            with patch.object(installer, 'save', side_effect=interrupted):
                with self.assertRaisesRegex(RuntimeError, 'original_relay_restored'):
                    host.install()
            host.assert_original()
            self.assertTrue(host.active)

    def test_success_keeps_one_snapshot_and_manual_rollback_removes_new_tools_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(Path(temporary))
            result = host.install()
            self.assertTrue(result['installed'])
            self.assertTrue(host.active)
            self.assertEqual([None, installer.MODEL], host.health_calls)
            backup = host.root / installer.BACKUP
            self.assertEqual(set(installer.FILES) - {'tools.mjs'} | {'manifest.json'}, {p.name for p in backup.iterdir()})
            manifest = json.loads((backup / 'manifest.json').read_text())
            self.assertEqual('applied', manifest['state'])
            with self.assertRaisesRegex(RuntimeError, 'review_existing_cloud_free_rollback'):
                host.install()
            installer.restore(host.root, backup, manifest, host.command, host.health)
            host.assert_original()
            self.assertEqual('rolled_back', json.loads((backup / 'manifest.json').read_text())['state'])
            self.assertEqual([], list((host.root / 'etc/campusai').glob('.cloud-free-stage-*')))

    def test_environment_is_only_statted_and_root_0600_is_required(self):
        for uid, mode, allowed in ((0, 0o600, True), (1, 0o600, False), (0, 0o640, False), (0, 0o644, False)):
            with self.subTest(uid=uid, mode=mode), patch.object(installer, 'safe_path'), \
                    patch.object(Path, 'stat', return_value=SimpleNamespace(st_uid=uid, st_mode=stat.S_IFREG | mode)), \
                    patch.object(Path, 'read_text', side_effect=AssertionError('environment content read')), \
                    patch.object(Path, 'read_bytes', side_effect=AssertionError('environment content copied')):
                if allowed:
                    installer.require_environment(Path('/synthetic'))
                else:
                    with self.assertRaisesRegex(RuntimeError, 'root_0600'):
                        installer.require_environment(Path('/synthetic'))

    def test_invalid_bundle_and_symlink_targets_are_rejected_before_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(Path(temporary))
            host.bundle['unexpected.env'] = 'not a real credential'
            with self.assertRaises(ValueError):
                host.install()
            host.assert_original()
            self.assertFalse(any(args[:2] == ('systemctl', 'stop') for args in host.calls))
        with patch.object(Path, 'is_symlink', return_value=True):
            with self.assertRaisesRegex(RuntimeError, 'symlink_target_refused'):
                installer.safe_path(Path('/synthetic/file'))

    def test_incomplete_rollback_is_reported_and_snapshot_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(Path(temporary), 'health')
            with patch.object(installer, 'restore', side_effect=OSError('synthetic recovery failure')):
                with self.assertRaisesRegex(RuntimeError, 'rollback_incomplete_keep_recovery_snapshot'):
                    host.install()
            self.assertTrue((host.root / installer.BACKUP / 'manifest.json').exists())

    def test_missing_recovery_file_is_rejected_before_stopping_current_relay(self):
        with tempfile.TemporaryDirectory() as temporary:
            host = Host(Path(temporary))
            host.install()
            backup = host.root / installer.BACKUP
            manifest = json.loads((backup / 'manifest.json').read_text())
            (backup / 'relay.mjs').unlink()
            host.calls.clear()
            with self.assertRaises(FileNotFoundError):
                installer.restore(host.root, backup, manifest, host.command, host.health)
            self.assertTrue(host.active)
            self.assertEqual([], host.calls)


if __name__ == '__main__':
    unittest.main()
