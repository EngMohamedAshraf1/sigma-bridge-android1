begin;

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

    return v_conversation_id;
end;
$$;

commit;