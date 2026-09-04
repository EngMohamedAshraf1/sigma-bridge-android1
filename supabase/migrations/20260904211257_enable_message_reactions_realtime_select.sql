alter table public.message_reactions replica identity full;

alter publication supabase_realtime add table public.message_reactions;
