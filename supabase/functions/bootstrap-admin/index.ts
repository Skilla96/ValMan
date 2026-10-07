import { createClient } from 'npm:@supabase/supabase-js@2'

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

function keyFromEnv(jsonName: string, legacyName: string) {
  const raw = Deno.env.get(jsonName) || ''
  if (raw) {
    try {
      const map = JSON.parse(raw)
      if (map.default) return map.default
      const first = Object.values(map)[0]
      if (typeof first === 'string') return first
    } catch (_) {}
  }
  return Deno.env.get(legacyName) || ''
}

function normalizeId(v: unknown) {
  return String(v || '').trim().toUpperCase().replace(/[^A-Z0-9_.-]/g, '').slice(0, 64)
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors })
  if (req.method !== 'POST') return Response.json({ error: 'METHOD_NOT_ALLOWED' }, { status: 405, headers: cors })

  try {
    const url = Deno.env.get('SUPABASE_URL')!
    const secret = keyFromEnv('SUPABASE_SECRET_KEYS', 'SUPABASE_SERVICE_ROLE_KEY')
    const publishable = keyFromEnv('SUPABASE_PUBLISHABLE_KEYS', 'SUPABASE_ANON_KEY')
    const expectedSetup = Deno.env.get('VALMAN_SETUP_CODE') || ''
    if (!url || !secret || !publishable || !expectedSetup) throw new Error('SERVER_NOT_CONFIGURED')

    const body = await req.json()
    const employeeId = normalizeId(body.employeeId || body.id)
    const name = String(body.name || '').trim().slice(0, 120)
    const password = String(body.password || '')
    const setupCode = String(body.setupCode || '')
    if (setupCode !== expectedSetup) return Response.json({ error: 'BAD_SETUP_CODE' }, { status: 403, headers: cors })
    if (employeeId.length < 2 || !name || password.length < 8) return Response.json({ error: 'BAD_INPUT' }, { status: 400, headers: cors })

    const admin = createClient(url, secret, { auth: { persistSession: false } })
    const { count, error: countErr } = await admin.from('profiles').select('*', { count: 'exact', head: true })
    if (countErr) throw countErr
    if ((count || 0) > 0) return Response.json({ error: 'ALREADY_BOOTSTRAPPED' }, { status: 409, headers: cors })

    const email = `${employeeId.toLowerCase()}@valman.internal`
    const { data: created, error: createErr } = await admin.auth.admin.createUser({ email, password, email_confirm: true })
    if (createErr || !created.user) throw createErr || new Error('CREATE_USER_FAILED')

    const { error: profileErr } = await admin.from('profiles').insert({
      user_id: created.user.id,
      employee_id: employeeId,
      name,
      role: 'ADMIN',
      enabled: true,
    })
    if (profileErr) throw profileErr

    await admin.from('audit_log').insert({ user_id: created.user.id, action: 'BOOTSTRAP_ADMIN', entity: 'profile', entity_id: employeeId })

    const pub = createClient(url, publishable, { auth: { persistSession: false } })
    const { data: signed, error: signErr } = await pub.auth.signInWithPassword({ email, password })
    if (signErr) throw signErr

    return Response.json({
      ok: true,
      profile: { employeeId, name, role: 'ADMIN', enabled: true },
      session: signed.session,
    }, { headers: { ...cors, 'Content-Type': 'application/json' } })
  } catch (e) {
    return Response.json({ error: 'BOOTSTRAP_ERROR', message: String(e?.message || e) }, { status: 500, headers: cors })
  }
})
