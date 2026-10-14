# Caesar Hangzhou migration — 2026-09-26

## Production result

`https://campusai.campus3ai.xyz` now serves Caesar/CampusAI from Hangzhou **120.55.14.0**, Ubuntu 24.04, 2 vCPU, 1,613 MiB usable RAM. Cloudflare's A record is DNS-only. The existing activation application remains at `https://activate-test.campus3ai.xyz` on its separate systemd service, SQLite database and nginx virtual host.

The hostname, signing keys, account IDs/password hashes, sessions, business data and media were preserved. Clients already using this hostname require no APK endpoint change. No application upgrade or database schema migration was included.

## Capacity incident and recovery

The initial unbounded nine-container startup caused memory/IO pressure and an activation-site outage. After the user completed Alibaba verification, the instance was rebooted, migration containers were stopped and activation recovered. The cloud-assistant recovery command `t-hz06y71o2i1zf29` was confirmed terminal (failed); no delayed stop command remained pending before production startup.

Seven core services now run: db, rest, auth, imgproxy, api-gw, storage and realtime. Studio and Postgres Meta remain installed under the `maintenance` profile, stopped with restart disabled. The separate public CampusAI administration UI remains functional. All source image digests were retained.

All core containers belong to `campusai.slice`: memory high 800 MiB, maximum 900 MiB, no cgroup swap, CPU quota one core, reads/writes 20/10 MiB/s and 500/200 IOPS. These bounds protect the independent activation service. The host has a 2 GiB swap file, but Caesar cannot use it. The enabled `campusai-backend.service` starts services sequentially through `/opt/campusai/deploy/start-bounded.sh`, checking limits and service membership before startup and stopping the stack on startup failure.

Limits are merged into the active main compose file because `.env` sets `COMPOSE_FILE=docker-compose.yml`; an implicit override would not apply. The backend unit starts Docker through its dependency. A complete service stop/start passed. No further full-machine reboot was performed after the final configuration.

At final functional verification, 556 MiB host memory was available, swap use was zero, and the cgroup had zero hard-limit hits, OOMs and OOM kills. This verifies the checked small deployment, not high-concurrency capacity. Check activation latency and cgroup pressure as load increases. Do not start Studio/Meta without checking headroom.

## Data and acceptance evidence

- Final cold restore matched **58 tables** by count/content digest and **six Storage file/xattr inventory records**. Four original accounts and four original objects were retained.
- After public DNS cutover, **27 API/invitation/login/refresh/admin checks** and **65 HTTPS/API/private Storage authorization checks** passed. Synthetic fixtures were removed; test audit events remain.
- All **four original Storage objects** were downloaded through the API and matched SHA-256.
- Normal public HTTPS connected to **120.55.14.0**, returned login HTTP 200 and validated TLS. Chrome rendered the login form. No fresh real-user browser login or Android-device acceptance is claimed by these checks.
- Activation `/api/status` returned HTTP 200, `ok: true`, and activation enabled. The recovered SQLite integrity check was `ok`; its recovery inventory contained 221 entitlements and 416 activation attempts. No paid entitlement was consumed for testing.
- Certificate renewal dry-run succeeded for `campusai.campus3ai.xyz`; certbot.timer and the existing nginx reload hook remain in use. Activation service configuration and certificates were preserved.

## Backups and evidence

The final source backup `hangzhou-final-20260926T042600Z` is retained on Hangzhou at `/opt/campusai/migration/hangzhou-final-20260926T042600Z` and locally under `artifacts/hangzhou-migration-20260926/private/`. All eight SHA-256 entries matched independently. Rehearsal `hangzhou-stage-20260926T033819Z` is retained too.

The post-cutover logical/configuration backup is `/opt/campusai/backups/20260926T043238Z` on Hangzhou and `artifacts/hangzhou-migration-20260926/private/20260926T043238Z` locally. All eight manifest entries matched. It includes runtime units/start script, database, roles, media/configuration, DB configuration volume, admin, nginx and certificates. No automatic database-backup schedule was added.

Physical PostgreSQL data was copied after clean shutdown with the same database image. Storage tar archives preserve extended attributes, ACLs and numeric ownership. These protected archives contain credentials and account data and must not be shared or committed.

Local evidence: `final-data-verification.json`, `smoke-public-verification.json` and `verification.json` in the protected migration directory. Activation recovery backup on Hangzhou: `/opt/campusai/migration/recovery-evidence/activation-recovered-20260926.sqlite`.

## Hong Kong retirement

After successful cutover the Hong Kong nginx API temporarily forwarded over verified HTTPS to Hangzhou while all old database containers stayed stopped. The user then explicitly requested cleanup before releasing Hong Kong **47.243.49.228**. Its complete `/opt/campusai` tree, admin, ACME files, nginx configuration and TLS material were archived as `campusai-hongkong-retirement-20260926.tar.gz` under the protected local migration directory before deletion. The 118,040,022-byte archive matched SHA-256 `e80fefa0588ea63d4ff76730fb7f348c4c66f61d8e7df8eecd6cfa5a4a20f79a`.

Cleanup completed: all nine old containers and their images, `campusai_db-config`, `campusai_default`, `/opt/campusai`, `/var/www/campusai-admin`, `/var/www/campusai-acme`, the nginx site/forwarding bridge and the old domain certificate were removed. The temporary remote retirement archive was deleted only after independent local verification. Source Docker containers/images/volumes are empty; only the generic nginx default site remains. Generic OS software was not uninstalled. The user will release the VM separately; the agent did not release it.

After source cleanup, public Caesar and activation HTTPS still connected directly to **120.55.14.0** and returned HTTP 200 with valid TLS. All seven target containers remained healthy, activation was enabled, and nginx/certbot/backend/activation units were active. Target available memory was 579 MiB, swap use zero, and OOM/hard-limit counters stayed zero. The migration no longer relies on the Hong Kong instance.

Rollback after new Hangzhou writes requires a write freeze and reverse synchronization into a restored compatible deployment. A retained old database archive alone cannot restore post-cutover changes. Unrelated Cloudflare applications and the old hosted Korea project remain outside this work.
