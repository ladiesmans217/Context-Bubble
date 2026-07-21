# Privacy policy draft

Context Bubble is an Android assistant controlled by an edge-docked bubble. It does not continuously record the screen, microphone, or application activity. Screen capture and screen understanding occur only after an explicit bubble action or an explicit spoken request for current-screen context.

## Data lanes

- **Ephemeral context** exists only for the active request and is not converted into memory automatically.
- **Local only** records remain on the phone. Memory values, Quick Fill values, captures, recordings, transcripts, OAuth tokens, and saved generated images are encrypted with Android Keystore-protected AES-GCM keys. Private app data is excluded from Android backup.
- **Shared Cloud** memories synchronize only after sign-in and explicit approval. Signed-in saves preselect Shared Cloud but always show that choice. Signed-out saves default to Local only.

Quick Fill is Local only unless the user marks a specific entry Allow AI and selects it for an action. Payment cards, passwords, PINs, OTPs, and authentication secrets are prohibited.

## Cloud and AI processing

Approved Shared Cloud memory payloads are encrypted with per-user AES-256-GCM keys derived server-side. They are encrypted at rest, but they are not zero-knowledge: the Context Bubble backend can decrypt them to answer authorized requests. Embeddings and limited metadata are stored separately and can reveal semantic similarity even without the plaintext payload.

OpenAI API requests are sent only through the backend. Responses requests use `store:false`. Screen images and buffered audio are transmitted over TLS only for the invoked operation; Context Bubble does not persist them in Supabase Database or Storage. Realtime voice media travels directly between the phone and OpenAI after the backend establishes the authenticated session. Failed recordings remain encrypted on the phone for retry.

Generated images are uploaded to private Supabase Storage only after Save. The backend encrypts each saved image before upload. Google Calendar is optional; refresh tokens are encrypted server-side, the app requests only `calendar.events`, and every event write requires an exact preview and confirmation.

## ChatGPT MCP

Connecting ChatGPT authorizes Context Bubble's own MCP server. MCP exposes only approved Shared Cloud memories. It never exposes Local only data and does not modify native ChatGPT Memory. ChatGPT calls the MCP when useful in a conversation; it does not continuously poll the phone.

MCP writes and deletions require a prepare preview followed by a separate ten-minute, one-use confirmation. Access can be changed to read-only or revoked from the Android app. OAuth-client tokens are blocked from direct Database and Storage access.

## Protection and retention

The overlay is removed and active capture is discarded on known financial, UPI, password-manager, authenticator, permission, installer, biometric, lock-screen, credential, and protected surfaces. Password, PIN, OTP, and card fields are blocked independently of package classification. These hard blocks cannot be weakened by user exclusions.

Raw-capture retention choices are 1, 7, 30, 60, or 90 days, or Until deleted. The default is 30 days. Pinned captures, approved memories, and Quick Fill entries remain until deleted. Cloud memory deletions retain tombstones for 30 days to synchronize deletion safely.

Content is excluded from application logs, crash diagnostics, analytics, notification previews, and exported diagnostics. Users can export data, delete local data, delete cloud data, sign out, revoke MCP clients, and revoke Calendar access.

## Publication checklist

Before public distribution, replace this section with the publisher identity, support contact, account-deletion URL, legal basis, age policy, complete subprocessor list, exact deployed regions, transfer terms, incident contact, and Google Play Data Safety answers. Supabase, OpenAI, Google, and Cloudflare must be disclosed according to the deployed configuration.
