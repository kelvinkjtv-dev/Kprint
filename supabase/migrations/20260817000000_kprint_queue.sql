-- KPrint secure print queue for Supabase.
-- Apply with `supabase db push` or paste once in the Supabase SQL editor.

create table if not exists public.kprint_printers (
    id uuid primary key default gen_random_uuid(),
    store_id uuid not null,
    name text not null,
    token_hash text not null check (length(token_hash) = 64),
    active boolean not null default true,
    last_seen_at timestamptz,
    created_at timestamptz not null default now(),
    unique (id, store_id)
);

create table if not exists public.kprint_jobs (
    id uuid primary key default gen_random_uuid(),
    store_id uuid not null,
    order_id text not null,
    payload jsonb not null,
    status text not null default 'pending'
        check (status in ('pending', 'printing', 'printed', 'failed')),
    claimed_by uuid references public.kprint_printers(id),
    claimed_at timestamptz,
    printed_at timestamptz,
    attempts integer not null default 0,
    max_attempts integer not null default 100 check (max_attempts between 1 and 500),
    next_attempt_at timestamptz not null default now(),
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (store_id, order_id)
);

create index if not exists kprint_jobs_pending_idx
    on public.kprint_jobs (store_id, next_attempt_at, created_at)
    where status = 'pending';

alter table public.kprint_printers enable row level security;
alter table public.kprint_jobs enable row level security;

-- There are intentionally no direct table policies. The mobile app only executes
-- the two narrowly scoped functions below with its anon/publishable key.
revoke all on table public.kprint_printers from anon, authenticated;
revoke all on table public.kprint_jobs from anon, authenticated;

create or replace function public.kprint_claim_jobs(
    p_store_id uuid,
    p_device_id uuid,
    p_device_token text,
    p_limit integer default 5
)
returns table (
    id uuid,
    order_id text,
    payload jsonb,
    attempts integer,
    created_at timestamptz
)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if not exists (
        select 1
          from public.kprint_printers as printer
         where printer.id = p_device_id
           and printer.store_id = p_store_id
           and printer.active
           and printer.token_hash = encode(sha256(convert_to(p_device_token, 'UTF8')), 'hex')
    ) then
        raise exception 'KPrint device credentials are invalid' using errcode = '42501';
    end if;

    update public.kprint_printers as printer
       set last_seen_at = now()
     where printer.id = p_device_id;

    -- A process may die after claiming a job. Return stale claims to the queue.
    update public.kprint_jobs as stale
       set status = 'pending',
           claimed_by = null,
           claimed_at = null,
           next_attempt_at = now(),
           updated_at = now(),
           last_error = coalesce(stale.last_error, 'Claim expired before acknowledgement')
     where stale.store_id = p_store_id
       and stale.status = 'printing'
       and stale.claimed_at < now() - interval '5 minutes';

    return query
    with candidates as (
        select job.id
          from public.kprint_jobs as job
         where job.store_id = p_store_id
           and job.status = 'pending'
           and job.next_attempt_at <= now()
         order by job.next_attempt_at, job.created_at
         for update skip locked
         limit least(greatest(coalesce(p_limit, 5), 1), 20)
    )
    update public.kprint_jobs as job
       set status = 'printing',
           claimed_by = p_device_id,
           claimed_at = now(),
           attempts = job.attempts + 1,
           updated_at = now()
      from candidates
     where job.id = candidates.id
    returning job.id, job.order_id, job.payload, job.attempts, job.created_at;
end;
$$;

create or replace function public.kprint_complete_job(
    p_job_id uuid,
    p_device_id uuid,
    p_device_token text,
    p_printed boolean,
    p_error text default null
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    target public.kprint_jobs%rowtype;
begin
    if not exists (
        select 1
          from public.kprint_printers as printer
         where printer.id = p_device_id
           and printer.active
           and printer.token_hash = encode(sha256(convert_to(p_device_token, 'UTF8')), 'hex')
    ) then
        raise exception 'KPrint device credentials are invalid' using errcode = '42501';
    end if;

    select job.* into target
      from public.kprint_jobs as job
     where job.id = p_job_id
       and job.claimed_by = p_device_id
       and job.status = 'printing'
     for update;

    if not found then
        -- A repeated acknowledgement is safe.
        return exists (
            select 1 from public.kprint_jobs as job
             where job.id = p_job_id and job.status = 'printed'
        );
    end if;

    if p_printed then
        update public.kprint_jobs as job
           set status = 'printed',
               printed_at = now(),
               updated_at = now(),
               last_error = null
         where job.id = p_job_id;
    else
        update public.kprint_jobs as job
           set status = case when job.attempts >= job.max_attempts then 'failed' else 'pending' end,
               claimed_by = case when job.attempts >= job.max_attempts then job.claimed_by else null end,
               claimed_at = case when job.attempts >= job.max_attempts then job.claimed_at else null end,
               next_attempt_at = case
                   when job.attempts >= job.max_attempts then job.next_attempt_at
                   else now() + make_interval(secs => least(300, 5 * job.attempts * job.attempts))
               end,
               updated_at = now(),
               last_error = left(coalesce(p_error, 'Unknown print error'), 500)
         where job.id = p_job_id;
    end if;

    return true;
end;
$$;

-- Optional backend helper. Only service_role can enqueue jobs; never put that key in the app.
create or replace function public.kprint_enqueue_job(
    p_store_id uuid,
    p_order_id text,
    p_payload jsonb
)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    job_id uuid;
begin
    insert into public.kprint_jobs (store_id, order_id, payload)
    values (p_store_id, p_order_id, p_payload)
    on conflict (store_id, order_id) do update
        set payload = case
            when public.kprint_jobs.status = 'pending' then excluded.payload
            else public.kprint_jobs.payload
        end,
        updated_at = now()
    returning id into job_id;
    return job_id;
end;
$$;

revoke all on function public.kprint_claim_jobs(uuid, uuid, text, integer) from public;
revoke all on function public.kprint_complete_job(uuid, uuid, text, boolean, text) from public;
revoke all on function public.kprint_enqueue_job(uuid, text, jsonb) from public;

grant execute on function public.kprint_claim_jobs(uuid, uuid, text, integer) to anon, authenticated;
grant execute on function public.kprint_complete_job(uuid, uuid, text, boolean, text) to anon, authenticated;
grant execute on function public.kprint_enqueue_job(uuid, text, jsonb) to service_role;
