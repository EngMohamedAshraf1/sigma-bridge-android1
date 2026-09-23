begin;

create or replace function public.sigma_register_account_device_v2(
    p_device_public_id text,
    p_identity_public_key text
)
returns table (user_id uuid, device_id uuid, device_role text)
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
    if v_user_id is null then raise exception 'AUTH_REQUIRED'; end if;
    if p_device_public_id is null or trim(p_device_public_id) = '' then raise exception 'DEVICE_PUBLIC_ID_REQUIRED'; end if;
    if p_identity_public_key is null or trim(p_identity_public_key) = '' then raise exception 'IDENTITY_PUBLIC_KEY_REQUIRED'; end if;

    select u.public_id into v_public_id
    from public.users u
    where u.id = v_user_id
    for update;

    if v_public_id is null then
        loop
            v_public_id := 'SB-' || upper(encode(extensions.gen_random_bytes(18), 'hex'));
            exit when not exists (select 1 from public.users where public_id = v_public_id);
        end loop;
        insert into public.users (id, public_id) values (v_user_id, v_public_id);
    end if;

    insert into public.devices (user_id, device_public_id, identity_public_key)
    values (v_user_id, trim(p_device_public_id), trim(p_identity_public_key))
    on conflict on constraint devices_user_id_device_public_id_key
    do update set identity_public_key = excluded.identity_public_key, last_seen_at = now()
    returning devices.id, devices.device_role into v_device_id, v_device_role;

    return query select v_user_id, v_device_id, v_device_role;
end;
$$;

revoke all on function public.sigma_register_account_device_v2(text,text) from public;
grant execute on function public.sigma_register_account_device_v2(text,text) to authenticated;

create or replace function public.sigma_ensure_conversation_v2(p_partner_user_id uuid)
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
    if not exists (select 1 from public.users u where u.id = p_partner_user_id) then raise exception 'PARTNER_NOT_FOUND'; end if;

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

create or replace function public.sigma_get_or_create_conversation_key_v2(p_conversation_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_key_material text;
begin
    if v_user_id is null then raise exception 'AUTH_REQUIRED'; end if;

    if not exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = p_conversation_id and cm.user_id = v_user_id
    ) then
        raise exception 'NOT_A_CONVERSATION_MEMBER';
    end if;

    insert into public.conversation_keys (conversation_id, key_material)
    values (p_conversation_id, encode(extensions.gen_random_bytes(32), 'hex'))
    on conflict (conversation_id) do nothing;

    select ck.key_material into v_key_material
    from public.conversation_keys ck
    where ck.conversation_id = p_conversation_id;

    return v_key_material;
end;
$$;

revoke all on function public.sigma_get_or_create_conversation_key_v2(uuid) from public;
grant execute on function public.sigma_get_or_create_conversation_key_v2(uuid) to authenticated;

commit;