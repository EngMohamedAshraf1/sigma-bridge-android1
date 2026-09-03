begin;

-- =========================================================
-- Sigma Bridge Chat RPC v1
-- Run this in the sigma-bridge-android Supabase SQL Editor.
-- These functions keep chat provisioning and message sequence
-- allocation server-side while the client remains authenticated.
-- =========================================================

-- ---------------------------------------------------------
-- 1) Register the existing SB-... identity + device
-- ---------------------------------------------------------

create or replace function public.sigma_register_device(
    p_public_id text,
    p_device_public_id text,
    p_identity_public_key text
)
returns table (
    user_id uuid,
    public_id text,
    device_id uuid
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_device_id uuid;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if p_public_id !~ '^SB-[A-Z0-9]+(-[A-Z0-9]+)*$' then
        raise exception 'INVALID_PUBLIC_ID';
    end if;

    if length(p_public_id) > 40 then
        raise exception 'PUBLIC_ID_TOO_LONG';
    end if;

    insert into public.users (id, public_id)
    values (v_user_id, p_public_id)
    on conflict (id) do update
        set public_id = excluded.public_id
    where public.users.public_id = excluded.public_id;

    if exists (
        select 1
        from public.users u
        where u.public_id = p_public_id
          and u.id <> v_user_id
    ) then
        raise exception 'PUBLIC_ID_ALREADY_IN_USE';
    end if;

    insert into public.devices (
        user_id,
        device_public_id,
        identity_public_key
    )
    values (
        v_user_id,
        p_device_public_id,
        p_identity_public_key
    )
    on conflict (user_id, device_public_id)
    do update set
        identity_public_key = excluded.identity_public_key,
        last_seen_at = now()
    returning id into v_device_id;

    return query
    select v_user_id, p_public_id, v_device_id;
end;
$$;

revoke all on function public.sigma_register_device(text, text, text) from public;
grant execute on function public.sigma_register_device(text, text, text) to authenticated;


-- ---------------------------------------------------------
-- 2) Create/find the deterministic 1-to-1 conversation
-- ---------------------------------------------------------

create or replace function public.sigma_ensure_conversation(
    p_partner_public_id text,
    p_conversation_key text
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_my_user_id uuid := auth.uid();
    v_partner_user_id uuid;
    v_conversation_id uuid;
    v_my_device_id uuid;
    v_partner_device_id uuid;
    v_device_a uuid;
    v_device_b uuid;
begin
    if v_my_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    select u.id
    into v_partner_user_id
    from public.users u
    where u.public_id = trim(p_partner_public_id)
    limit 1;

    if v_partner_user_id is null then
        raise exception 'PARTNER_NOT_FOUND';
    end if;

    if v_partner_user_id = v_my_user_id then
        raise exception 'PARTNER_MUST_BE_DIFFERENT';
    end if;

    select d.id
    into v_my_device_id
    from public.devices d
    where d.user_id = v_my_user_id
    order by d.created_at asc
    limit 1;

    select d.id
    into v_partner_device_id
    from public.devices d
    where d.user_id = v_partner_user_id
    order by d.created_at asc
    limit 1;

    if v_my_device_id is null or v_partner_device_id is null then
        raise exception 'PARTNER_DEVICE_NOT_READY';
    end if;

    if v_my_device_id::text < v_partner_device_id::text then
        v_device_a := v_my_device_id;
        v_device_b := v_partner_device_id;
    else
        v_device_a := v_partner_device_id;
        v_device_b := v_my_device_id;
    end if;

    insert into public.conversations (conversation_key)
    values (trim(p_conversation_key))
    on conflict (conversation_key)
    do update set updated_at = now()
    returning id into v_conversation_id;

    if v_conversation_id is null then
        select c.id
        into v_conversation_id
        from public.conversations c
        where c.conversation_key = trim(p_conversation_key);
    end if;

    insert into public.conversation_members (conversation_id, user_id, device_id)
    values
        (v_conversation_id, v_my_user_id, v_my_device_id),
        (v_conversation_id, v_partner_user_id, v_partner_device_id)
    on conflict (conversation_id, user_id)
    do update set device_id = excluded.device_id;

    insert into public.sessions (
        conversation_id,
        device_a_id,
        device_b_id
    )
    values (
        v_conversation_id,
        v_device_a,
        v_device_b
    )
    on conflict (conversation_id, device_a_id, device_b_id)
    do update set updated_at = now();

    return v_conversation_id;
end;
$$;

revoke all on function public.sigma_ensure_conversation(text, text) from public;
grant execute on function public.sigma_ensure_conversation(text, text) to authenticated;


-- ---------------------------------------------------------
-- 3) Insert one message with server-side sequence allocation
-- ---------------------------------------------------------

create or replace function public.sigma_send_message(
    p_conversation_key text,
    p_client_message_id uuid,
    p_sender_device_id uuid,
    p_ciphertext text,
    p_nonce text,
    p_message_version integer default 1
)
returns public.messages
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_conversation_id uuid;
    v_sequence bigint;
    v_message public.messages;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    select c.id
    into v_conversation_id
    from public.conversations c
    where c.conversation_key = trim(p_conversation_key)
    limit 1;

    if v_conversation_id is null then
        raise exception 'CONVERSATION_NOT_FOUND';
    end if;

    if not exists (
        select 1
        from public.conversation_members cm
        where cm.conversation_id = v_conversation_id
          and cm.user_id = v_user_id
    ) then
        raise exception 'NOT_A_CONVERSATION_MEMBER';
    end if;

    if not exists (
        select 1
        from public.devices d
        where d.id = p_sender_device_id
          and d.user_id = v_user_id
    ) then
        raise exception 'INVALID_SENDER_DEVICE';
    end if;

    -- Serialize sequence allocation per conversation.
    perform pg_advisory_xact_lock(hashtextextended(v_conversation_id::text, 0));

    select coalesce(max(m.sequence_number), 0) + 1
    into v_sequence
    from public.messages m
    where m.conversation_id = v_conversation_id;

    insert into public.messages (
        conversation_id,
        sender_user_id,
        sender_device_id,
        client_message_id,
        sequence_number,
        ciphertext,
        nonce,
        message_version
    )
    values (
        v_conversation_id,
        v_user_id,
        p_sender_device_id,
        p_client_message_id,
        v_sequence,
        p_ciphertext,
        p_nonce,
        p_message_version
    )
    on conflict (conversation_id, client_message_id)
    do update set client_message_id = excluded.client_message_id
    returning * into v_message;

    return v_message;
end;
$$;

revoke all on function public.sigma_send_message(text, uuid, uuid, text, text, integer) from public;
grant execute on function public.sigma_send_message(text, uuid, uuid, text, text, integer) to authenticated;


-- ---------------------------------------------------------
-- 4) Upsert a delivery/read receipt for the current user
-- ---------------------------------------------------------

create or replace function public.sigma_set_receipt(
    p_message_id uuid,
    p_delivered boolean default false,
    p_read boolean default false
)
returns public.message_receipts
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_receipt public.message_receipts;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if not exists (
        select 1
        from public.messages m
        join public.conversation_members cm
          on cm.conversation_id = m.conversation_id
        where m.id = p_message_id
          and cm.user_id = v_user_id
    ) then
        raise exception 'MESSAGE_NOT_ACCESSIBLE';
    end if;

    insert into public.message_receipts (
        message_id,
        user_id,
        delivered_at,
        read_at
    )
    values (
        p_message_id,
        v_user_id,
        case when p_delivered then now() else null end,
        case when p_read then now() else null end
    )
    on conflict (message_id, user_id)
    do update set
        delivered_at = coalesce(public.message_receipts.delivered_at, excluded.delivered_at),
        read_at = coalesce(public.message_receipts.read_at, excluded.read_at)
    returning * into v_receipt;

    return v_receipt;
end;
$$;

revoke all on function public.sigma_set_receipt(uuid, boolean, boolean) from public;
grant execute on function public.sigma_set_receipt(uuid, boolean, boolean) to authenticated;


-- ---------------------------------------------------------
-- 5) Store/update a translation while keeping the original intact
-- ---------------------------------------------------------

create or replace function public.sigma_store_translation(
    p_message_id uuid,
    p_source_language text,
    p_target_language text,
    p_translated_ciphertext text
)
returns public.message_translations
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_translation public.message_translations;
    v_conversation_id uuid;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    select m.conversation_id
    into v_conversation_id
    from public.messages m
    where m.id = p_message_id;

    if v_conversation_id is null then
        raise exception 'MESSAGE_NOT_FOUND';
    end if;

    if not exists (
        select 1
        from public.conversation_members cm
        where cm.conversation_id = v_conversation_id
          and cm.user_id = v_user_id
    ) then
        raise exception 'NOT_A_CONVERSATION_MEMBER';
    end if;

    insert into public.message_translations (
        message_id,
        source_language,
        target_language,
        translated_ciphertext
    )
    values (
        p_message_id,
        p_source_language,
        p_target_language,
        p_translated_ciphertext
    )
    on conflict (message_id, target_language)
    do update set
        source_language = excluded.source_language,
        translated_ciphertext = excluded.translated_ciphertext
    returning * into v_translation;

    return v_translation;
end;
$$;

revoke all on function public.sigma_store_translation(uuid, text, text, text) from public;
grant execute on function public.sigma_store_translation(uuid, text, text, text) to authenticated;

commit;
