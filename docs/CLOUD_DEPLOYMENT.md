# Cloud deployment

Context Bubble uses the same Hono handlers through the local Node adapter and the production Supabase Edge adapter.

## Projects

Create `context-bubble-dev` and `context-bubble-prod` in Mumbai (`ap-south-1`). Keep production feature flags disabled until the corresponding dev gates pass.

For each project:

1. Configure Google in Supabase Auth and enable Supabase OAuth Server beta for the MCP client flow.
2. Copy `backend/.env.example` to a private secrets worksheet and populate every production value.
3. Store server values with `supabase secrets set --env-file <private-file>`; never commit the populated file.
4. Run `backend/scripts/deploy-supabase.ps1 -ProjectRef <ref>`.
5. Enable `pg_cron` and schedule `public.cleanup_context_bubble_data()` once daily as documented in migration 004.
6. Build and deploy `oauth-consent` to Cloudflare Pages, then use that HTTPS URL as the OAuth authorization/consent page.
7. Set `MCP_RESOURCE_URL` to the deployed MCP function base and configure ChatGPT with `<MCP_RESOURCE_URL>/mcp`.

Production optional features default off even when their secrets exist. Open them in this order only after the dev project gate passes:

1. `ENABLE_CLOUD_MEMORY=true`
2. `ENABLE_SEMANTIC_SEARCH=true`
3. `ENABLE_MCP=true`
4. `ENABLE_REALTIME_VOICE=true`
5. `ENABLE_CALENDAR=true`
6. `ENABLE_SIGNED_POLICIES=true`

Redeploy the functions after each secret change and verify `/v1/config` before enabling the Android rollout. Turning a switch off must leave local Vault, Quick Fill, reminders, bubble controls, and queued shared changes operational.

The public Android build receives only the managed API URL, Supabase public URL, publishable key, Google public client ID, policy public key, and API compatibility version. It never receives the Supabase secret key, OpenAI key, Calendar client secret, cloud encryption master key, policy private key, or Play Integrity service-account JSON.

## Android build variables

Set these only in the build environment:

- `POLICY_SIGNING_PUBLIC_KEY_DER_BASE64`
- `PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER`
- `LAB_INVITE_CREDENTIAL` for Lab builds only

## Backup

Run `node backend/scripts/backup-cloud.mjs` weekly from a protected machine with `pg_dump` and `tar` available. The script creates a Postgres custom-format dump plus private Storage metadata inventory, wraps both in AES-256-GCM, and removes the plaintext temporary bundle. Store `BACKUP_ENCRYPTION_KEY` separately from the backup files and perform quarterly restoration drills into a throwaway project.

Raw screenshots and audio are never uploaded. Generated images are encrypted before private Storage upload and are included in the metadata inventory; Storage objects remain ciphertext.

## Retrieval release gate

Use a new disposable user in `context-bubble-dev`, obtain that user's Supabase access token, and run:

```powershell
$env:RETRIEVAL_EVAL_BACKEND_URL='https://YOUR_PROJECT.supabase.co/functions/v1/api'
$env:RETRIEVAL_EVAL_ACCESS_TOKEN='DISPOSABLE_USER_ACCESS_TOKEN'
$env:RETRIEVAL_EVAL_CONFIRM='I_AM_USING_A_DISPOSABLE_DEV_USER'
cd backend
npm run eval:retrieval -- --cleanup
```

The runner seeds 50 expiring evaluation memories and executes 100 searches: 50 English and 50 Hinglish. Both Recall@5 values must be at least 85%. `--cleanup` tombstones the fixtures; delete the disposable Auth user after the run. If `gte-small-v1.0` misses the gate, close semantic search, set `EMBEDDING_PROVIDER=openai` and `OPENAI_EMBEDDING_MODEL=text-embedding-3-small`, run `npm run reembed:cloud -- --apply`, verify every live row uses the same embedding model, redeploy both functions, rerun the gate, and only then reopen semantic search.

## Live validation that cannot be done from source alone

- Apply all four migrations to the dev project, then run Supabase database lint and two-user/multiple-OAuth-client RLS tests.
- Verify Google OAuth, refresh, logout, Calendar revoke, MCP consent, token refresh rotation, and MCP revoke with real provider accounts.
- Test Play Integrity with the production Play package and cloud project.
- Run the retrieval gate, live SSE, WebRTC media, Storage encryption/inventory, weekly backup, and restoration drill.
- Do not copy production secrets into Android resources, Gradle properties committed to source control, screenshots, logs, or support bundles.
