create policy reactions_select_member on public.message_reactions
for select
using (
    exists (
        select 1
          from public.messages m
         where m.id = message_reactions.message_id
           and public.is_conversation_member(m.conversation_id)
    )
);
