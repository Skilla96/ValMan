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
    if (!url || !secret || !publishable) throw new Error('SERVER_NOT_CONFIGURED')

    const body = await req.json()
    const employeeId = normalizeId(body.employeeId || body.id)
    const password = String(body.password || '')
    if (employeeId.length < 2 || !password) return Response.json({ error: 'BAD_INPUT' }, { status: 400, headers: cors })

    const admin = createClient(url, secret, { auth: { persistSession: false } })
    const { data: profile, error: profileErr } = await admin
      .from('profiles')
      .select('user_id,employee_id,name,role,enabled')
      .eq('employee_id', employeeId)
      .maybeSingle()

    if (profileErr) throw profileErr
    if (!profile || !profile.enabled) return Response.json({ error: 'INVALID_ACCOUNT' }, { status: 404, headers: cors })

    const { data: userData, error: userErr } = await admin.auth.admin.getUserById(profile.user_id)
    if (userErr || !userData.user?.email) throw userErr || new Error('AUTH_USER_NOT_FOUND')

    const email = userData.user.email
    const pub = createClient(url, publishable, { auth: { persistSession: false } })
    const { data: signed, error: signErr } = await pub.auth.signInWithPassword({ email, password })
    if (signErr || !signed.session) return Response.json({ error: 'INVALID_CREDENTIALS' }, { status: 401, headers: cors })

    return Response.json({
      ok: true,
      loginEmail: email,
      profile: {
        employeeId: profile.employee_id,
        name: profile.name,
        role: profile.role,
        enabled: profile.enabled,
      },
      session: signed.session,
    }, { headers: { ...cors, 'Content-Type': 'application/json' } })
  } catch (e) {
    return Response.json({ error: 'LOGIN_ID_ERROR', message: String(e?.message || e) }, { status: 500, headers: cors })
  }
})
