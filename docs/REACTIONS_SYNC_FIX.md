# Reactions sync fix

This change keeps the existing reactions database/RPC contract and improves client synchronization.

## Problem

Reactions were persisted in Supabase but could disappear after reopening the chat or fail to appear on the other device. The client relied on the timing of the realtime subscription and silently ignored snapshot failures.

## Fix

- Load the current reactions snapshot independently when a partner is available.
- Keep the realtime subscription for immediate remote updates.
- Re-synchronize the snapshot periodically while the ViewModel is active so a missed realtime event is eventually recovered.
- Preserve optimistic local reactions while a write is pending.
- Avoid changing message transport, encryption, replies, swipe-to-reply, or translation.

The rollback point is `backup/v0.8.11-before-reactions-20260912`.
