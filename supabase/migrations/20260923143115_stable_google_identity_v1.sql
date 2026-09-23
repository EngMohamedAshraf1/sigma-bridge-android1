begin;

-- Stable Private Chat identity:
-- one authenticated Google/Supabase account owns one canonical Sigma ID.
-- Devices remain separate and are attached to that account.

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
    v_public_id text;
    v_device_id uuid;
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
        if p_public_id !~ '^SB-[A-Z0-9]+(-[A-Z0-9]+)*$' then
            raise exception 'INVALID_PUBLIC_ID';
        end if;

        if length(p_public_id) > 40 then
            raise exception 'PUBLIC_ID_TOO_LONG';
        end if;

        if exists (
            select 1
            from public.users u
            where u.public_id = trim(p_public_id)
              and u.id <> v_user_id
        ) then
            raise exception 'PUBLIC_ID_ALREADY_IN_USE';
        end if;

        v_public_id := trim(p_public_id);

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
    returning id into v_device_id;

    return query
    select v_user_id, v_public_id, v_device_id;
end;
$$;

revoke all on function public.sigma_register_device(text, text, text) from public;
grant execute on function public.sigma_register_device(text, text, text) to authenticated;

create or replace function public.sigma_update_profile(
    p_public_id text,
    p_first_name text,
    p_last_name text,
    p_username text
)
returns table (
    public_id text,
    first_name text,
    last_name text,
    username text
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_public_id text;
    v_username text := lower(trim(p_username));
    v_first_name text := trim(p_first_name);
    v_last_name text := trim(p_last_name);
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    select u.public_id
    into v_public_id
    from public.users u
    where u.id = v_user_id
    for update;

    if v_public_id is null then
        raise exception 'PROFILE_NOT_REGISTERED';
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

    return query
    select u.public_id, u.first_name, u.last_name, u.username
    from public.users u
    where u.id = v_user_id;
end;
$$;

revoke all on function public.sigma_update_profile(text, text, text, text) from public;
grant execute on function public.sigma_update_profile(text, text, text, text) to authenticated;

commit;
