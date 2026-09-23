begin;

create or replace function public.sigma_search_users_v2(p_query text)
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
    v_query text := lower(trim(p_query));
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if length(v_query) < 2 then
        return;
    end if;

    return query
    select u.id, u.public_id, u.first_name, u.last_name, u.username, u.avatar_path
    from public.users u
    where u.username is not null
      and u.id <> v_user_id
      and lower(u.username) like '%' || v_query || '%'
    order by lower(u.username)
    limit 20;
end;
$$;

revoke all on function public.sigma_search_users_v2(text) from public;
grant execute on function public.sigma_search_users_v2(text) to authenticated;

commit;