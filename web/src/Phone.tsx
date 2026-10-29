import { ArrowRight, DeviceMobile } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import { api, type AgentLimit } from './api'
import JharvisTalk, { type Mood } from './JharvisTalk'
import { Item } from './pages/Approvals'
import { GuardMini } from './pages/Cost'
import { ChromeButton, Empty, ErrorLine, Panel, inputCls, usePoll } from './ui'

/** Phone opened from the PC's QR (/#pair=<token>): trade the token once for the aos_token cookie.
 *  The fragment never reaches the server; it leaves the address bar before anything else runs. */
export async function pairFromHash() {
  if (!location.hash.startsWith('#pair=')) return
  const token = decodeURIComponent(location.hash.slice(6))
  history.replaceState(null, '', location.pathname + location.search)
  await fetch('/api/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) }).catch(() => {})
}

const PHONE = '(max-width: 767px)'  // Tailwind's md: the same width that swaps the rail for the tab bar
export function usePhone() {
  const [on, setOn] = useState(() => matchMedia(PHONE).matches)
  useEffect(() => {
    const m = matchMedia(PHONE), up = () => setOn(m.matches)
    m.addEventListener('change', up)
    return () => m.removeEventListener('change', up)
  }, [])
  return on
}

const Logo = () => (
  <span className="flex h-10 w-10 items-center justify-center rounded-xl border border-white bg-white/80" aria-hidden="true"><img src="/logo.svg" alt="" className="h-7 w-7" /></span>
)

/** A phone without the cookie: how to pair (QR from the browser, or the 6-digit code, which the Android app needs). */
export function Unpaired() {
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true); setErr(null)
    try {
      const r = await fetch('/api/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }) })
      if (r.ok) return location.reload()
      setErr(r.status === 401 ? 'Wrong or expired code. Check the PC, or press New code there.' : `Couldn't pair (${r.status}).`)
    } catch { setErr("Can't reach your PC. Is it awake and Tailscale on?") }
    setCode(''); setBusy(false)
  }
  return (
    <main className="flex min-h-[100dvh] items-center justify-center p-4">
      <div className="frost-mesh" aria-hidden="true" />
      <Panel className="w-full max-w-sm" inner="flex flex-col gap-4">
        <Logo />
        <h1 className="text-2xl font-medium tracking-tight text-ink">Pair this phone</h1>
        <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm leading-relaxed text-ink-2">
          <li>On the PC, open Agentic OS, then <b className="font-medium text-ink">Agents &amp; tools</b>.</li>
          <li>Press <b className="font-medium text-ink">Pair phone</b>.</li>
          <li>Type the 6-digit code below, or scan the QR with this phone's camera.</li>
        </ol>
        <form onSubmit={submit} className="flex flex-col gap-3">
          <label htmlFor="pair-code" className="text-sm font-medium text-ink-2">Pairing code</label>
          <input id="pair-code" value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} placeholder="000000"
            className={`${inputCls} text-center font-mono text-2xl tracking-[0.4em]`} />
          {err && <p role="alert" className="text-sm text-bad">{err}</p>}
          <ChromeButton type="submit" disabled={busy || code.length !== 6} icon={<ArrowRight size={16} aria-hidden="true" />}>Pair</ChromeButton>
        </form>
      </Panel>
    </main>
  )
}

/** PC only (Agents & tools): a QR and a 6-digit code that sign the phone in. Both come from PC-only routes
 *  (/api/pair/link, /api/pair/code); the QR is drawn here, never by a web service. */
export function PairPhone() {
  const [qr, setQr] = useState<string | null>(null)
  const [code, setCode] = useState<{ code: string; until: number } | null>(null)
  const [now, setNow] = useState(Date.now())
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    if (!code) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [code])
  const get = async (url: string) => {
    const r = await fetch(url)
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).detail || `Request failed (${r.status})`)
    return r.json()
  }
  const newCode = async () => {
    const c = await get('/api/pair/code')
    setCode({ code: c.code, until: Date.now() + c.expires_in * 1000 }); setNow(Date.now())
  }
  const show = async () => {
    setErr(null)
    try {
      const [link] = await Promise.all([get('/api/pair/link'), newCode()])
      const { renderSVG } = await import('uqr')
      setQr(renderSVG(link.url, { border: 2, pixelSize: 8, blackColor: '#0E1726', whiteColor: '#FFFFFF' }))
    } catch (e) { setErr((e as Error).message) }
  }
  const left = code ? Math.max(0, Math.ceil((code.until - now) / 1000)) : 0
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 className="flex items-center gap-2 text-sm font-medium text-ink-2"><DeviceMobile size={16} aria-hidden="true" />Phone</h2>
        <p className="text-sm text-ink-3">Scan with the phone's camera, or type the code in the Agentic OS app: it opens there already signed in (on your Tailscale network only).</p>
      </div>
      {qr ? (
        <div className="flex flex-wrap items-end gap-6">
          <img src={`data:image/svg+xml,${encodeURIComponent(qr)}`} alt="Pairing QR code" className="h-56 w-56 rounded-2xl border border-white" />
          <div className="flex flex-col gap-2">
            {code && left > 0 ? <>
              <p className="text-sm text-ink-3">Or type this code</p>
              <p className="font-mono text-5xl font-medium tracking-[0.15em] text-ink" aria-label={`Pairing code ${code.code.split('').join(' ')}`}>{code.code.slice(0, 3)} {code.code.slice(3)}</p>
              <p className="font-mono text-sm text-ink-3" aria-live="off">Works once, for {Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')}</p>
            </> : <p className="text-sm text-ink-3">Code expired.</p>}
            <div className="flex gap-2">
              <button type="button" onClick={() => newCode().catch(e => setErr((e as Error).message))}
                className="min-h-11 cursor-pointer rounded-full border border-white bg-white/70 px-5 text-sm text-ink hover:bg-white">New code</button>
              <button type="button" onClick={() => { setQr(null); setCode(null) }}
                className="min-h-11 cursor-pointer rounded-full border border-white bg-white/70 px-5 text-sm text-ink hover:bg-white">Hide</button>
            </div>
          </div>
        </div>
      ) : <ChromeButton onClick={show} icon={<DeviceMobile size={16} aria-hidden="true" />}>Pair phone</ChromeButton>}
      {err && <ErrorLine msg={err} />}
    </div>
  )
}

/** Highest use across an agent's windows, as a short label ("40%", "30/100"). */
function peak(l: AgentLimit) {
  const pct = l.windows.map(w => w.used_pct ?? (w.used != null && w.limit ? (100 * w.used) / w.limit : null)).filter((p): p is number => p != null)
  return pct.length ? `${Math.round(Math.max(...pct))}%` : '–'
}

const More = ({ href, children }: { href: string; children: string }) => (
  <a href={href} className="inline-flex min-h-11 items-center gap-1 text-sm font-medium text-navy">{children}<ArrowRight size={14} aria-hidden="true" /></a>
)

/** Jharvis's page on a phone: talk to him, answer approvals, see usage. No waves (too heavy for a phone). */
export function PhoneHome() {
  const energy = useRef(0), mood = useRef<Mood>('normal')
  const ap = usePoll(api.approvals, 5000)
  const limits = usePoll(api.agentLimits, 60000)
  const list = ap.data?.approvals ?? []
  return (
    <div className="mx-auto flex max-w-md flex-col gap-5">
      <header className="flex items-center gap-3 pr-14">
        <Logo />
        <p className="text-[15px] font-medium text-ink">Agentic OS</p>
      </header>

      <Panel inner="py-6"><JharvisTalk energy={energy} mood={mood} onReply={() => {}} big /></Panel>

      <section aria-labelledby="ph-approvals" className="flex flex-col gap-3">
        <div className="flex items-center justify-between px-1">
          <h2 id="ph-approvals" className="text-lg font-medium tracking-tight text-ink">
            Approvals {ap.data && <span className="ml-1 rounded-full bg-navy px-2 font-mono text-xs leading-5 text-white">{ap.data.count}</span>}
          </h2>
          {list.length > 0 && <More href="#/approvals">All</More>}
        </div>
        {ap.error && <ErrorLine msg={ap.error} />}
        {ap.data && list.length === 0 && <Panel><Empty title="Nothing waiting" hint="When an agent asks before acting, it shows up here." /></Panel>}
        {list.slice(0, 2).map(a => <Item key={a.id} a={a} onDone={ap.reload} />)}
      </section>

      <Panel inner="flex flex-col gap-1">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-medium tracking-tight text-ink">Usage</h2>
          <More href="#/cost">Billing</More>
        </div>
        <GuardMini />
        {limits.data && (
          <p className="-mt-4 px-3 font-mono text-[11px] leading-relaxed text-ink-3">
            {Object.values(limits.data).map(l => `${l.label} ${peak(l)}`).join(' · ')}
          </p>
        )}
      </Panel>
    </div>
  )
}
