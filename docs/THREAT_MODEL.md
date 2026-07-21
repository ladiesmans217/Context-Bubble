# Threat model

## Protected assets

- Screen text, screenshots, audio, transcripts, Quick Fill values, reminders, and approved memories.
- Android Keystore keys, Supabase sessions, OAuth grants, Google refresh tokens, installation tokens, cloud master keys, and provider secrets.
- Consequential operations such as memory changes, calendar writes, messages, shares, and Lab automation.

## Trust boundaries

1. Other Android applications and Accessibility nodes to the overlay service.
2. Android app to Context Bubble API and Supabase Auth.
3. API/MCP Edge Functions to Postgres, Storage, OpenAI, Google, and OAuth clients.
4. Untrusted screen or memory content to model context.
5. Model-proposed tools to deterministic phone/backend policy.
6. Play and Lab source sets to final distribution artifacts.

## Principal controls

- Package policy is evaluated before hierarchy access. Hard blocks combine built-in packages, signed remote package/certificate policy, sensitive-field detection, protected-window behavior, and system-surface blocks.
- Long-press voice does not attach screen context automatically. The current screen is captured only for the explicit screen tool, and the device verifies that the current transcribed turn requested screen context.
- Screen and memory content is labeled untrusted. Model output is typed and cannot authorize an actuator directly.
- External writes use `Understand → Plan → Policy → Preview → Confirm → Execute → Verify → Receipt`. Financial, credential, OTP, biometric, permission, installation, and purchase-like operations remain blocked.
- Local content and sessions use Keystore-backed AES-GCM encryption. Vault and Quick Fill use `FLAG_SECURE`; Android backup is disabled.
- Cloud payload/blob encryption uses per-user HKDF-derived AES-GCM keys with versioned key rotation. Separate HMAC keys support deduplication.
- Every user table has RLS. Phone JWTs are user-scoped. OAuth JWTs carrying `client_id` are denied direct PostgREST and Storage access; the MCP function uses the server secret only after issuer, audience, expiry, user, client, grant, and explicit user-ID checks.
- MCP changes use signed tokens bound to user, OAuth client, operation, payload hash, version, expiry, and nonce. Consumed nonces prevent replay.
- Calendar confirmations use the same exact-preview, expiry, and replay protections. Idempotent identifiers prevent duplicate creates.
- Permanent OpenAI, Supabase, Calendar, policy-signing, and Play Integrity credentials never enter the APK. Production registration verifies Play Integrity request binding; Lab uses a separate signed/private credential.
- Ed25519 policy updates reject invalid signatures, expiry, implausible issue times, and version downgrade, then retain the last known good policy.
- Logs store request IDs and error categories, never authorization headers, prompts, screen content, audio, transcripts, memory payloads, or calendar descriptions.
- Play artifact verification rejects Lab actuator markers and `QUERY_ALL_PACKAGES`.

## Abuse and failure cases

- **Prompt injection:** visible text and stored memories cannot override system policy or confirmation requirements.
- **Tapjacking/overlay abuse:** the app follows Android overlay ordering, hides on protected surfaces, and never overlays permission or credential surfaces.
- **Wrong-target dictation:** package/window/node/selection fingerprints are revalidated before insertion; otherwise the transcript is offered for Copy or Insert here.
- **Replay/duplicate writes:** mutation idempotency, confirmation nonces, optimistic versions, and receipts prevent silent duplication.
- **URI leakage:** generated image sharing uses expiring FileProvider grants and revocation.
- **Cross-user access:** all cloud access applies authenticated user filters; service-secret paths never accept a caller-supplied owner without verified identity.
- **Process/network failure:** drafts and encrypted audio survive; safe work retries with backoff; consequential operations do not retry without a receipt check.

## Residual risks

- No package list identifies every sensitive application worldwide. Field/system protections reduce but do not eliminate classification risk.
- A compromised, unlocked Android user profile can access data through the running app and accessibility privileges.
- Server-decryptable Shared Cloud memory and semantic embeddings are not zero-knowledge.
- Clipboard receivers and external target apps can retain data after a deliberate Copy or Share.
- Supabase OAuth is beta and lacks custom application scopes; Context Bubble therefore enforces grants in its own database and MCP layer.
- OEM process managers, force-stop, revoked permissions, or project pause can stop availability until the user repairs it.
- Conservative Realtime quota reservation protects cost but may make the daily limit appear reached before the exact number of spoken seconds is consumed.
