# Troubleshooting

This guide is for diagnosis of the current Sigma Bridge architecture. Always identify the exact branch and commit before debugging.

## 1. The app builds locally but Android Studio shows strange syntax errors

First check whether the local file is actually clean:

```powershell
git status
git diff -- app/src/main/java/com/sigmabridge/app/data/chat/SupabaseChatRepository.kt
```

If the repository is in the middle of a rebase or has unresolved conflict markers, do not edit around the errors blindly. Look for:

```text
<<<<<<<
=======
>>>>>>>
```

If the intended solution is to abandon the local work and use a known clean GitHub branch, abort the rebase and reset to the explicitly chosen remote reference.

## 2. Git pull says local changes would be overwritten

This means the working tree contains changes that conflict with incoming remote changes.

Safe procedure:

```powershell
git status
git diff
git diff --name-only --diff-filter=U
```

If the local work is important, commit or stash it. If the goal is simply to use the known remote baseline, create/use a clean branch and reset it to that exact remote branch.

Never use force-push as a shortcut for an uncertain merge.

## 3. Private Chat messages do not arrive

Trace the message path:

```text
ChatViewModel / background worker
      -> ChatRepository.send or observe
      -> Supabase RPC/read
      -> messages table
      -> decrypt
      -> ChatEvent.Message
```

Check:

- authenticated Supabase user exists;
- identity registration succeeded;
- partner public ID resolves;
- both participant devices are ready for the v1 conversation provisioning flow;
- conversation ID is the expected one;
- message sequence is advancing;
- ciphertext decrypts with the correct partner context.

Do not immediately change Supabase schema. First prove which stage failed.

## 4. A message arrives in the wrong conversation

This is a conversation-isolation bug.

Check these values together:

```text
partnerId
conversationKey
conversationTopic
conversationId
historyKey
message client_message_id
```

Then inspect whether any background operation accidentally reads `identity.partnerId` instead of carrying an explicit partner ID.

Particularly review:

- `ChatNotificationService`
- `ChatForegroundState`
- `ChatViewModel` translation callback
- `ChatHistoryStore` key selection
- repository conversation caching

Background processing should prefer explicit partner/conversation context.

## 5. Translation is slow

Slow translation should not block message visibility.

The expected behavior is:

```text
original message visible immediately
      |
      +--> Read receipt immediately
      |
      +--> translation asynchronously
```

If the original message does not appear until translation finishes, inspect `ChatViewModel` handling of `ChatEvent.Message`.

## 6. Translation fails and the message disappears

This violates the Private Chat UX contract.

Check that the message is created with:

```text
originalText = received text
translatedText = null
translationStatus = PENDING
```

and that the failure path changes only translation state while retaining `originalText`.

## 7. Read receipt is delayed until translation completes

This is a separation-of-concerns regression.

The Read receipt must be triggered directly from the incoming-message path before the translation coroutine.

Inspect `ChatViewModel` and make sure `sendReadReceiptForMessage(...)` has not been moved into a translation success callback.

## 8. Delivered/Read status never changes

Trace:

```text
receiver
 -> sendReadReceipt / sendDeliveredReceipt
 -> sigma_set_receipt
 -> message_receipts
 -> foreground polling
 -> ChatEvent.Delivered/Read
 -> ChatViewModel.updateReceiptStatus
```

Then inspect whether the receipt is filtered because `receiptSenderId != identity.partnerId`, whether the server message lookup uses the correct conversation, and whether the local history contains the same `client_message_id`.

## 9. Kotlin error near fetchReceipts()

The documented 6bb07de-based baseline previously contained a malformed nested `when` structure. The current release branch has a syntax correction using:

```kotlin
if (row.readAt != null) {
    ...
} else if (row.deliveredAt != null) {
    ...
}
```

Do not reintroduce the malformed structure. If a local checkout shows it, verify the branch/commit before making manual edits.

## 10. The app repeatedly says “new update available” for the version already installed

Check three values:

```text
BuildConfig.VERSION_NAME
GitHub latest release tag
APK actually installed on the device
```

The current checker compares the normalized `BuildConfig.VERSION_NAME` with GitHub `tag_name` and only reports an update when the remote version is greater.

For the v0.8.6 incident, the GitHub release was `v0.8.6` while the APK still embedded `0.8.5`. The project was corrected to versionCode 6 / versionName 0.8.6.

An APK must never be published under a release version that it does not itself report.

## 11. APK downloads but installation fails

Check:

- Android O+ install-from-unknown-sources permission;
- DownloadManager result status;
- downloaded URI;
- APK MIME type;
- package installer result.

The application does not silently grant itself install permissions. The user must enable the platform setting where required.

## 12. Supabase errors look inconsistent after a schema change

Do not assume the repository SQL files are the live schema.

Inspect:

- table definitions;
- primary/unique constraints;
- RLS policies;
- grants;
- function signatures;
- function definitions;
- indexes used by query filters.

A common debugging mistake is to read an old migration file and assume the live constraint still matches it.

## 13. Identity collision

If registration returns:

```text
PUBLIC_ID_ALREADY_IN_USE
```

the client has a bounded recovery path: regenerate the public ID once and retry registration.

Do not delete the existing user as a workaround.

## 14. App background behavior is broken

Separate these cases:

### User is currently viewing the conversation

`ChatForegroundState.openPartnerId` should identify that partner so the background service does not create unnecessary unread notifications for the open chat.

### User is not viewing the conversation

`ChatNotificationService` should be able to fetch messages, store unread IDs, send Delivered receipts, and post notifications.

### Device rebooted

For authenticated Private Chat users, `ChatBootReceiver` can start `ChatNotificationService` after `BOOT_COMPLETED`.

These are Private Chat behaviors. Do not confuse them with the legacy Telegram bridge service.

## 15. Debugging order

Use this order whenever possible:

```text
1. exact app version
2. exact Git commit/branch
3. reproduce on one device pair
4. identify message/client ID
5. identify server message/conversation IDs
6. inspect local history/outbox/unread state
7. inspect Supabase RPC/query result
8. only then modify code
```

The goal is to reduce the problem to one broken boundary rather than changing multiple layers simultaneously.
