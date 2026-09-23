begin;

create table if not exists public.conversation_keys (
    conversation_id uuid primary key references public.conversations(id) on delete cascade,
    key_material text not null,
    created_at timestamptz not null default now()
);

alter table public.conversation_keys enable row level security;
revoke all on table public.conversation_keys from public, anon, authenticated;

create or replace function public.sigma_get_or_create_conversation_key_v2(
    p_conversation_id uuid
)
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

insert into public.conversation_keys (conversation_id, key_material)
select c.id, encode(extensions.gen_random_bytes(32), 'hex')
from public.conversations c
left join public.conversation_keys ck on ck.conversation_id = c.id
where ck.conversation_id is null;

commit;