# CampusAI Alibaba deployment

Active server: **Hong Kong ECS 47.243.49.228**, Ubuntu 22.04, 2 vCPU / 4 GiB. Public admin/API hostname: `https://campusai.campus3ai.xyz`, using a DNS-only Cloudflare A record. Hangzhou ECS `121.41.52.217` is a stopped-stack rollback copy; its VM has not been released. The separate 2 GiB Hangzhou light server is not deployed and shares the mainland filing restriction that blocked the earlier destination.

Supabase is pinned to `self-hosted/v0.8.0`, with all nine image versions locked to source image digests. It lives at `/opt/campusai/supabase`. The gateway binds to `127.0.0.1:8000`; nginx exposes HTTPS Auth, REST, Storage and Realtime. Database and Studio remain private. Certbot renewal uses webroot `/var/www/campusai-acme` and a deploy hook to reload nginx.

## Client configuration and verification

Root `.env`, admin `.env.local`/`.env.production.local`, and the two existing GitHub client secrets now point to Hong Kong. Clients receive only the anon key. Never include service-role keys, signing secrets, SSH keys or database passwords. Existing passwords/account IDs are preserved; users must sign in again because hosted-platform sessions were not transferred. Already-installed APKs need an update to change their compiled endpoint.

The admin bundle is at `/var/www/campusai-admin`. A candidate can use the alternate ignored environment file:

```powershell
.\gradlew.bat :apps:android:app:assembleDebug '-PcampusBackendConfig=.env.alicloud.local' --console=plain
```

The APK at `artifacts/alicloud-migration-20260908/CampusAI-alicloud-candidate.apk` passed Android AuthFlowTest checks. Admin unit tests/build passed. Production verification passed 28 source-table comparisons, 27 API/invitation checks and 11 external HTTPS/asset/authorization/object checks. A fresh source comparison matched all 23 non-audit business tables. Four stored objects (two images and two placeholders) retain their SHA-256 digests and ownership.

Actual browser interaction remains unverified because browser control timed out. No Android-device installation/acceptance has been performed. Prefer a user login through the admin site/new APK before releasing old Hangzhou ECS.

During cutover, Windows briefly reused the old Hangzhou DNS answer. Both Cloudflare authoritative nameservers and three public resolvers returned Hong Kong; clearing the local DNS cache restored all external checks. Allow stale client/resolver caches to expire when diagnosing a connection to the old IP.

`smoke-test.py` must run only on the self-hosted destination. It creates uniquely named synthetic accounts/invitations and removes them afterward; audit events remain. Set `CAMPUSAI_TEST_BASE_URL=https://campusai.campus3ai.xyz` for the public hostname. `verify-restore.py --allow-new-audit-events` compares original typed rows while allowing added audit records.

## Backup and restore

`backup.sh` creates a protected timestamped directory containing a PostgreSQL custom dump, roles, environment/configuration and Storage files, DB configuration volume, admin assets, nginx configuration, TLS/account keys and a SHA-256 manifest. The verified cutover backup is `/opt/campusai/backups/20260908T110339Z`, also downloaded to `artifacts/alicloud-migration-20260908/private/20260908T110339Z`. All seven file digests matched. These archives contain secrets: do not commit or share them.

For a future migration, inspect the destination and use the same pinned image digests. Stop writes before the final copy. A physical PostgreSQL copy requires a clean database shutdown and the same compatible database image/architecture. Restore only into an empty destination, preserving numeric ownership and named `campusai_db-config` volume. Never overlay an active database. Retain the old deployment and independent archives until verification passes.

**Storage extended attributes are required.** For archiving and extraction use `tar --xattrs --xattrs-include='*' --acls` with the normal create/extract flags. A plain tar can retain file bytes while losing metadata, causing Storage GET ENODATA/500. Verify through Storage API downloads and compare hashes afterward.

The protected migration directory holds `hongkong-stack.tar.gz`, `hongkong-db-config.tar.gz`, `hongkong-image-lock.json`, and corrective `hongkong-storage-xattrs.tar.gz`. Together these are the cleanly stopped, successfully restored source copy. The last archive supplies attributes missing from the initial stack tar. Original platform exports/media remain separately available. For a logical restore, restore roles/grants and the custom database dump into a prepared, empty compatible destination; handle built-in roles deliberately and validate ownership, RLS, triggers, RPCs and auth hashes.

Restore configuration and certificates with restricted permissions. Verify through SSH, then external HTTPS against the target IP with the valid hostname, then normal DNS. Check login/refresh, invite concurrency, authorization and file downloads. Reusing hostname/server secrets avoids another client change. Any future 2 GiB deployment requires separate capacity verification; current checks cover the 4 GiB server only.

## Rollback and cleanup

Hosted Korea Supabase remains unchanged. Pre-cutover client configuration is archived privately in `client-before-hongkong.env`. Back up and reconcile new Hong Kong writes before rollback; do not discard post-cutover data. Do not use the old hosted-deployment workflow to apply self-hosted migrations.

The old CampusAI CF worker/settings were archived and the worker deleted. Other Workers, D1 databases, DNS and tunnels are outside cleanup. Local CI changes have not been pushed. No Alibaba instance was released. After interactive acceptance, release only old Hangzhou ECS, never active Hong Kong ECS.
