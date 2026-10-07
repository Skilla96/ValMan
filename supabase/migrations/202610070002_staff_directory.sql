-- ValMan staff directory seed from the maintenance roster supplied by the administrator.
-- This is a directory only: it does NOT create login accounts or passwords.

create table if not exists public.staff_directory (
  id uuid primary key default gen_random_uuid(),
  badge_code text unique,
  roster_name text not null unique,
  trade text not null check (trade in ('CAPO_REPARTO','CAPO_SQUADRA','MECCANICO','ELETTRICO')),
  team text not null check (team in ('DIREZIONE','MECCANICA','ELETTRICA')),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_staff_roster_name on public.staff_directory(roster_name);
create index if not exists idx_staff_badge_code on public.staff_directory(badge_code);

-- Keep updated_at fresh.
drop trigger if exists trg_staff_directory_updated_at on public.staff_directory;
create trigger trg_staff_directory_updated_at before update on public.staff_directory
for each row execute function public.valman_touch_updated_at();

-- Visible roster data. Blank cartellino cells are intentionally left NULL.
insert into public.staff_directory (badge_code, roster_name, trade, team, active) values
  ('10250','ECCHER L.','CAPO_REPARTO','DIREZIONE',true),
  ('18331','CARRARO G.','CAPO_SQUADRA','MECCANICA',true),
  ('17919','MOTTIN D.','MECCANICO','MECCANICA',true),
  (null,'ASBOCK M.','MECCANICO','MECCANICA',true),
  ('18026','VISINTAINER N.','MECCANICO','MECCANICA',true),
  ('10479','SCHILLACI P.','MECCANICO','MECCANICA',true),
  (null,'CRISTOFORETTI A.','MECCANICO','MECCANICA',true),
  ('10472','GJEKA E.','MECCANICO','MECCANICA',true),
  (null,'KEMENATER N.','MECCANICO','MECCANICA',true),
  ('197','MICHELONI M.','CAPO_SQUADRA','ELETTRICA',true),
  ('190','VACCARI C.','ELETTRICO','ELETTRICA',true),
  (null,'PRESCIANOTTO G.','ELETTRICO','ELETTRICA',true),
  ('10312','COSTA M.','ELETTRICO','ELETTRICA',true),
  ('10313','STATILE V.','ELETTRICO','ELETTRICA',true)
on conflict (roster_name) do update set
  badge_code = excluded.badge_code,
  trade = excluded.trade,
  team = excluded.team,
  active = excluded.active,
  updated_at = now();

alter table public.staff_directory enable row level security;

drop policy if exists staff_read on public.staff_directory;
create policy staff_read on public.staff_directory for select to authenticated
using (public.valman_is_enabled());

drop policy if exists staff_admin_write on public.staff_directory;
create policy staff_admin_write on public.staff_directory for all to authenticated
using (public.valman_is_admin())
with check (public.valman_is_admin());
