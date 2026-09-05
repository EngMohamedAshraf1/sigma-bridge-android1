begin;

-- Discover first/undelivered private messages without requiring a locally
-- stored partner or a prior username search on the receiving device.
create or replace function public.sigma_get_undelivered_messages()
returns table (
    message_id uuid,
    conversation_id uuid,
    sender_public_id text,
    client_message_id uuid,
    sequence_number bigint,
    ciphertext text,
    nonce text,
    message_version integer,
    created_at timestamptz,
    server_received_at timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    return query
    select
        m.id as message_id,
        m.conversation_id,
        sender.public_id as sender_public_id,
        m.client_message_id,
        m.sequence_number,
        m.ciphertext,
        m.nonce,
        m.message_version,
        m.created_at,
        m.server_received_at
    from public.messages m
    join public.conversation_members cm
      on cm.conversation_id = m.conversation_id
     and cm.user_id = v_user_id
    join public.users sender
      on sender.id = m.sender_user_id
    left join public.message_receipts mr
      on mr.message_id = m.id
     and mr.user_id = v_user_id
    where m.sender_user_id <> v_user_id
      and mr.delivered_at is null
    order by m.sequence_number asc
    limit 200;
end;
$$;

revoke all on function public.sigma_get_undelivered_messages() from public;
grant execute on function public.sigma_get_undelivered_messages() to authenticated;

commit;
