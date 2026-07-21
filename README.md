# Context Bubble

Context Bubble is a privacy-first Android assistant that stays available through a lightweight edge-docked overlay. It supports user-invoked screen understanding, streaming answers, Realtime voice, dictation insertion, approved cloud memory, local Quick Fill, reminders, image generation, Google Calendar, and a read/write ChatGPT MCP connection.

The idle path owns one classic Android overlay `View` and an event-driven Accessibility listener. It has no idle microphone, WebRTC/Supabase socket, polling loop, screenshot, AI request, animation loop, sensor subscription, or wake lock.

## Architecture

- Native Kotlin, Android 13+ (`minSdk 33`, `targetSdk 36`).
- Compose for the app shell; classic `View` overlays for the persistent bubble and response surfaces.
- Separate Play and Lab application IDs. Lab-only Accessibility actuators are compile-time excluded from Play.
- Room v2 with a non-destructive v1→v2 migration, encrypted local files, Keystore-protected keys, DataStore, and constrained WorkManager sync.
- Shared Hono TypeScript API used by the local Node adapter and Supabase Edge Functions.
- Supabase Auth, Postgres/pgvector, private Storage, OAuth 2.1, RLS, and built-in `gte-small` embeddings.
- OpenAI Responses SSE, transcription, Realtime WebRTC, and image APIs; all permanent credentials remain server-side.
- Context Bubble MCP tools for approved shared memories. This is app-owned memory, not native ChatGPT Memory.

## Implemented product paths

- Persistent edge bubble with safe status-bar, cutout, gesture, navigation, orientation, and IME bounds.
- Tap actions stay in the current app: Ask about screen, Remember, Generate image, Quick Fill, Reminder, and Pause.
- Streaming answer overlay with Copy, Save, Retry, and Close.
- Long-press Realtime WebRTC conversation with barge-in, 20-second warm continuation, two-minute ceiling, device-side screen/memory/reminder tools, and encrypted buffered fallback.
- Optional transcription-only long press with wrong-target protection and clipboard fallback.
- Three memory lanes: ephemeral, local-only, and user-approved Shared AI.
- Optimistic cloud sync, cursors, conflicts, 30-day tombstones, idempotency, semantic retrieval, and no idle Realtime subscription.
- Google sign-in through Credential Manager and Supabase native ID-token exchange.
- MCP read tools plus two-stage, ten-minute, one-use write/delete confirmations.
- OAuth-client RLS isolation: OAuth tokens cannot bypass MCP by calling PostgREST or Storage directly.
- Google Calendar `calendar.events` authorization, encrypted refresh tokens, exact signed previews, one-use confirmations, and deterministic event idempotency.
- Signed package policy with Ed25519 verification, downgrade/expiry rejection, last-known-good fallback, and optional signing-certificate fingerprints.
- Play Integrity Standard verification for production Play registration; private invite registration for Lab.
- Optional AutofillService limited to name, email, phone, and address. Passwords, cards, PINs, and OTPs are prohibited.
- Cloud quota dashboard, export, destructive two-stage cloud deletion, integration/MCP revocation, daily usage limits, cleanup, and encrypted weekly backup tooling.

## Build and test

Requirements: Android SDK 36, JDK 17+, and Node.js 22+.

```powershell
.\gradlew.bat testPlayDebugUnitTest testLabDebugUnitTest lintPlayDebug assemblePlayDebug assembleLabDebug

cd backend
npm install
npm run typecheck
npm test
deno task check:edge

cd ..\oauth-consent
npm install
npm run build
```

APKs are written to:

- `app/build/outputs/apk/play/debug/app-play-debug.apk`
- `app/build/outputs/apk/lab/debug/app-lab-debug.apk`

Use `adb install -r` so the current Room database, permissions, and app data survive upgrades. Never uninstall or clear data during the normal fix/test loop.

## Local phone testing

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb reverse tcp:8787 tcp:8787

cd backend
Copy-Item .env.example .env   # once; then fill private values
npm run dev
```

The current local `.env` needs only `OPENAI_API_KEY`, `OPENAI_MODEL_PROFILE=budget`, and a strong `INSTALLATION_TOKEN_SECRET` for AI-only testing. Cloud, MCP, Calendar, signed policy, and production Play Integrity activate only when their complete server configuration is present. See [backend/.env.example](backend/.env.example) for every variable.

Production rollout switches default to off. Adding secrets alone does not expose cloud memory, semantic search, MCP, Realtime, Calendar, or signed-policy endpoints. Enable them one at a time after the dev gate.

Android build-only values are `POLICY_SIGNING_PUBLIC_KEY_DER_BASE64`, `PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER`, and Lab-only `LAB_INVITE_CREDENTIAL`.

## Cloud deployment

See [Cloud deployment](docs/CLOUD_DEPLOYMENT.md). Apply all migrations in order, deploy `api` and `mcp-server`, configure Supabase OAuth with asymmetric JWT signing, deploy `oauth-consent` to Cloudflare Pages, and keep production feature flags closed until the dev gates pass.

The cloud retrieval gate is `npm run eval:retrieval -- --cleanup`. It requires an explicitly confirmed disposable dev user, runs 50 English and 50 Hinglish queries, and fails if either Recall@5 is below 85%.

The Supabase CLI is intentionally not installed into the application dependency tree. `backend/scripts/deploy-supabase.ps1` checks for an authenticated CLI before changing a remote project.

## Privacy and security boundaries

- Local-only records never enter cloud requests unless selected for a single explicit action.
- Raw screenshots and recordings never enter Supabase DB or Storage.
- Generated images are cloud-saved only after Save and are encrypted before upload.
- Screen content is untrusted data and cannot directly authorize actions.
- Financial, credential, OTP, biometric, permission, package-install, protected, and configured sensitive screens remain hard-blocked.
- MCP cannot read local-only data. MCP writes/deletes need an exact preview, explicit user confirmation, matching OAuth client grant, and a one-use token.
- The app cannot write native ChatGPT Memory. ChatGPT can retrieve Context Bubble memory only after the user connects this MCP server.

See [Threat model](docs/THREAT_MODEL.md), [Performance contract](docs/PERFORMANCE.md), [Validation record](docs/VALIDATION.md), and [Privacy draft](docs/PRIVACY.md).

## Honest remaining external gates

Source implementation does not create or own Manjunath’s third-party accounts. Final live cloud validation still requires the two Supabase project references/credentials, Supabase OAuth enablement, Google OAuth/Calendar credentials, a Play Console cloud project/service account, Ed25519 production keys, and a Cloudflare deployment. Long-duration 8-hour battery, 24-hour soak, seven-day dogfood, multi-OEM, and live 120 Hz gates require connected hardware and elapsed real time; these results must be measured, not inferred.
