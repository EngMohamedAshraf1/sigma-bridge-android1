# Supabase Integration

## Purpose

Supabase is the remote coordination and persistence layer for Private Chat. The Android client uses Supabase Auth, PostgREST RPCs, PostgREST reads, and Storage for chat data and profiles.

This document describes the client-facing contract visible in the current Android code and the SQL snapshots committed under `docs/supabase/`.

**Important:** the SQL files in this repository are migration/configuration snapshots, not a guarantee that the live Supabase project is byte-for-byte identical. Before changing production database objects, inspect the live schema, constraints, policies, and function definitions first.

## Authentication boundary

Private Chat uses the authenticated Supabase user as the server-side owner of data. The database functions use `auth.uid()` to identify the caller.

The client does not send an arbitrary user UUID and ask the database to trust it. The authenticated session is the authority.

Google sign-in is performed through AndroidX Credential Manager and Google ID. The resulting ID token is passed to Supabase Auth. The chat repositories then operate through the authenticated session.

## Core logical entities

The current SQL/client contract references these logical entities:

```text
users
  -> stable Supabase auth user + Sigma public identity

devices
  -> installations/devices belonging to a user

conversations
  -> deterministic 1-to-1 conversation container

conversation_members
  -> participants and their associated device IDs

sessions
  -> ordered device pair associated with a conversation

messages
  -> encrypted message transport records

message_receipts
  -> Delivered/Read state per receiving user

message_translations
  -> encrypted translated text records
```

The exact live columns and constraints must be verified before database modifications.

## RPC contract

The current Android client depends on these Private Chat RPCs.

### `sigma_register_device`

Input:

```text
p_public_id
p_device_public_id
p_identity_public_key
```

Purpose:

- authenticate the caller
- validate the `SB-...` public ID
- reject collisions with another authenticated user
- upsert the user's public identity
- upsert/register the device
- return the caller's user ID, public ID, and device ID

Important errors include:

```text
AUTH_REQUIRED
INVALID_PUBLIC_ID
PUBLIC_ID_TOO_LONG
PUBLIC_ID_ALREADY_IN_USE
```

The Android client treats `PUBLIC_ID_ALREADY_IN_USE` specially and can regenerate its persisted public ID once before retrying.

### `sigma_ensure_conversation`

Input:

```text
p_partner_public_id
p_conversation_key
```

Purpose:

- resolve the partner's authenticated user by public ID
- reject self-conversations
- resolve the earliest/current device records used by the v1 session model
- create/find the deterministic conversation by `conversation_key`
- upsert conversation membership
- upsert the device-pair session record
- return the authoritative `conversation_id`

Important errors include:

```text
AUTH_REQUIRED
PARTNER_NOT_FOUND
PARTNER_MUST_BE_DIFFERENT
PARTNER_DEVICE_NOT_READY
```

### `sigma_send_message`

Input:

```text
p_conversation_key
p_client_message_id
p_sender_device_id
p_ciphertext
p_nonce
p_message_version
```

Purpose:

- verify the caller is authenticated
- resolve the conversation
- verify that the caller is a conversation member
- verify that the sender device belongs to the caller
- serialize message sequence allocation using a PostgreSQL advisory transaction lock
- insert the encrypted message
- make the client UUID idempotent inside the conversation
- return the inserted message row

The server, not the device clock, owns the authoritative `sequence_number`.

### `sigma_set_receipt`

Input:

```text
p_message_id
p_delivered
p_read
```

Purpose:

- verify the authenticated caller can access the message through conversation membership
- create/update the caller's receipt
- preserve already-recorded `delivered_at` and `read_at` timestamps instead of regressing them

The current SQL snapshot uses an upsert on `(message_id, user_id)`. This exact constraint must still be verified against the live database before any schema change.

### `sigma_store_translation`

Input:

```text
p_message_id
p_source_language
p_target_language
p_translated_ciphertext
```

Purpose:

- verify authentication
- resolve the message's conversation
- verify membership
- store or update encrypted translated ciphertext without modifying the original message
- key the translation by message and target language

## Read/write permissions

The committed `chat_data_api_grants_v1.sql` intentionally gives the `authenticated` role SELECT access to:

```text
messages
message_receipts
message_translations
```

It intentionally does not grant direct INSERT/UPDATE/DELETE for those chat tables. Writes are expected to go through the RPC functions.

This is an important architectural rule: application code should not bypass the established server-side authorization path simply because direct PostgREST writes are technically possible after a permission change.

## Message row contract

The Android model expects a message row containing the following fields:

```text
id
conversation_id
sender_user_id
sender_device_id
client_message_id
sequence_number
ciphertext
nonce
message_version
created_at
server_received_at
```

The client treats `client_message_id` as the stable bridge between local chat state and the server message row.

## Receipt row contract

The Android model expects:

```text
message_id
user_id
device_id (optional in the client model)
delivered_at
read_at
```

The client maps the authoritative server message UUID back to `client_message_id` before emitting the domain-level receipt event.

## Translation job/relay contract

The Android relay repository references additional RPCs for remote translation work:

```text
sigma_request_translation
sigma_get_translation
sigma_claim_translation_jobs
sigma_complete_translation_job
sigma_fail_translation_job
```

These are coordination APIs between a primary/secondary translation worker architecture and do not belong to Telegram.

The relay path is intentionally separate from the normal message transport path.

## Supabase security rules for maintainers

1. Never put a Supabase `service_role` key in Android source, resources, APK assets, or documentation.
2. Keep privileged writes behind authenticated RPCs or explicitly reviewed RLS policies.
3. Before modifying an RPC used by the released APK, inspect all Android callers and all database callers.
4. Before changing a constraint used by `ON CONFLICT`, inspect the live `pg_constraint` definition.
5. Before changing RLS, inspect both policies and grants. A policy alone does not define the complete Data API authorization surface.
6. Test with an authenticated client, not only with an elevated SQL editor session.
7. Never use a cleanup migration to remove real user records as part of routine application debugging.

## Existing repository SQL files

```text
docs/supabase/
├── chat_data_api_grants_v1.sql
└── sigma_bridge_chat_rpc_v1.sql
```

These files are valuable implementation references. They are not permission to execute destructive SQL against production without verification.

## Debugging methodology

When a Private Chat Supabase bug appears, trace one complete object through the system:

```text
local ChatMessage.id
       |
       v
client_message_id
       |
       v
messages.id (server UUID)
       |
       +--> message_receipts.message_id
       |
       +--> message_translations.message_id
```

Then separately trace:

```text
my public ID
partner public ID
conversation key
conversation_id
my device ID
partner device ID
```

Do not replace one identifier with another merely because both look like UUID/string values. They have different ownership and lifecycle semantics.

## Important current-baseline receipt behavior

The current `private-chat-6bb07de-fix` baseline resolves the server message for receipt submission through the active conversation and `client_message_id`. The later experimental message-centric receipt change is intentionally not part of this baseline.

This matters when comparing Git branches: a newer receipt implementation may be a useful experiment, but it should not be silently described as the current release architecture.
