-- Fix sigma_register_device ambiguity caused by the RETURNS TABLE output
-- column named user_id colliding with devices.user_id in ON CONFLICT.

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

    if exists (
        select 1
        from public.users u
        where u.public_id = p_public_id
          and u.id <> v_user_id
    ) then
        raise exception 'PUBLIC_ID_ALREADY_IN_USE';
    end if;

    insert into public.users (id, public_id)
    values (v_user_id, p_public_id)
    on conflict (id) do update
        set public_id = excluded.public_id;

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
    on conflict on constraint devices_user_id_device_public_id_key
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
