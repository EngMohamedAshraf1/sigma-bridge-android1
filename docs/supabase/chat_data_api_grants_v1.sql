-- Sigma Bridge Chat v1 - least-privilege Data API grants
-- Run this after the RLS policies and RPC functions are installed.
-- The Android client does not insert messages/receipts directly;
-- those writes go through SECURITY DEFINER RPC functions.

grant select on table public.messages to authenticated;
grant select on table public.message_receipts to authenticated;
grant select on table public.message_translations to authenticated;

-- No direct INSERT/UPDATE/DELETE grants are intentionally provided for
-- these chat tables in v1. Writes are performed by the RPC functions:
--   sigma_send_message
--   sigma_set_receipt
--   sigma_store_translation
