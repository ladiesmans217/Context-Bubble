# Validation record

Validated through 2026-07-22 after the cloud, MCP, synchronization, Realtime, and
production-completion implementation.

## Current source and artifact gates

Android verification:

- Clean Play and Lab debug unit suites: 59/59 tests passed, with no failures,
  errors, or skipped tests.
- Play lint: no fatal or error findings. The report contains 97 warnings and
  five informational findings; the remaining warnings are reviewed build/API
  advisories rather than release-blocking lint errors.
- Play and Lab debug and minified release APKs: built successfully.
- Play debug instrumentation source, including the Room v1 to v2 migration
  test: compiled successfully. It was not executed because no device or
  emulator was connected.
- Benchmark APK and updated cloud/sign-in/SSE/Realtime journeys: compiled.
- Static Play/Lab isolation gate: passed after scanning every decompressed APK
  entry for the Lab actuator marker and `QUERY_ALL_PACKAGES`.
- Compiled manifest verification: Play application ID is
  `com.contextbubble.app.play` and does not request `QUERY_ALL_PACKAGES`; Lab
  application ID is `com.contextbubble.app.lab` and does request it.

Release artifact sizes:

- Play unsigned release APK: 31.75 MiB.
- Lab unsigned release APK: 31.75 MiB.
- Benchmark APK: 41.06 MiB; this is a test-only artifact and is not shipped to
  users.
- Debug APKs are approximately 93.4 MiB because they retain development and
  native debug material. Both release APKs pass the 40 MiB product gate.

Backend and web verification:

- Backend TypeScript checks, including the deployment and retrieval scripts:
  passed.
- Backend tests: 21/21 passed.
- Supabase Edge entrypoints: Deno type-check passed for `api` and `mcp-server`.
- Backend dependency audit: zero known vulnerabilities at `high` or above.
- OAuth consent page production build: passed.
- OAuth consent dependency audit: zero known vulnerabilities at `high` or
  above.
- The 100-query retrieval evaluation runner validates 50 English and 50
  Hinglish queries and fails deployment when either Recall@5 is below 85%.
  The runner itself is checked, but a live score has not been claimed without
  a disposable Supabase development user and deployed dev project.

## Live development deployment

- Supabase project `oxrytpcmkqkprqefzkrf` is linked and migrations 001 through
  004 were applied successfully. The project was created in `ap-northeast-1`
  rather than the planned Mumbai region, so it is treated as development only.
- The `api` and `mcp-server` Edge Functions are deployed using the checked-in
  npm import map and Supabase's hosted publishable/secret key maps.
- Live health reports OpenAI and cloud memory configured. Public configuration
  enables cloud memory, semantic retrieval, MCP, Realtime voice, and signed
  policies for development; Calendar remains disabled.
- A disposable-user cloud journey passed encrypted write, server-side
  ciphertext inspection, `gte-small-v1.0` semantic retrieval, versioned
  tombstone deletion, and user cleanup.
- A two-user live security journey passed API and direct-PostgREST RLS
  isolation, exact idempotent replay, stale-version conflict generation, and
  cleanup. Altered payload reuse of an idempotency key returns the stable
  `409 idempotency_mismatch` response after a live-discovered status fix.
- The live package policy has a valid non-empty Ed25519 signature and expiry.
- MCP protected-resource metadata is live. An unauthenticated memory tool call
  returns an error result containing `mcp/www_authenticate` as required.
- Supabase's OAuth authorization-server discovery endpoint still returns 404;
  the OAuth server must be enabled and configured before ChatGPT can complete
  the connection.
- The Motorola was upgraded from 0.1.0 to 0.2.0 using `adb install -r`. Its
  original first-install timestamp remained unchanged, the bubble/menu rendered,
  ADB reverse reached the local backend, and no focused crash/ANR appeared.
- The device correctly blocked Ask about screen while Accessibility screen
  access was disabled. Android also confirmed that battery optimization is
  currently active for the package.

## Security and privacy gates checked locally

- No `.env`, `local.properties`, private key, keystore, or certificate file is
  tracked by Git.
- Permanent OpenAI, Supabase, Google, cloud-memory, and signing secrets remain
  backend-only.
- Android configuration contains only the managed URL and public identifiers.
- `LOCAL_ONLY` records are omitted from synchronization and assistant-memory
  requests by repository and request-construction tests.
- Cloud-memory AES-256-GCM encryption, tamper failure, HKDF key separation, and
  versioned key metadata are covered by backend tests.
- MCP prepare/commit tokens are bound to user, OAuth client, operation, payload
  hash, version, expiry, and one-use nonce; expiry, replay, alteration,
  revocation, and client mismatch are covered by tests.
- Realtime does not attach the current screen automatically. Device code only
  captures screen context for an explicit current-screen request and still
  applies protected-package and sensitivity policy.
- The public production configuration defaults cloud, semantic search, MCP,
  Realtime, Calendar, and signed-policy rollout flags to off until their live
  gates pass.

## Historical pre-cloud device baseline

This evidence belongs to the immutable `pre-cloud-baseline` build and must not
be read as a performance result for the new cloud build.

Device: Motorola moto g54 5G (`ZD222FP4FC`), Android 15/API 35,
1080 x 2400, 400 dpi. The measured rendering run used 60 Hz.

- Five cold starts: 307 to 432 ms.
- 1,593 rendered frames; 19 janky frames (1.19%).
- Frame time: p50 8 ms, p90 10 ms, p95 13 ms, p99 40 ms.
- Six missed vsync events and no frozen frame over 700 ms.
- Visible full-Activity PSS after stabilization: approximately 89 MiB. This was
  not an idle bubble-service PSS measurement.

The previous APK was installed with `adb install -r`; data was preserved and
no unrelated setting or permission was changed.

## Gates intentionally still open

These require external credentials, a deployed development project, a
connected device, user consent, or extended observation. They have not been
fabricated from compilation results:

- Run multi-OAuth-client grant isolation and migration rollback tests against
  the live database.
- Deploy the consent page, enable/configure Supabase OAuth and
  Google providers, and run full OAuth refresh/logout/revocation tests.
- Run the 100-query English/Hinglish retrieval evaluation against the deployed
  development project. Keep `gte-small` only if both groups reach 85% Recall@5;
  otherwise switch the whole deployment to the documented OpenAI embedding
  fallback and re-embed before reopening semantic search.
- Exercise live OpenAI SSE, image, buffered transcription, and Realtime WebRTC
  media—including interruption, route change, connection loss, and fallback.
- Connect ChatGPT to the deployed Context Bubble MCP and validate discovery,
  read tools, confirmed writes/deletes, immediate revocation, and audit history.
- Validate Google Calendar authorization and live create/update/delete
  idempotency using a test calendar.
- Configure production Play Integrity and verify genuine, replayed, and altered
  request hashes against the Play project.
- Execute the migration instrumentation test and the complete permission, overlay,
  Accessibility, keyboard, cutout, Bluetooth, app-compatibility, and protected-
  screen matrices.
- Re-measure the cloud build at 60 and 120 Hz, idle CPU/PSS, first SSE chunk,
  first Realtime audio, search/sync latency, eight-hour battery A/B, 24-hour
  service soak, 1,000 bubble cycles, 200 dictations, and seven-day dogfood run.
- Repeat representative reliability testing on API 33 to 36 and the listed
  Samsung, Xiaomi/Redmi, OnePlus, and Oppo/Realme environments.

The current Supabase project has development flags enabled. Final production
flags remain disabled until the corresponding gate is recorded here with raw
evidence.
