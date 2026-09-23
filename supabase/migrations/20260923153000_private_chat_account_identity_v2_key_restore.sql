begin;

create table if not exists public.conversation_keys (
    conversation_id uuid primary key references public.conversations(id) on delete cascade,
    key_material text not null,
    created_at timestamptz not null default now()
);

alter table public.conversation_keys enable row level security;
revoke all on table public.conversation_keys from public, anon, authenticated;

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
    if v_my_user_id is null then raise exception 'AUTH_REQUIRED'; end if;
    if p_partner_user_id is null then raise exception 'PARTNER_REQUIRED'; end if;
    if p_partner_user_id = v_my_user_id then raise exception 'PARTNER_MUST_BE_DIFFERENT'; end if;
    if not exists (select 1 from public.users u where u.id = p_partner_user_id) then
        raise exception 'PARTNER_NOT_FOUND';
    end if;

    select c.id into v_conversation_id
    from public.conversations c
    join public.conversation_members mine on mine.conversation_id = c.id and mine.user_id = v_my_user_id
    join public.conversation_members partner on partner.conversation_id = c.id and partner.user_id = p_partner_user_id
    order by c.created_at asc
    limit 1;

    if v_conversation_id is not null then
        insert into public.conversation_keys (conversation_id, key_material)
        values (v_conversation_id, encode(extensions.gen_random_bytes(32), 'hex'))
        on conflict (conversation_id) do nothing;
        update public.conversations set updated_at = now() where id = v_conversation_id;
        return v_conversation_id;
    end if;

    v_conversation_key := encode(
        digest(
            'sigma-account-conversation-v2|' || least(v_my_user_id::text, p_partner_user_id::text) || '|' || greatest(v_my_user_id::text, p_partner_user_id::text),
            'sha256'
        ), 'hex'
    );

    insert into public.conversations (conversation_key)
    values (v_conversation_key)
    on conflict (conversation_key) do update set updated_at = now()
    returning id into v_conversation_id;

    if v_conversation_id is null then
        select c.id into v_conversation_id from public.conversations c where c.conversation_key = v_conversation_key;
    end if;

    insert into public.conversation_members (conversation_id, user_id, device_id)
    values (v_conversation_id, v_my_user_id, null), (v_conversation_id, p_partner_user_id, null)
    on conflict (conversation_id, user_id) do nothing;

    insert into public.conversation_keys (conversation_id, key_material)
    values (v_conversation_id, encode(extensions.gen_random_bytes(32), 'hex'))
    on conflict (conversation_id) do nothing;

    return v_conversation_id;
end;
$$;

revoke all on function public.sigma_ensure_conversation_v2(uuid) from public;
grant execute on function public.sigma_ensure_conversation_v2(uuid) to authenticated;

drop function if exists public.sigma_get_my_conversations_v2();

create function public.sigma_get_my_conversations_v2()
returns table (
    conversation_id uuid,
    partner_user_id uuid,
    partner_first_name text,
    partner_last_name text,
    partner_username text,
    partner_avatar_path text,
    conversation_key_material text,
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
    if v_user_id is null then raise exception 'AUTH_REQUIRED'; end if;

    return query
    select
        c.id,
        other_member.user_id,
        partner.first_name,
        partner.last_name,
        partner.username,
        partner.avatar_path,
        ck.key_material,
        lm.client_message_id,
        lm.sender_user_id,
        lm.ciphertext,
        lm.nonce,
        lm.message_version,
        lm.created_at
    from public.conversation_members mine
    join public.conversations c on c.id = mine.conversation_id
    join public.conversation_members other_member
      on other_member.conversation_id = c.id
     and other_member.user_id <> v_user_id
    join public.users partner on partner.id = other_member.user_id
    left join public.conversation_keys ck on ck.conversation_id = c.id
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

commit;