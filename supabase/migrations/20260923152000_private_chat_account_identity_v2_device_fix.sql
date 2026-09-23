begin;

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
    if v_user_id is null then raise exception 'AUTH_REQUIRED'; end if;
    if p_device_public_id is null or trim(p_device_public_id) = '' then raise exception 'DEVICE_PUBLIC_ID_REQUIRED'; end if;
    if p_identity_public_key is null or trim(p_identity_public_key) = '' then raise exception 'IDENTITY_PUBLIC_KEY_REQUIRED'; end if;

    select u.public_id into v_public_id
    from public.users u
    where u.id = v_user_id
    for update;

    if v_public_id is null then
        loop
            v_public_id := 'SB-' || upper(encode(gen_random_bytes(18), 'hex'));
            exit when not exists (select 1 from public.users where public_id = v_public_id);
        end loop;
        insert into public.users (id, public_id) values (v_user_id, v_public_id);
    end if;

    insert into public.devices (user_id, device_public_id, identity_public_key)
    values (v_user_id, trim(p_device_public_id), trim(p_identity_public_key))
    on conflict on constraint devices_user_id_device_public_id_key
    do update set
        identity_public_key = excluded.identity_public_key,
        last_seen_at = now()
    returning devices.id, devices.device_role into v_device_id, v_device_role;

    return query select v_user_id, v_device_id, v_device_role;
end;
$$;

revoke all on function public.sigma_register_account_device_v2(text,text) from public;
grant execute on function public.sigma_register_account_device_v2(text,text) to authenticated;

commit;