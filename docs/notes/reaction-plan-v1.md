# Reaction plan v1

Built from v0.3-chat-reliability-stable.

Scope: isolated Private Chat reactions only.

Design:
- Keep stable message transport, receipts, outbox, retry, network recovery, translation, notifications, and Telegram unchanged.
- Reactions use an independent table and repository.
- UI applies an optimistic local update immediately.
- Local device keeps that state until the server operation resolves.
- Realtime events update only the affected reaction, not the whole reaction snapshot.
- Initial reaction snapshot is loaded once when the chat opens.
