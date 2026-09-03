-- Sigma Bridge Private Chat — primary-device translation relay.
-- Translation remains local to the primary Android device that owns Gemini keys.
-- The secondary device submits requests only; Supabase stores encrypted payloads/results.

create table if not exists public.translation_jobs (
    id uuid primary key default gen_random_uuid(),
    message_id uuid not null references public.messages(id) on delete cascade,
    requested_by_user_id text not null references public.users(id) on delete cascade,
    target_language text not null,
    status text not null default 'PENDING'
        check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempts integer not null default 0,
    translated_ciphertext text,
    translated_nonce text,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (message_id, requested_by_user_id, target_language)
);

create index if not exists idx_translation_jobs_pending_sender
    on public.translation_jobs(status, message_id, updated_at);

alter table public.translation_jobs enable row level security;

create or replace function public.touch_translation_job_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_translation_jobs_updated_at on public.translation_jobs;
create trigger trg_translation_jobs_updated_at
before update on public.translation_jobs
for each row
execute function public.touch_translation_job_updated_at();

-- Secondary device requests translation of a message using its local target language.
create or replace function public.sigma_request_translation(
    p_client_message_id uuid,
    p_target_language text
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id text := auth.uid()::text;
    v_message public.messages;
    v_job_id uuid;
begin
    if auth.uid() is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    select m.* into v_message
    from public.messages m
    join public.conversation_members cm
      on cm.conversation_id = m.conversation_id
     and cm.user_id = v_user_id
    where m.client_message_id = p_client_message_id
    order by m.created_at desc
    limit 1;

    if v_message.id is null then
        raise exception 'MESSAGE_NOT_ACCESSIBLE';
    end if;

    insert into public.translation_jobs (
        message_id,
        requested_by_user_id,
        target_language,
        status,
        attempts,
        translated_ciphertext,
        translated_nonce,
        last_error
    ) values (
        v_message.id,
        v_user_id,
        lower(trim(p_target_language)),
        'PENDING',
        0,
        null,
        null,
        null
    )
    on conflict (message_id, requested_by_user_id, target_language)
    do update set
        status = case
            when public.translation_jobs.status in ('FAILED', 'COMPLETED') then 'PENDING'
            else public.translation_jobs.status
        end,
        attempts = case
            when public.translation_jobs.status in ('FAILED', 'COMPLETED') then 0
            else public.translation_jobs.attempts
        end,
        translated_ciphertext = case
            when public.translation_jobs.status in ('FAILED', 'COMPLETED') then null
            else public.translation_jobs.translated_ciphertext
        end,
        translated_nonce = case
            when public.translation_jobs.status in ('FAILED', 'COMPLETED') then null
            else public.translation_jobs.translated_nonce
        end,
        last_error = null;

    select id into v_job_id
    from public.translation_jobs
    where message_id = v_message.id
      and requested_by_user_id = v_user_id
      and target_language = lower(trim(p_target_language));

    return v_job_id;
end;
$$;

-- The primary device can claim only jobs for messages it originally sent.
create or replace function public.sigma_claim_translation_jobs()
returns table (
    job_id uuid,
    client_message_id uuid,
    target_language text,
    ciphertext text,
    nonce text,
    message_version integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id text := auth.uid()::text;
begin
    if auth.uid() is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    return query
    with candidates as (
        select tj.id
        from public.translation_jobs tj
        join public.messages m on m.id = tj.message_id
        where m.sender_user_id = v_user_id
          and tj.requested_by_user_id <> v_user_id
          and tj.status = 'PENDING'
        order by tj.created_at
        for update of tj skip locked
        limit 10
    )
    update public.translation_jobs tj
       set status = 'PROCESSING',
           attempts = tj.attempts + 1,
           updated_at = now()
      from candidates c
     where tj.id = c.id
    returning tj.id,
              (select m.client_message_id from public.messages m where m.id = tj.message_id),
              tj.target_language,
              (select m.ciphertext from public.messages m where m.id = tj.message_id),
              (select m.nonce from public.messages m where m.id = tj.message_id),
              (select m.message_version from public.messages m where m.id = tj.message_id);
end;
$$;

create or replace function public.sigma_complete_translation_job(
    p_job_id uuid,
    p_translated_ciphertext text,
    p_translated_nonce text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id text := auth.uid()::text;
begin
    if auth.uid() is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    update public.translation_jobs tj
       set status = 'COMPLETED',
           translated_ciphertext = p_translated_ciphertext,
           translated_nonce = p_translated_nonce,
           last_error = null,
           updated_at = now()
      from public.messages m
     where tj.id = p_job_id
       and m.id = tj.message_id
       and m.sender_user_id = v_user_id;

    if not found then
        raise exception 'TRANSLATION_JOB_NOT_ACCESSIBLE';
    end if;
end;
$$;

create or replace function public.sigma_fail_translation_job(
    p_job_id uuid,
    p_error text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id text := auth.uid()::text;
begin
    if auth.uid() is null then
        raise exception 'AUTH_REQUIRED';
    end if;

    update public.translation_jobs tj
       set status = 'FAILED',
           last_error = left(coalesce(p_error, 'TRANSLATION_FAILED'), 200),
           updated_at = now()
      from public.messages m
     where tj.id = p_job_id
       and m.id = tj.message_id
       and m.sender_user_id = v_user_id;

    if not found then
        raise exception 'TRANSLATION_JOB_NOT_ACCESSIBLE';
    end if;
end;
$$;

create or replace function public.sigma_get_translation(
    p_client_message_id uuid,
    p_target_language text
)
returns table (
    status text,
    translated_ciphertext text,
    translated_nonce text,
    last_error text
)
language sql
security definer
set search_path = public
as $$
    select tj.status,
           tj.translated_ciphertext,
           tj.translated_nonce,
           tj.last_error
    from public.translation_jobs tj
    join public.messages m on m.id = tj.message_id
    where m.client_message_id = p_client_message_id
      and tj.requested_by_user_id = auth.uid()::text
      and tj.target_language = lower(trim(p_target_language))
    order by tj.created_at desc
    limit 1;
$$;

revoke all on table public.translation_jobs from anon, authenticated;
grant execute on function public.sigma_request_translation(uuid, text) to authenticated;
grant execute on function public.sigma_claim_translation_jobs() to authenticated;
grant execute on function public.sigma_complete_translation_job(uuid, text, text) to authenticated;
grant execute on function public.sigma_fail_translation_job(uuid, text) to authenticated;
grant execute on function public.sigma_get_translation(uuid, text) to authenticated;
