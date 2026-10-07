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

async function sha256Hex(value: string) {
  const bytes = new TextEncoder().encode(value)
  const hash = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, '0')).join('')
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
    const activationCode = String(body.activationCode || '').trim().toUpperCase()
    const password = String(body.password || '')
    if (employeeId.length < 2 || activationCode.length < 4 || password.length < 8) {
      return Response.json({ error: 'BAD_INPUT' }, { status: 400, headers: cors })
    }

    const admin = createClient(url, secret, { auth: { persistSession: false } })
    const { data: invite, error: inviteErr } = await admin.from('employee_invites').select('*').eq('employee_id', employeeId).maybeSingle()
    if (inviteErr) throw inviteErr
    if (!invite || !invite.enabled || invite.claimed_by) return Response.json({ error: 'INVALID_INVITE' }, { status: 404, headers: cors })

    const givenHash = await sha256Hex(activationCode)
    if (givenHash !== invite.activation_hash) return Response.json({ error: 'BAD_ACTIVATION_CODE' }, { status: 403, headers: cors })

    const email = `${employeeId.toLowerCase()}@valman.internal`
    const { data: created, error: createErr } = await admin.auth.admin.createUser({ email, password, email_confirm: true })
    if (createErr || !created.user) throw createErr || new Error('CREATE_USER_FAILED')

    const { error: profileErr } = await admin.from('profiles').insert({
      user_id: created.user.id,
      employee_id: employeeId,
      name: invite.name,
      role: invite.role,
      enabled: true,
    })
    if (profileErr) throw profileErr

    await admin.from('employee_invites').update({ claimed_by: created.user.id, claimed_at: new Date().toISOString() }).eq('employee_id', employeeId)
    await admin.from('audit_log').insert({ user_id: created.user.id, action: 'CLAIM_ACCOUNT', entity: 'profile', entity_id: employeeId })

    const pub = createClient(url, publishable, { auth: { persistSession: false } })
    const { data: signed, error: signErr } = await pub.auth.signInWithPassword({ email, password })
    if (signErr) throw signErr

    return Response.json({
      ok: true,
      profile: { employeeId, name: invite.name, role: invite.role, enabled: true },
      session: signed.session,
    }, { headers: { ...cors, 'Content-Type': 'application/json' } })
  } catch (e) {
    return Response.json({ error: 'CLAIM_ERROR', message: String(e?.message || e) }, { status: 500, headers: cors })
  }
})
