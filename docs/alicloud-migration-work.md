# Alibaba Cloud Supabase migration

## Current status — Hong Kong cutover, 2026-09-08

The active deployment is **47.243.49.228**, Hong Kong, Ubuntu 22.04, 2 vCPU / 4 GiB, at `/opt/campusai/supabase`. DNS-only `campusai.campus3ai.xyz` now resolves to this host. Earlier notes below are chronological evidence, not current instructions.

**2026-09-08 evening follow-up:** Explicit user authorization deployed community visibility/media migration `20260908123909` and restored omitted client column ACLs with `20260908125410` to this Hong Kong host only. Actual new-post API testing exposed the catalog restore omission (HTTP 403); profile/post/wish writes and protected-field denial now pass. All 65 HTTPS/API/Storage checks passed and temporary fixtures were removed. Backup: `/opt/campusai/backups/20260908T124954Z`. See `outputs/candidates/20260908-experience/deploy-hongkong/DEPLOYMENT.md`. Community buckets are now private: previous permanent public media checks are historical and must not be used as current acceptance criteria. The component folding, peace animation and onboarding are implemented in the new local Android candidate, with emulator verification; this candidate has not been installed on a physical phone.

- After the user bound `codex (2).pem` and rebooted, SSH succeeded. The clean destination was inspected before installation. All nine images were locked to exact source repository digests.
- Hangzhou's containers were stopped cleanly; the stack and database configuration volume were archived, downloaded, hash-verified and restored. Hangzhou ECS itself remains allocated at `121.41.52.217`; no instance was released. The separate 2 GiB light server remains untouched.
- A plain tar copy lost Storage extended attributes and caused ENODATA. Re-export/restoration with `--xattrs --xattrs-include='*' --acls` fixed it. All four objects (two images and two empty placeholders) now pass external download/hash checks. The updated backup script preserves these attributes.
- Typed source comparison passed for 28 tables, including the four original account IDs/password hashes. A fresh hosted-source comparison matched all 23 non-audit business tables. Original audit records are preserved; test audit events were added. All synthetic users/invitations were removed.
- All nine services are healthy; nginx and certificate renewal timer are active. Normal public DNS passed 11 HTTPS/asset/authorization/object checks. The public hostname passed 27 API/invitation/admin checks. Studio and PostgreSQL remain private.
- Actual browser click-through remains unverified: navigation timed out despite direct external HTTPS succeeding. No real browser or Android-device acceptance is claimed.
- One final normal-DNS request returned the old Hangzhou address and failed TLS. Both Cloudflare authoritative nameservers plus 1.1.1.1, 8.8.8.8 and 223.5.5.5 returned Hong Kong. Clearing the Windows DNS cache restored all 11 normal-DNS checks. Do not confuse a cached old address with a failure of the verified Hong Kong origin. Browser AX control still timed out afterward, so interactive acceptance remains pending.
- Root `.env` and existing GitHub `SUPABASE_URL` / `SUPABASE_ANON_KEY` secrets now use the new deployment. Other fields and signing secrets were preserved. Admin production configuration/bundle use the new deployment. The candidate APK below already contains this hostname and its digest was rechecked; it has not been installed or published. Already-installed APKs retain their old compiled endpoint until updated.
- Final backup `/opt/campusai/backups/20260908T110339Z` was downloaded to protected local `private/20260908T110339Z`. All seven manifest files matched SHA-256: database, roles, configuration/media, DB configuration volume, admin assets, TLS material and nginx configuration. Earlier cold-copy archives and original hosted-source exports remain available independently.
- Backend cutover and backup checks are complete. Prefer a successful user login in the admin site/new APK before releasing Hangzhou because interactive acceptance remains pending. Release must target only the old Hangzhou ECS; never release the active Hong Kong ECS.

The hosted Korea project remains unchanged as a rollback source. CampusAI CF worker cleanup is complete; unrelated Cloudflare resources remain intact. Local workflow changes have not been pushed. See the evening follow-up above for the completed experience candidate and subsequent Hong Kong migrations.

## Earlier staging history

Authorized 2026-09-08: migrate the existing Supabase project to the user's Hangzhou ECS; retire the unfinished Workers/D1/R2 replacement. Keep existing account IDs, passwords, business data and media. Retain the old hosted database as a rollback source.

Host: `121.41.52.217`, Ubuntu 22.04, 2 vCPU / 4 GiB. Deployment: `/opt/campusai/supabase`. Intended hostname: `campusai.campus3ai.xyz`, using the existing Cloudflare zone. DNS authorization remains to be resolved; no public cutover yet.

Current work is preserved in `artifacts/alicloud-migration-20260908/baseline`. Withdrawn Cloudflare candidate files are archived under `withdrawn-cloudflare`; they are no longer active source code. Private source exports are under `private`, restricted to the current Windows account and excluded from Git.

Acceptance:
- Auth identities and password hashes, all 24 business tables, RLS, RPCs, triggers and files are preserved and reconciled.
- No database or Studio port is exposed publicly. HTTPS API and the admin application are verified before switching clients.
- Invite-only registration and existing admin changes work against PostgreSQL/Supabase.
- Android and admin builds/tests pass; client credentials contain only the publishable/anon key.
- Restore and rollback instructions plus independent backups are provided.

Current target remains the **4 GiB ECS until the trial ends**. User corrected the temporary plan to slim immediately. All nine services are restored and healthy. The 2 GiB light server (`120.55.14.0`, Ubuntu 24.04) is a future target only; assess it before the old trial expires and cut over before expiry. Its key was supplied at `C:/Users/28219/Downloads/codex (1).pem`; do not copy either private key into the repository. No deployment was made to that host. `slim.py` is an optional future experiment, not the active configuration.

Verified on the new ECS:
- Exact typed row comparison across 28 restored tables, including 24 business tables and original account IDs/password hashes. Source exports and schema remain archived privately.
- Four objects imported through Storage, downloaded again and SHA-256 compared; original ownership retained. Business rows contain relative media paths, with no old Storage URL prefix references; no database URL rewrite is required for this snapshot.
- Invitation/admin migration applied only to the new database. 27 API checks passed, including all list pages, overview, invalid/expired/revoked invitations, concurrent single-use registration, password login, refresh, and student admin denials. Synthetic accounts/invitations were removed afterward; audit events remain.
- Admin unit tests and build pass; Android AuthFlowTest passes, including a candidate built with `.env.alicloud.local`. APK: `artifacts/alicloud-migration-20260908/CampusAI-alicloud-candidate.apk`, SHA-256 `3A42D0130AEDA6BA56ED49564255C9E1170DA4A45B83BA0362AB61E4576BFADA`. Generated BuildConfig and admin bundle were checked for the new URL and absence of the old project URL. No public app/device acceptance yet.
- Database/config/media backup created at `/opt/campusai/backups/20260908T100537Z`, copied to local `private/20260908T100537Z`.

The user added DNS-only A `campusai` to `121.41.52.217` and allowed TCP 80/443 in the 4 GiB ECS security group. Both screenshots and direct network checks confirmed the changes. Let's Encrypt issued a certificate expiring 2026-12-07; nginx serves HTTPS and Certbot renewal timer plus nginx reload hook are active. All 27 API checks passed again from the ECS using its public HTTPS hostname.

**Confirmed public access blocker:** external HTTPS initially returned 200, then started resetting during TLS. HTTP returns `403`, `Server: Beaver`, and an HTML title of `Non-compliance ICP Filing` with an Alibaba备案拦截 URL. The ECS itself still serves HTTPS normally. This is Alibaba's domain filing/access-registration block, not a remaining port or certificate error. User was informed and asked whether to keep the Hangzhou deployment pending filing or choose a non-mainland server. Both purchased instances are in Hangzhou; moving to the 2 GiB instance does not resolve this block.

The new admin files are deployed at `/var/www/campusai-admin`. API routes are proxied over HTTPS; Studio and Postgres remain private. Browser UI verification did not complete because browser control timed out. Android's active `.env` and GitHub client secrets still point to the original source, preventing a broken public cutover. Only the migration candidate uses the new host.

CF cleanup: worker `campusai-admin` and settings were archived privately and the worker was deleted through the API; the follow-up list confirmed only the two unrelated workers remain. No CampusAI Pages or D1 resources existed. Other workers, D1 databases and DNS entries were preserved. The local candidate for the old CF auto-deploy workflow now performs checks only; it has not been pushed to GitHub. Wrangler deployment scripts/config/dependency have been removed from active local admin sources, with the config archived.

Latest decision: user chose **no filing for now; migrate to Hong Kong or another non-mainland instance**. The user supplied a screenshot of Hong Kong ECS `47.243.49.228`, showing inbound 22/80/443 allowed. SSH reaches this host, but root authentication with both supplied keys (`codex.pem` and `codex (1).pem`) returned `Permission denied (publickey)`. The second key's Windows ACL was narrowed to match the already protected first key before the retry. No commands ran on the Hong Kong host and no service/data changes were made there. Its existing services and hardware remain unverified; the screenshot shows older security-group rules, so inspect before installing or replacing anything.

The 4 GiB Hangzhou instance remains the staging source; do not release it merely because the user asked whether release/recreation is possible. Both Supabase data/config/media and TLS keys were backed up at `/opt/campusai/backups/20260908T102723Z`, downloaded to local `private/20260908T102723Z`, and all five manifest digests verified.

Alibaba's current ECS trial guide lists China Hong Kong and says an instance can be released then recreated from the trial entry, but the user's earlier region dropdown did not show Hong Kong. Confirm the actual account's available trial regions/quota before recommending release or purchase. Prefer creating/verifying the replacement before releasing the staging source when the account permits it.

Pending: new non-mainland instance connection, restore and external/public acceptance, DNS and production client endpoint cutover. UI folding, animation and onboarding remain part of the earlier feature request and must not be mistaken for completed migration work.
