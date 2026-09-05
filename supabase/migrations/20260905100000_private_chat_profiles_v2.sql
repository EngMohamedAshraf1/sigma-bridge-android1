begin;

alter table public.users
    add column if not exists first_name text not null default '',
    add column if not exists last_name text not null default '',
    add column if not exists username text;

create unique index if not exists users_username_lower_uidx
    on public.users (lower(username))
    where username is not null;

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
    v_username text := lower(trim(p_username));
    v_first_name text := trim(p_first_name);
    v_last_name text := trim(p_last_name);
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if trim(p_public_id) = '' then
        raise exception 'PUBLIC_ID_REQUIRED';
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

    insert into public.users (id, public_id, first_name, last_name, username)
    values (v_user_id, trim(p_public_id), v_first_name, v_last_name, v_username)
    on conflict (id) do update set
        public_id = excluded.public_id,
        first_name = excluded.first_name,
        last_name = excluded.last_name,
        username = excluded.username,
        last_seen_at = now();

    return query
    select u.public_id, u.first_name, u.last_name, u.username
    from public.users u
    where u.id = v_user_id;
end;
$$;

revoke all on function public.sigma_update_profile(text, text, text, text) from public;
grant execute on function public.sigma_update_profile(text, text, text, text) to authenticated;

create or replace function public.sigma_get_my_profile()
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
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    return query
    select u.public_id, u.first_name, u.last_name, u.username
    from public.users u
    where u.id = v_user_id;
end;
$$;

revoke all on function public.sigma_get_my_profile() from public;
grant execute on function public.sigma_get_my_profile() to authenticated;

create or replace function public.sigma_search_users(p_query text)
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
    v_query text := lower(trim(p_query));
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if length(v_query) < 2 then
        return;
    end if;

    return query
    select u.public_id, u.first_name, u.last_name, u.username
    from public.users u
    where u.username is not null
      and u.id <> v_user_id
      and lower(u.username) like '%' || v_query || '%'
    order by lower(u.username)
    limit 20;
end;
$$;

revoke all on function public.sigma_search_users(text) from public;
grant execute on function public.sigma_search_users(text) to authenticated;

commit;
