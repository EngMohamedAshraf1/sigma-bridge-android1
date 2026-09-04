begin;

create or replace function public.sigma_get_reaction_context(
    p_message_id uuid,
    p_user_id uuid
) returns table(
    client_message_id uuid,
    user_public_id text
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_conversation_id uuid;
begin
    if auth.uid() is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    select m.conversation_id
      into v_conversation_id
      from public.messages m
     where m.id = p_message_id;

    if v_conversation_id is null then
        raise exception 'MESSAGE_NOT_FOUND';
    end if;

    if not public.is_conversation_member(v_conversation_id) then
        raise exception 'MESSAGE_NOT_ACCESSIBLE';
    end if;

    return query
    select m.client_message_id,
           u.public_id
      from public.messages m
      join public.users u on u.id = p_user_id
     where m.id = p_message_id;
end;
$$;

revoke all on function public.sigma_get_reaction_context(uuid, uuid) from public;
grant execute on function public.sigma_get_reaction_context(uuid, uuid) to authenticated;

commit;
