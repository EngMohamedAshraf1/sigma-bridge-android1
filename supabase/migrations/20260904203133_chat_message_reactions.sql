create table if not exists public.message_reactions (
    message_id uuid not null references public.messages(id) on delete cascade,
    user_id uuid not null references public.users(id) on delete cascade,
    emoji text not null,
    created_at timestamptz not null default now(),
    primary key (message_id, user_id),
    constraint message_reactions_emoji_check check (emoji in ('❤️','😂','👍','😢','😡','😍','🔥'))
);

alter table public.message_reactions enable row level security;

create index if not exists message_reactions_message_id_idx
    on public.message_reactions(message_id);

create or replace function public.sigma_set_reaction(
    p_message_id uuid,
    p_emoji text
) returns public.message_reactions
language plpgsql
security definer
set search_path = public
as $$
declare
    v_row public.message_reactions;
    v_conversation_id uuid;
begin
    if auth.uid() is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    if p_emoji not in ('❤️','😂','👍','😢','😡','😍','🔥') then
        raise exception 'INVALID_REACTION';
    end if;

    select m.conversation_id into v_conversation_id
      from public.messages m
     where m.id = p_message_id;

    if v_conversation_id is null or not public.is_conversation_member(v_conversation_id) then
        raise exception 'MESSAGE_NOT_ACCESSIBLE';
    end if;

    insert into public.message_reactions(message_id, user_id, emoji)
    values (p_message_id, auth.uid(), p_emoji)
    on conflict (message_id, user_id)
    do update set emoji = excluded.emoji, created_at = now()
    returning * into v_row;

    return v_row;
end;
$$;

create or replace function public.sigma_remove_reaction(
    p_message_id uuid
) returns boolean
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

    select m.conversation_id into v_conversation_id
      from public.messages m
     where m.id = p_message_id;

    if v_conversation_id is null or not public.is_conversation_member(v_conversation_id) then
        raise exception 'MESSAGE_NOT_ACCESSIBLE';
    end if;

    delete from public.message_reactions
     where message_id = p_message_id
       and user_id = auth.uid();

    return true;
end;
$$;

create or replace function public.sigma_get_reactions(
    p_partner_public_id text,
    p_conversation_key text
) returns table(
    client_message_id uuid,
    user_public_id text,
    emoji text,
    created_at timestamptz
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

    select c.id into v_conversation_id
      from public.conversations c
      join public.conversation_members cm
        on cm.conversation_id = c.id
       and cm.user_id = auth.uid()
     where c.conversation_key = p_conversation_key
       and exists (
           select 1
             from public.conversation_members cm2
             join public.users u2 on u2.id = cm2.user_id
            where cm2.conversation_id = c.id
              and u2.public_id = p_partner_public_id
       )
     limit 1;

    if v_conversation_id is null then
        raise exception 'CONVERSATION_NOT_ACCESSIBLE';
    end if;

    return query
    select m.client_message_id,
           u.public_id,
           r.emoji,
           r.created_at
      from public.message_reactions r
      join public.messages m on m.id = r.message_id
      join public.users u on u.id = r.user_id
     where m.conversation_id = v_conversation_id
     order by r.created_at asc;
end;
$$;

grant execute on function public.sigma_set_reaction(uuid,text) to authenticated;
grant execute on function public.sigma_remove_reaction(uuid) to authenticated;
grant execute on function public.sigma_get_reactions(text,text) to authenticated;
