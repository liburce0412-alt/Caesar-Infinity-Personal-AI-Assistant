# CampusAI Alibaba deployment

Active production: **Hangzhou lightweight 120.55.14.0**, Ubuntu 24.04, 2 vCPU / nominal 2 GiB (1,613 MiB usable). Admin/API: **https://campusai.campus3ai.xyz**, Cloudflare DNS-only A record. Cutover completed September 26, 2026. The independent activation site `activate-test.campus3ai.xyz` shares the host and must remain available.

Hong Kong **47.243.49.228** is retired from production; the user authorized cleanup before releasing it. Do not restore stale database data as a live writer. See [the migration record](../../docs/caesar-hangzhou-migration-20260926.md) for backup and retirement evidence. September 8 deployment notes are historical.

## Runtime and limits

Supabase lives at `/opt/campusai/supabase`, retaining the source image digests. The gateway binds only to `127.0.0.1:8000`; nginx exposes HTTPS Auth, REST, Storage and Realtime. PostgreSQL and Studio remain private. Admin assets: `/var/www/campusai-admin`.

Seven core services run: db, rest, auth, imgproxy, api-gw, storage and realtime. Studio/Meta remain installed under the `maintenance` profile and stopped; the public CampusAI admin application is independent. Do not enable maintenance services without checking memory headroom.

`campusai.slice` bounds core containers to 900 MiB memory (800 MiB high), no swap, one CPU core and limited disk bandwidth/IOPS. These bounds protect activation. They are merged into the active main compose file because `.env` sets `COMPOSE_FILE=docker-compose.yml`; an implicit override is ineffective.

The enabled `campusai-backend.service` requires Docker and the slice, and calls `/opt/campusai/deploy/start-bounded.sh`. The script verifies limits/service membership, starts sequentially and stops the stack on startup failure. Manage the complete stack through this unit:

```sh
systemctl status campusai-backend.service
systemctl restart campusai-backend.service
cat /sys/fs/cgroup/campusai.slice/memory.events
cd /opt/campusai/supabase && docker compose ps
```

A full service restart and functional checks passed under these limits. This is not a high-load benchmark or full-machine reboot acceptance test. Investigate activation latency and pressure before increasing resource limits.

After the October 14 host OOM recovery, core containers use `restart: on-failure:5`. Systemd owns cold startup order; Docker retries failed running containers up to five times without launching the whole stack ahead of the database after a daemon restart. The startup script verifies this policy. Healthchecks retain their original commands, timeout and retries, with a 30-second steady interval and a 5-second startup interval during a 90-second grace period. `configure-bounded-runtime.py` applies only these fields to the private main Compose file, verifies the resolved configuration has no other changes, and retains one root-only recovery copy. It does not restart services; apply the result through the backend unit above. Docker restart-policy semantics are documented [here](https://docs.docker.com/engine/containers/start-containers-automatically/).

The incident's kernel OOM snapshot contained 105 `runc` and 32 `runc:[2:INIT]` processes, with about 354 MiB summed RSS, outside the application processes themselves. This establishes runtime-process accumulation as a material source of host pressure, but does not prove the precise trigger. Reduced probe frequency mitigates repeated exec overhead; no permanent leak fix or unlimited capacity is claimed. The 900 MiB core hard limit and independent activation service remain unchanged.

## Clients and verification

The server move preserved hostname, signing secrets, accounts and sessions. Clients already using `campusai.campus3ai.xyz` need no endpoint/APK change. Clients must contain only the anon key, never service-role keys or server secrets.

Final restore matched 58 table inventories and all six media file/xattr records. Post-cutover validation passed 27 API/invitation/admin checks and 65 HTTPS/API/private media checks. Four original objects passed download/hash checks. Chrome rendered the login page. These checks do not substitute for real-user browser login or Android-device acceptance.

`smoke-test.py` creates disposable synthetic accounts/invitations and removes them afterward; audit events remain. Run only against the authorized self-hosted deployment. Set `CAMPUSAI_TEST_BASE_URL=https://campusai.campus3ai.xyz` for public-hostname checks.

TLS renewal uses `/var/www/campusai-acme`, the existing certbot timer and nginx reload hook. The post-cutover renewal dry-run succeeded.

## Backup and restore

Run `/opt/campusai/deploy/backup.sh` for a protected timestamped logical database/configuration/media backup. It includes roles, DB configuration volume, admin assets, nginx/TLS material, runtime slice/unit/start script, the installed cloud-free relay and its protected environment file, and a SHA-256 manifest. No automatic database-backup schedule was added. A live dump and media archive are not a single atomic snapshot; freeze writes for a final migration copy.

Verified final cold backup: `hangzhou-final-20260926T042600Z`. Verified post-cutover backup: `/opt/campusai/backups/20260926T043238Z`. Independent hash-verified copies are under ignored `artifacts/hangzhou-migration-20260926/private/`. Archives contain secrets and must not be committed or shared.

For physical migration, stop writes and cleanly stop PostgreSQL; use the same compatible image/architecture. Restore only into an empty destination, preserving numeric ownership and named `campusai_db-config` volume. Never overlay an active database. For logical restore, deliberately restore roles, grants, ownership and the custom dump into a prepared compatible destination.

**Storage xattrs are required.** Archive and extract with `tar --xattrs --xattrs-include='*' --acls` and appropriate create/extract flags and numeric ownership. Verify actual Storage API downloads afterward; file hashes alone do not establish metadata preservation. Restore bounded compose configuration and runtime units before starting. Preserve the activation virtual host, certificates and service.

New writes belong to Hangzhou. A rollback requires a write freeze and reverse synchronization of current database/media; stale Hong Kong archives alone omit new data. No VM was released by the agent. Unrelated Cloudflare applications and hosted Korea Supabase remain outside this migration. See [the historical work log](../../docs/alicloud-migration-work.md) for earlier work.

## Caesar cloud free

Installed October 13, 2026: `caesar-cloud-free.service` runs `/usr/local/bin/node` against `/opt/campusai/cloud-free/server.mjs`, listening only on `127.0.0.1:8788`. The exact public endpoint is `POST /caesar-cloud/v1/chat`; nginx includes `/etc/nginx/snippets/caesar-cloud-free.conf` with streaming buffering disabled. The root-only environment file is `/etc/campusai/cloud-free.env` (0600). Never print or commit it.

The service uses a 96 MiB JS heap, 128 MiB memory high / 192 MiB hard limit, no swap and one CPU core maximum. It does not modify the core container slice or activation service. `GET /healthz` only checks configuration presence. Use `journalctl -u caesar-cloud-free.service` for content-free diagnostic codes; upstream 1305 means model overload, not a local daily quota. Fast text with the new client capability falls back once to free `glm-4-flash-250414` on model overload, upstream 5xx or no first token within eight seconds. Other requests retain one same-model congestion retry. Deep, vision and legacy clients keep their fixed routing, with no paid fallback. See [routing and live verification](../../docs/caesar-cloud-free.md).
