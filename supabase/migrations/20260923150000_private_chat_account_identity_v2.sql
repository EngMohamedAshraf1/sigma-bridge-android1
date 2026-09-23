begin;

-- Private Chat Account Identity v2
-- Account identity is Supabase Auth UUID. Username is discovery only.
-- conversation_id is the durable conversation identity.
-- The existing SB/public_id column remains as legacy compatibility metadata.
-- Telegram is intentionally outside this migration.

alter table public.devices
    add column if not exists device_role text not null default 'SECONDARY';

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'devices_device_role_check'
          and conrelid = 'public.devices'::regclass
    ) then
        alter table public.devices
            add constraint devices_device_role_check
            check (device_role in ('PRIMARY', 'SECONDARY'));
    end if;
end;
$$;

with ranked as (
    select
        id,
        row_number() over (
            partition by user_id
            order by created_at asc, id asc
        ) as rn
    from public.devices
)
update public.devices d
set device_role = case when ranked.rn = 1 then 'PRIMARY' else 'SECONDARY' end
from ranked
where ranked.id = d.id;

create or replace function public.sigma_register_account_device_v2(
    p_device_public_id text,
    p_identity_public_key text
)
returns table (
    user_id uuid,
    device_id uuid,
    device_role text
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_device_id uuid;
    v_device_role text;
    v_public_id text;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if p_device_public_id is null or trim(p_device_public_id) = '' then
        raise exception 'DEVICE_PUBLIC_ID_REQUIRED';
    end if;

    if p_identity_public_key is null or trim(p_identity_public_key) = '' then
        raise exception 'IDENTITY_PUBLIC_KEY_REQUIRED';
    end if;

    select u.public_id
    into v_public_id
    from public.users u
    where u.id = v_user_id
    for update;

    if v_public_id is null then
        loop
            v_public_id := 'SB-' || upper(encode(extensions.gen_random_bytes(18), 'hex'));
            exit when not exists (
                select 1
                from public.users
                where public_id = v_public_id
            );
        end loop;

        insert into public.users (id, public_id)
        values (v_user_id, v_public_id);
    end if;

    insert into public.devices (
        user_id,
        device_public_id,
        identity_public_key
    )
    values (
        v_user_id,
        trim(p_device_public_id),
        trim(p_identity_public_key)
    )
    on conflict on constraint devices_user_id_device_public_id_key
    do update set
        identity_public_key = excluded.identity_public_key,
        last_seen_at = now()
    returning devices.id, devices.device_role
    into v_device_id, v_device_role;

    return query
    select v_user_id, v_device_id, v_device_role;
end;
$$;

revoke all on function public.sigma_register_account_device_v2(text, text) from public;
grant execute on function public.sigma_register_account_device_v2(text, text) to authenticated;

create or replace function public.sigma_get_my_profile_v2()
returns table (
    user_id uuid,
    public_id text,
    first_name text,
    last_name text,
    username text,
    avatar_path text
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
    select u.id, u.public_id, u.first_name, u.last_name, u.username, u.avatar_path
    from public.users u
    where u.id = v_user_id;
end;
$$;

revoke all on function public.sigma_get_my_profile_v2() from public;
grant execute on function public.sigma_get_my_profile_v2() to authenticated;

create or replace function public.sigma_update_profile_v2(
    p_first_name text,
    p_last_name text,
    p_username text
)
returns table (
    user_id uuid,
    public_id text,
    first_name text,
    last_name text,
    username text,
    avatar_path text
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_username text := lower(trim(p_username));
    v_first_name text := trim(p_first_name);
    v_last_name text := trim(p_last_name);
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if v_username !~ '^[a-z0-9_]{3,24}$' then
        raise exception 'INVALID_USERNAME';
    end if;

    if length(v_first_name) > 40 or length(v_last_name) > 40 then
        raise exception 'NAME_TOO_LONG';
    end if;

    if exists (
        select 1
        from public.users u
        where lower(u.username) = v_username
          and u.id <> v_user_id
    ) then
        raise exception 'USERNAME_ALREADY_IN_USE';
    end if;

    update public.users
    set first_name = v_first_name,
        last_name = v_last_name,
        username = v_username,
        last_seen_at = now()
    where id = v_user_id;

    if not found then
        raise exception 'PROFILE_NOT_REGISTERED';
    end if;

    return query
    select u.id, u.public_id, u.first_name, u.last_name, u.username, u.avatar_path
    from public.users u
    where u.id = v_user_id;
end;
$$;

revoke all on function public.sigma_update_profile_v2(text, text, text) from public;
grant execute on function public.sigma_update_profile_v2(text, text, text) to authenticated;

create or replace function public.sigma_get_profile_by_user_id_v2(
    p_user_id uuid
)
returns table (
    user_id uuid,
    public_id text,
    first_name text,
    last_name text,
    username text,
    avatar_path text
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

    if p_user_id = v_user_id then
        raise exception 'SELF_PROFILE_NOT_REQUESTED';
    end if;

    return query
    select u.id, u.public_id, u.first_name, u.last_name, u.username, u.avatar_path
    from public.users u
    where u.id = p_user_id
    limit 1;
end;
$$;

revoke all on function public.sigma_get_profile_by_user_id_v2(uuid) from public;
grant execute on function public.sigma_get_profile_by_user_id_v2(uuid) to authenticated;

create or replace function public.sigma_get_last_seen_v2(
    p_user_id uuid
)
returns table (
    last_seen_at bigint
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_seen timestamptz;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    select u.last_seen_at
    into v_seen
    from public.users u
    where u.id = p_user_id;

    return query select case
        when v_seen is null then null
        else (extract(epoch from v_seen) * 1000)::bigint
    end;
end;
$$;

revoke all on function public.sigma_get_last_seen_v2(uuid) from public;
grant execute on function public.sigma_get_last_seen_v2(uuid) to authenticated;

create or replace function public.sigma_ensure_conversation_v2(
    p_partner_user_id uuid
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_my_user_id uuid := auth.uid();
    v_conversation_id uuid;
    v_conversation_key text;
begin
    if v_my_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if p_partner_user_id is null then
        raise exception 'PARTNER_REQUIRED';
    end if;

    if p_partner_user_id = v_my_user_id then
        raise exception 'PARTNER_MUST_BE_DIFFERENT';
    end if;

    if not exists (
        select 1
        from public.users u
        where u.id = p_partner_user_id
    ) then
        raise exception 'PARTNER_NOT_FOUND';
    end if;

    select c.id
    into v_conversation_id
    from public.conversations c
    join public.conversation_members mine
      on mine.conversation_id = c.id
     and mine.user_id = v_my_user_id
    join public.conversation_members partner
      on partner.conversation_id = c.id
     and partner.user_id = p_partner_user_id
    where c.status = 'ACTIVE'
    order by c.created_at asc
    limit 1;

    if v_conversation_id is not null then
        update public.conversations
        set updated_at = now()
        where id = v_conversation_id;

        return v_conversation_id;
    end if;

    v_conversation_key := encode(
        digest(
            'sigma-account-conversation-v2|' ||
            least(v_my_user_id::text, p_partner_user_id::text) || '|' ||
            greatest(v_my_user_id::text, p_partner_user_id::text),
            'sha256'
        ),
        'hex'
    );

    insert into public.conversations (conversation_key)
    values (v_conversation_key)
    on conflict (conversation_key)
    do update set updated_at = now()
    returning id into v_conversation_id;

    if v_conversation_id is null then
        select c.id
        into v_conversation_id
        from public.conversations c
        where c.conversation_key = v_conversation_key;
    end if;

    insert into public.conversation_members (conversation_id, user_id, device_id)
    values
        (v_conversation_id, v_my_user_id, null),
        (v_conversation_id, p_partner_user_id, null)
    on conflict (conversation_id, user_id)
    do nothing;

    return v_conversation_id;
end;
$$;

revoke all on function public.sigma_ensure_conversation_v2(uuid) from public;
grant execute on function public.sigma_ensure_conversation_v2(uuid) to authenticated;

create or replace function public.sigma_get_my_conversations_v2()
returns table (
    conversation_id uuid,
    partner_user_id uuid,
    partner_first_name text,
    partner_last_name text,
    partner_username text,
    partner_avatar_path text,
    last_client_message_id uuid,
    last_sender_user_id uuid,
    last_ciphertext text,
    last_nonce text,
    last_message_version integer,
    last_created_at timestamptz
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
        c.id,
        other_member.user_id,
        partner.first_name,
        partner.last_name,
        partner.username,
        partner.avatar_path,
        lm.client_message_id,
        lm.sender_user_id,
        lm.ciphertext,
        lm.nonce,
        lm.message_version,
        lm.created_at
    from public.conversation_members mine
    join public.conversations c
      on c.id = mine.conversation_id
     and c.status = 'ACTIVE'
    join public.conversation_members other_member
      on other_member.conversation_id = c.id
     and other_member.user_id <> v_user_id
    join public.users partner
      on partner.id = other_member.user_id
    left join lateral (
        select m.*
        from public.messages m
        where m.conversation_id = c.id
        order by m.sequence_number desc
        limit 1
    ) lm on true
    where mine.user_id = v_user_id
    order by coalesce(lm.created_at, c.updated_at) desc, c.created_at desc;
end;
$$;

revoke all on function public.sigma_get_my_conversations_v2() from public;
grant execute on function public.sigma_get_my_conversations_v2() to authenticated;

create or replace function public.sigma_send_message_v2(
    p_conversation_id uuid,
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
    v_sequence bigint;
    v_message public.messages;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if not exists (
        select 1
        from public.conversation_members cm
        where cm.conversation_id = p_conversation_id
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

    perform pg_advisory_xact_lock(
        hashtextextended(p_conversation_id::text, 0)
    );

    select coalesce(max(m.sequence_number), 0) + 1
    into v_sequence
    from public.messages m
    where m.conversation_id = p_conversation_id;

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
        p_conversation_id,
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

    update public.conversations
    set updated_at = now()
    where id = p_conversation_id;

    return v_message;
end;
$$;

revoke all on function public.sigma_send_message_v2(uuid, uuid, uuid, text, text, integer) from public;
grant execute on function public.sigma_send_message_v2(uuid, uuid, uuid, text, text, integer) to authenticated;

create or replace function public.sigma_get_undelivered_messages_v2()
returns table (
    message_id uuid,
    conversation_id uuid,
    sender_user_id uuid,
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
        m.id,
        m.conversation_id,
        m.sender_user_id,
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
    left join public.message_receipts mr
      on mr.message_id = m.id
     and mr.user_id = v_user_id
    where m.sender_user_id <> v_user_id
      and mr.delivered_at is null
    order by m.sequence_number asc
    limit 200;
end;
$$;

revoke all on function public.sigma_get_undelivered_messages_v2() from public;
grant execute on function public.sigma_get_undelivered_messages_v2() to authenticated;

create or replace function public.sigma_claim_translation_jobs_v2()
returns table (
    job_id uuid,
    client_message_id uuid,
    conversation_id uuid,
    peer_user_id uuid,
    target_language text,
    ciphertext text,
    nonce text,
    message_version integer
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
    with candidates as (
        select tj.id
        from public.translation_jobs tj
        join public.messages m
          on m.id = tj.message_id
        where m.sender_user_id = v_user_id
          and tj.requested_by_user_id <> v_user_id
          and (
              tj.status = 'PENDING'
              or (
                  tj.status = 'PROCESSING'
                  and tj.updated_at < now() - interval '2 minutes'
              )
          )
        order by tj.created_at
        for update of tj skip locked
        limit 10
    )
    update public.translation_jobs tj
    set status = 'PROCESSING',
        attempts = tj.attempts + 1,
        updated_at = now()
    from candidates c
    where tj.id = c.id
    returning
        tj.id,
        (select m.client_message_id from public.messages m where m.id = tj.message_id),
        (select m.conversation_id from public.messages m where m.id = tj.message_id),
        tj.requested_by_user_id,
        tj.target_language,
        (select m.ciphertext from public.messages m where m.id = tj.message_id),
        (select m.nonce from public.messages m where m.id = tj.message_id),
        (select m.message_version from public.messages m where m.id = tj.message_id);
end;
$$;

revoke all on function public.sigma_claim_translation_jobs_v2() from public;
grant execute on function public.sigma_claim_translation_jobs_v2() to authenticated;

create or replace function public.sigma_get_reactions_v2(
    p_conversation_id uuid
)
returns table (
    client_message_id uuid,
    user_id uuid,
    emoji text,
    created_at timestamptz
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

    if not exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = p_conversation_id
          and cm.user_id = v_user_id
    ) then
        raise exception 'NOT_A_CONVERSATION_MEMBER';
    end if;

    return query
    select m.client_message_id, r.user_id, r.emoji, r.created_at
    from public.message_reactions r
    join public.messages m on m.id = r.message_id
    where m.conversation_id = p_conversation_id
    order by r.created_at asc;
end;
$$;

revoke all on function public.sigma_get_reactions_v2(uuid) from public;
grant execute on function public.sigma_get_reactions_v2(uuid) to authenticated;

create or replace function public.sigma_get_reaction_context_v2(
    p_message_id uuid,
    p_user_id uuid
)
returns table (
    client_message_id uuid,
    user_id uuid
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
    select m.client_message_id, r.user_id
    from public.message_reactions r
    join public.messages m on m.id = r.message_id
    join public.conversation_members cm
      on cm.conversation_id = m.conversation_id
     and cm.user_id = v_user_id
    where r.message_id = p_message_id
      and r.user_id = p_user_id
    limit 1;
end;
$$;

revoke all on function public.sigma_get_reaction_context_v2(uuid, uuid) from public;
grant execute on function public.sigma_get_reaction_context_v2(uuid, uuid) to authenticated;

commit;