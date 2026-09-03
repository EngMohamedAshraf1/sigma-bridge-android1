-- Sigma Bridge Private Chat — Schema v1
-- Phase 1: database foundation only.
-- Intentionally does not change Telegram/Gemini code or ntfy code.
-- RLS is enabled with deny-by-default posture; application access policies
-- will be added only after the authentication/transport boundary is finalized.

create extension if not exists pgcrypto;

create table if not exists public.users (
    id text primary key,
    created_at timestamptz not null default now()
);

create table if not exists public.devices (
    id uuid primary key default gen_random_uuid(),
    user_id text not null references public.users(id) on delete cascade,
    identity_public_key text not null,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz,
    is_active boolean not null default true,
    unique (user_id, id)
);

create index if not exists idx_devices_user_id on public.devices(user_id);

create table if not exists public.conversations (
    id uuid primary key default gen_random_uuid(),
    created_at timestamptz not null default now(),
    next_sequence bigint not null default 0,
    version integer not null default 1,
    status text not null default 'ACTIVE'
        check (status in ('ACTIVE', 'CLOSED'))
);

create table if not exists public.conversation_members (
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    user_id text not null references public.users(id) on delete cascade,
    device_id uuid not null references public.devices(id) on delete cascade,
    role text not null default 'MEMBER'
        check (role in ('MEMBER', 'OWNER')),
    joined_at timestamptz not null default now(),
    primary key (conversation_id, device_id)
);

create index if not exists idx_conversation_members_user
    on public.conversation_members(user_id, conversation_id);

create table if not exists public.sessions (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    local_device_id uuid not null references public.devices(id) on delete cascade,
    remote_device_id uuid not null references public.devices(id) on delete cascade,
    version integer not null default 1,
    key_version integer not null default 1,
    status text not null default 'ACTIVE'
        check (status in ('ACTIVE', 'RESET', 'CLOSED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (conversation_id, local_device_id, remote_device_id)
);

create index if not exists idx_sessions_conversation
    on public.sessions(conversation_id);

create table if not exists public.messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    sender_user_id text not null references public.users(id) on delete cascade,
    sender_device_id uuid not null references public.devices(id) on delete cascade,
    client_message_id uuid not null,
    sequence_number bigint not null,
    ciphertext text not null,
    nonce text not null,
    key_version integer not null default 1,
    message_version integer not null default 1,
    created_at timestamptz not null default now(),
    server_received_at timestamptz not null default now(),
    unique (conversation_id, client_message_id),
    unique (conversation_id, sequence_number)
);

create index if not exists idx_messages_conversation_sequence
    on public.messages(conversation_id, sequence_number);

create index if not exists idx_messages_conversation_created
    on public.messages(conversation_id, created_at);

create table if not exists public.message_translations (
    id uuid primary key default gen_random_uuid(),
    message_id uuid not null references public.messages(id) on delete cascade,
    source_language text not null,
    target_language text not null,
    translated_ciphertext text not null,
    nonce text not null,
    key_version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (message_id, source_language, target_language)
);

create index if not exists idx_message_translations_message
    on public.message_translations(message_id);

create table if not exists public.message_receipts (
    message_id uuid not null references public.messages(id) on delete cascade,
    user_id text not null references public.users(id) on delete cascade,
    device_id uuid not null references public.devices(id) on delete cascade,
    delivered_at timestamptz,
    read_at timestamptz,
    primary key (message_id, device_id)
);

create index if not exists idx_message_receipts_message
    on public.message_receipts(message_id);

create index if not exists idx_message_receipts_user
    on public.message_receipts(user_id, message_id);

-- Conversation-scoped monotonic sequence number.
create or replace function public.assign_message_sequence()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    next_seq bigint;
begin
    update public.conversations
       set next_sequence = next_sequence + 1
     where id = new.conversation_id
     returning next_sequence into next_seq;

    if next_seq is null then
        raise exception 'Conversation % does not exist', new.conversation_id;
    end if;

    new.sequence_number := next_seq;
    new.server_received_at := coalesce(new.server_received_at, now());
    return new;
end;
$$;

drop trigger if exists trg_messages_assign_sequence on public.messages;
create trigger trg_messages_assign_sequence
before insert on public.messages
for each row
when (new.sequence_number is null or new.sequence_number <= 0)
execute function public.assign_message_sequence();

create or replace function public.touch_session_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_sessions_updated_at on public.sessions;
create trigger trg_sessions_updated_at
before update on public.sessions
for each row
execute function public.touch_session_updated_at();

create or replace function public.touch_translation_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_message_translations_updated_at on public.message_translations;
create trigger trg_message_translations_updated_at
before update on public.message_translations
for each row
execute function public.touch_translation_updated_at();

-- Security baseline: every table is protected immediately.
-- We deliberately add no client-facing policies in Schema v1 yet, so direct
-- Data API access is deny-by-default until the authenticated boundary is finalized.
alter table public.users enable row level security;
alter table public.devices enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_members enable row level security;
alter table public.sessions enable row level security;
alter table public.messages enable row level security;
alter table public.message_translations enable row level security;
alter table public.message_receipts enable row level security;

-- Realtime publication for the chat event stream.
do $$
begin
    if not exists (
        select 1
        from pg_publication_rel pr
        join pg_publication p on p.oid = pr.prpubid
        join pg_class c on c.oid = pr.prrelid
        join pg_namespace n on n.oid = c.relnamespace
        where p.pubname = 'supabase_realtime'
          and n.nspname = 'public'
          and c.relname = 'messages'
    ) then
        alter publication supabase_realtime add table public.messages;
    end if;

    if not exists (
        select 1
        from pg_publication_rel pr
        join pg_publication p on p.oid = pr.prpubid
        join pg_class c on c.oid = pr.prrelid
        join pg_namespace n on n.oid = c.relnamespace
        where p.pubname = 'supabase_realtime'
          and n.nspname = 'public'
          and c.relname = 'message_translations'
    ) then
        alter publication supabase_realtime add table public.message_translations;
    end if;

    if not exists (
        select 1
        from pg_publication_rel pr
        join pg_publication p on p.oid = pr.prpubid
        join pg_class c on c.oid = pr.prrelid
        join pg_namespace n on n.oid = c.relnamespace
        where p.pubname = 'supabase_realtime'
          and n.nspname = 'public'
          and c.relname = 'message_receipts'
    ) then
        alter publication supabase_realtime add table public.message_receipts;
    end if;
end;
$$;
