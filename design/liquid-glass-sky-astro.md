# Sky / Astro glass and private wishes

2026-09-06 implementation. Sky / Astro apply only to glass material. The original SPECTRA backgrounds, Classic / Fluid appearance, environment presets, navigation and AI provider routing remain in place.

## Product behavior

- Light glass uses Sky-inspired clear convex lenses and restrained rim light, sampling the original selected environment.
- Dark glass uses Astro-inspired clear lenses without RGB separation, sampling the original selected environment. It does not replace the background with a star field.
- The shared Android renderer still owns one scene texture. All glass panels use the same material; at most three visible regions receive live scene refraction. Other regions use the matching Compose material. Native text and controls are drawn above the optical pass.
- Cards, navigation, selectors, actions, icon actions, text fields, AI bubbles, popups and sheets share the revised glass treatment. Separate dialog windows and web controls use the matching translucent material; they do not capture/refraction-sample arbitrary UI text.
- No-photo wishes are text cards with no logo/image placeholder, and show their real title and description. Tree-hole posts already omit their image region when empty.
- The public-sharing switch starts off for each new tree-hole post or wish. Saving does not require switching it on. Anonymous posting remains independent.
- Private cards and details do not display a “仅自己可见” badge. The default action is simply “保存”.
- Blank wish price is SQL NULL, distinct from an explicitly entered zero. Unpriced cards omit the price line. Fractional cents, negative values and integer overflow are rejected. Unpriced wishes support conversation but cannot create a purchase order.

## Database and rollout boundary

`supabase/migrations/20260906053336_community_private_wishes.sql` preserves the visibility of existing records and changes the default only for new records. It adds restrictive parent/child RLS policies and updates the SECURITY DEFINER interaction functions. Private attachments use separate non-public owner-scoped buckets and authenticated signed URLs.

Visibility is selected during creation. This change does not provide a later public/private conversion workflow (which would also need to move attachments). The migration grants insert access to `is_public`, not update access.

After explicit user authorization, the migration was applied to production project `mcpjecboqddqelgikvvc` on 2026-09-06. The remote migration history version is `20260906063445`, name `community_private_wishes` (local source `20260906053336_community_private_wishes.sql`). No existing records were deleted or credentials changed.

Live rollback-only checks passed for private defaults, nullable wish price, explicit public opt-in, owner/stranger visibility, rejection of private-content RPC access and rejection of unpriced purchase orders. The transaction was rolled back; original post/listing counts remain 1/1 with zero test rows left. Both private media buckets and all ten new policies were verified. Evidence is in `artifacts/community-migration-deployment.json`; pre-migration function/policy definitions are saved in `artifacts/community-migration-schema-before.json`.

The security advisor reports intentionally callable SECURITY DEFINER RPCs and the existing disabled leaked-password check; no ERROR-level findings were returned. RPC authorization was verified for this change. Unrelated Auth settings were preserved. See [Supabase RPC advisor guidance](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable) and [password protection guidance](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection). Actual phone-to-server media upload remains outside the rollback SQL checks.

## Verification

- `node scripts/verify-community-privacy.mjs`: 38 checks using the actual schema, RLS and RPC migrations. Covers owner/stranger/staff, private media, approved-private records, legacy visibility, NULL price and free orders. Fixtures omit only unrelated extension/search-index setup.
- Android full local test suite: 371 tests passed. Android compile, debug APK and Lint checks are recorded in `artifacts/glass-final-validation.log`; the final icon-action refinement is checked separately in `artifacts/glass-final-component-validation.log`.
- `CommunityPrivacyUiTest`: opt-in behavior, price-free saving, absence of private badges/image placeholders, light/dark screenshots and 200% text. Screenshots are static Compose rendering, not proof of live Android GPU motion.
- Admin production build and ten Playwright checks pass using an isolated fixture build, local Microsoft Edge and no backend credentials. Tests cover six widths, navigation, form behavior and compilation/refraction of the exact Android GLSL on WebGL.
- The corrected APK was installed on phone `3cc5349b` with `adb install -r` and launched successfully. The installed APK hash matches the local artifact; app data directory inodes and first-install time are unchanged. Real-device frame pacing under load, context recovery and actual server media delivery remain unverified.

Design relationships adapted from the design-craft AppLlama reference; no upstream source code or artwork was copied. Supabase access rules were checked against the [RLS documentation](https://supabase.com/docs/guides/database/postgres/row-level-security) and [Storage access documentation](https://supabase.com/docs/guides/storage/security/access-control).

## Scope correction after phone review

The first candidate incorrectly applied Sky / Astro clouds and stars to the global environment. The correction restores the original Android scene shader, motion-off background/rail, full-screen dialog backdrop and web environment renderer exactly from the pre-change checkout. Glass-only optics and the confirmed community behavior remain. No saved appearance or environment preferences are reset.

## Touch material and dark-only stars

- `GlassPanel` now keeps content and hit targets outside the deforming material layer. Press compresses only glass by 1.8% horizontally / 3.2% vertically, with an underdamped spring on release.
- Passive pointer observation sends local touch position into the optical shader, moves the refractive focus and rim highlight, and prioritizes touched glass within the existing three-region budget. It does not consume events.
- Only dark glass has sparse four-point stars near its edges, brightening and growing near touch. Light glass has no stars. Motion-off preserves static material and disables touch deformation.
- The new Compose interaction test verifies touch tracking, release overshoot, stable label/control bounds and click delivery. `artifacts/glass-touch-validation.log` records the relevant Android tests, Lint and APK build. `artifacts/glass-touch-shader.log` verifies exact native shader compilation, differing left/right touch pixels and restoration after release.
- `artifacts/glass-environment-restoration-check.json` confirms all four original background/scene sections remain identical to the pre-change checkout.
- Installed artifact: `artifacts/campusai-glass-touch-debug.apk`, SHA-256 `28a80ab9a1f240cc14c17285388364fd7a6b9e6173299e5438fe46aeec557194`. Install/hash evidence: `artifacts/glass-touch-phone-install.json`.
