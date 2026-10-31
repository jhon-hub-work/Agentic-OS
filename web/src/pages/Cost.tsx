import { useState } from 'react'
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { AGENT_META, api, timeAgo, type AgentLimit, type AgentName, type ClaudeGuard, type GuardUsage, type LimitWindow } from '../api'
import { AgentLogo, Empty, ErrorLine, PageHead, Panel, Skeleton, usePoll } from '../ui'

const JOB_NAMES: Record<string, string> = {
  verdict: 'Debate verdict', self_review: 'Daily self-review', ticket_plan: 'Ticket planning',
  jobs: 'Job search drafting', fallback: 'Backup when the free agents run out', check: 'Checking replies and fixes',
}
const WINDOWS: { key: keyof GuardUsage; label: string; short: string }[] = [
  { key: 'five_hour', label: '5-hour window', short: '5h' }, { key: 'seven_day', label: 'This week', short: 'wk' },
]
const selCls = 'min-h-10 rounded-xl border border-white bg-white/70 px-3 py-1.5 text-sm text-ink focus:outline-2 focus:outline-offset-2 focus:outline-navy disabled:opacity-50'
const useGuard = () => usePoll(api.claudeGuard, 60000)

/** Used % against the limit mark; red once at or over the limit. */
function UsageBar({ label, used, limit, thin }: { label: string; used: number | null; limit: number; thin?: boolean }) {
  return (
    <div role="meter" aria-label={`Claude ${label} used`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={used ?? 0}
      aria-valuetext={used == null ? 'unknown' : `${Math.round(used)}%, limit ${limit}%`}
      className={`relative ${thin ? 'h-1 flex-1' : 'h-2'} rounded-full bg-steel/20`}>
      {used != null && <div className={`h-full rounded-full ${used >= limit ? 'bg-bad' : 'bg-navy'}`} style={{ width: `${Math.max(1, Math.min(100, used))}%` }} />}
      <span aria-hidden="true" className={`absolute w-0.5 rounded-full bg-ink-3 ${thin ? '-inset-y-0.5' : '-inset-y-1'}`} style={{ left: `calc(${limit}% - 1px)` }} />
    </div>
  )
}

/** Sidebar: Claude usage at a glance, links to Billing. */
export function GuardMini() {
  const { data } = useGuard()
  if (!data) return null
  const u = data.usage
  return (
    <a href="#/cost" className="mb-6 flex flex-col gap-1.5 rounded-2xl px-3 py-2 text-ink-2 transition-colors duration-200 hover:bg-white/50 hover:text-ink"
      title={data.blocked ?? 'Background Claude allowed'}>
      <span className="font-mono text-[11px]">Claude {u ? WINDOWS.map(w => `${w.short} ${Math.round(u[w.key])}%`).join(' · ') : 'usage unknown'}</span>
      <span className="flex gap-2">{WINDOWS.map(w => <UsageBar key={w.key} label={w.label} used={u?.[w.key] ?? null} limit={data.limits[w.key]} thin />)}</span>
    </a>
  )
}

function GuardPanel({ g, reload }: { g: ClaudeGuard; reload: () => void }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [lim, setLim] = useState<Record<string, string>>({})  // typed but not saved yet
  const save = (s: Parameters<typeof api.saveGuard>[0]) => {
    setBusy(true); setErr(null)
    api.saveGuard(s).then(() => { setLim({}); reload() }).catch(e => setErr(String(e.message || e))).finally(() => setBusy(false))
  }
  const pick = (job: string, agent: AgentName) =>
    save({ background: { ...Object.fromEntries(Object.entries(g.jobs).map(([j, v]) => [j, v.picked])), [job]: agent } })
  const limVal = (k: keyof GuardUsage) => lim[k] ?? String(g.limits[k])
  const limOk = WINDOWS.every(w => /^\d+$/.test(limVal(w.key)) && +limVal(w.key) <= 100)
  const state = !g.usage ? ['text-ink-3', `Usage unknown: background Claude is paused, jobs use ${AGENT_META[g.default]?.label ?? g.default}`]
    : g.blocked ? ['text-warn', `${g.blocked}: background jobs use ${AGENT_META[g.default]?.label ?? g.default}`]
    : ['text-ok', 'Background Claude allowed']

  return (
    <Panel className="mb-5" inner="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h2 className="text-sm font-medium text-ink-2">Claude guard</h2>
        <p role="status" className={`text-sm ${state[0]}`}>{state[1]}</p>
      </div>
      <div className="grid grid-cols-1 gap-5 md:grid-cols-2 md:gap-x-10">
        {WINDOWS.map(w => (
          <div key={w.key} className="flex flex-col gap-2">
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-sm text-ink-3">{w.label}</span>
              <span className="font-mono text-ink"><span className="text-2xl font-medium tracking-tight">{g.usage ? `${Math.round(g.usage[w.key])}%` : '–'}</span>
                <span className="text-xs text-ink-3"> / limit {g.limits[w.key]}%</span></span>
            </div>
            <UsageBar label={w.label} used={g.usage?.[w.key] ?? null} limit={g.limits[w.key]} />
          </div>
        ))}
      </div>
      <div>
        <h3 className="mb-1 text-sm font-medium text-ink-2">Background jobs</h3>
        <ul className="divide-y divide-white/80">
          {Object.entries(g.jobs).map(([job, v]) => (
            <li key={job} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2.5">
              <div className="flex flex-col">
                <span className="text-sm text-ink">{JOB_NAMES[job] ?? job}
                  <span className="ml-2 text-xs text-ink-3">{v.tier === 'heavy' ? 'Heavy' : 'Light'}{v.model && ` · ${v.model}`}</span></span>
                {job === 'jobs' ? <span className="text-xs text-ink-3">Stays on Claude: it reads your CV, and free models may log prompts.</span>
                  : v.now !== v.picked && <span className="text-xs text-warn">Paused by the guard: runs on {AGENT_META[v.now]?.label ?? v.now} for now</span>}
              </div>
              <span className="flex items-center gap-2">
                <AgentLogo name={v.picked} size={24} />
                <select aria-label={`Agent for ${JOB_NAMES[job] ?? job}`} value={v.picked} disabled={busy || job === 'jobs'}
                  onChange={e => pick(job, e.target.value)} className={selCls}>
                  {Object.keys(AGENT_META).map(a => <option key={a} value={a}>{AGENT_META[a].label}</option>)}
                </select>
              </span>
            </li>
          ))}
        </ul>
      </div>
      <form className="flex flex-wrap items-end gap-3" onSubmit={e => { e.preventDefault(); if (limOk) save({ claude_guard: { five_hour: +limVal('five_hour'), seven_day: +limVal('seven_day') } }) }}>
        {WINDOWS.map(w => (
          <label key={w.key} className="flex flex-col gap-1 text-xs text-ink-3">{w.label} limit (%)
            <input type="number" min={0} max={100} required value={limVal(w.key)} onChange={e => setLim(l => ({ ...l, [w.key]: e.target.value }))} className={`${selCls} w-24 font-mono`} />
          </label>
        ))}
        {Object.keys(lim).length > 0 && <button type="submit" disabled={busy || !limOk} className={`${selCls} cursor-pointer font-medium hover:bg-white`}>Save limits</button>}
      </form>
      {err && <p role="alert" className="text-sm text-bad">Couldn't save: {err}</p>}
    </Panel>
  )
}

const clock = (s: number) => new Date(s * 1000).toLocaleString(undefined, s - Date.now() / 1000 < 86400
  ? { hour: '2-digit', minute: '2-digit' } : { weekday: 'short', hour: '2-digit', minute: '2-digit' })
const ago = (s: number) => timeAgo(new Date(s * 1000).toISOString())

function WindowMeter({ w, agent }: { w: LimitWindow; agent: string }) {
  const pct = w.used_pct ?? (w.limit ? (100 * (w.used ?? 0)) / w.limit : 0)
  const text = w.used_pct != null ? `${Math.round(w.used_pct)}% used` : `${Math.max(0, (w.limit ?? 0) - (w.used ?? 0))} of ${w.limit} left`
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-3 text-xs">
        <span className="text-ink-3">{w.name}</span><span className="font-mono text-sm text-ink">{text}</span>
      </div>
      <div role="meter" aria-label={`${agent} ${w.name} used`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)} aria-valuetext={text}
        className="h-1.5 rounded-full bg-steel/20">
        <div className={`h-full rounded-full ${pct >= 100 ? 'bg-bad' : pct >= 80 ? 'bg-warn' : 'bg-navy'}`} style={{ width: `${Math.max(1, Math.min(100, pct))}%` }} />
      </div>
      {w.resets_at != null && <span className="text-xs text-ink-3">Resets {clock(w.resets_at)}</span>}
    </div>
  )
}

/** Every agent's plan, windows and rest state; hidden until the server has /api/agent-limits. */
function LimitsPanel({ limits }: { limits: Record<AgentName, AgentLimit> }) {
  return (
    <Panel className="mb-5" inner="flex flex-col gap-2">
      <div className="flex flex-col gap-1">
        <h2 className="text-sm font-medium text-ink-2">All agents</h2>
        <p className="text-sm text-ink-3">What each agent has left before you pick one.</p>
      </div>
      <ul className="divide-y divide-white/80">
        {Object.entries(limits).map(([id, a]) => {
          const notes = [
            a.resting_until && <span key="r" className="text-warn">Resting until {clock(a.resting_until)}</span>,
            a.limit_hit_at && <span key="h" className="text-warn">Hit its free limit {ago(a.limit_hit_at)}</span>,
            a.source === 'estimate' && <span key="e">Estimate: counted by the OS</span>,
            a.source === 'not reported' && <span key="n">Limits not reported</span>,
            a.source === 'api' && a.read_at && Date.now() / 1000 - a.read_at > 600 && <span key="a">Reading from {ago(a.read_at)}</span>,
          ].filter(Boolean)
          return (
            <li key={id} className="grid grid-cols-1 gap-x-8 gap-y-3 py-3.5 md:grid-cols-[16rem_1fr] md:items-center">
              <div className="flex items-center gap-3">
                <AgentLogo name={id} size={28} />
                <div className="flex min-w-0 flex-col">
                  <span className="text-sm text-ink">{a.label}</span>
                  <span className="text-xs text-ink-3">{a.plan}{a.calls_today > 0 && ` · ${a.calls_today} call${a.calls_today === 1 ? '' : 's'} today`}</span>
                </div>
              </div>
              <div className="flex flex-col gap-2">
                {a.windows.length > 0 && <div className="grid grid-cols-1 gap-x-8 gap-y-3 sm:grid-cols-2">{a.windows.map(w => <WindowMeter key={w.name} w={w} agent={a.label} />)}</div>}
                {notes.length > 0 && <p className="flex flex-wrap gap-x-3 text-xs text-ink-3">{notes}</p>}
              </div>
            </li>
          )
        })}
      </ul>
    </Panel>
  )
}

export default function Cost() {
  const guard = useGuard()
  const limits = usePoll(api.agentLimits, 60000)
  const { data, error } = usePoll(api.cost, 10000)
  const entries = data?.entries ?? []
  const byAgent = (Object.keys(AGENT_META) as AgentName[]).map(a => {
    const rows = entries.filter(e => e.agent === a)
    return { agent: AGENT_META[a].label, calls: rows.length, cost: +rows.reduce((s, e) => s + e.cost, 0).toFixed(4) }
  })
  const total = entries.reduce((s, e) => s + e.cost, 0)
  const tokens = entries.reduce((s, e) => s + e.tokens, 0)

  return (
    <>
      <PageHead eyebrow="Billing" title="What the agents cost" sub="Every agent call is logged automatically. Claude shows what the API would charge; your subscription covers it. OpenCode and Hermes run on free models, so only calls are counted." />
      {guard.error && !guard.data && <ErrorLine msg={guard.error} />}
      {guard.data && <GuardPanel g={guard.data} reload={guard.reload} />}
      {limits.data && <LimitsPanel limits={limits.data} />}
      {error && <ErrorLine msg={error} />}
      {!data ? <Skeleton className="h-64" /> : (
        <div className="grid grid-cols-1 gap-5 md:grid-cols-[1fr_2fr]">
          <Panel inner="flex flex-col gap-6">
            {[
              ['Calls', entries.length.toLocaleString()],
              ['Tokens (Claude)', tokens.toLocaleString()],
              ['API-equivalent', `$${total.toFixed(3)}`],
            ].map(([k, v]) => (
              <div key={k} className="border-b border-white/80 pb-4 last:border-0 last:pb-0">
                <p className="text-sm text-ink-3">{k}</p>
                <p className="font-mono text-3xl font-medium tracking-tight text-ink">{v}</p>
              </div>
            ))}
          </Panel>
          <Panel>
            <h2 className="mb-4 text-sm font-medium text-ink-2">Calls per agent</h2>
            {entries.length === 0 ? <Empty title="No calls yet" hint="Chat with an agent or run the team; costs appear here." /> : (
              <div className="h-64" role="img" aria-label={byAgent.map(b => `${b.agent} ${b.calls} calls`).join(', ')}>
                <ResponsiveContainer>
                  <BarChart data={byAgent} margin={{ left: -20 }}>
                    <defs>
                      <linearGradient id="chromeBar" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" style={{ stopColor: 'var(--chart-a)' }} /><stop offset="100%" style={{ stopColor: 'var(--chart-b)' }} />
                      </linearGradient>
                    </defs>
                    <XAxis dataKey="agent" tickLine={false} axisLine={false} tick={{ fill: 'var(--color-ink-3)', fontSize: 12 }} />
                    <YAxis allowDecimals={false} tickLine={false} axisLine={false} tick={{ fill: 'var(--color-ink-3)', fontSize: 12 }} />
                    <Tooltip cursor={{ fill: 'rgba(255,255,255,0.5)' }} contentStyle={{ borderRadius: 12, border: '1px solid var(--color-white)', background: 'rgba(255,255,255,0.9)' }} />
                    <Bar dataKey="calls" fill="url(#chromeBar)" radius={[8, 8, 4, 4]} maxBarSize={48} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </Panel>
          <Panel className="md:col-span-2">
            <h2 className="mb-3 text-sm font-medium text-ink-2">Recent calls</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-xs text-ink-3">
                  <tr><th className="py-2 pr-4 font-medium">When</th><th className="py-2 pr-4 font-medium">Agent</th><th className="py-2 pr-4 font-medium">Model</th><th className="py-2 pr-4 text-right font-medium">Tokens</th><th className="py-2 text-right font-medium">Cost</th></tr>
                </thead>
                <tbody className="divide-y divide-white/80">
                  {[...entries].reverse().slice(0, 25).map((e, i) => (
                    <tr key={i}>
                      <td className="py-2.5 pr-4 text-ink-2">{timeAgo(e.timestamp)}</td>
                      <td className="py-2.5 pr-4 text-ink"><span className="flex items-center gap-2"><AgentLogo name={e.agent} size={22} />{AGENT_META[e.agent as AgentName]?.label ?? e.agent}</span></td>
                      <td className="py-2.5 pr-4 font-mono text-xs text-ink-2">{e.model}</td>
                      <td className="py-2.5 pr-4 text-right font-mono text-ink">{e.tokens.toLocaleString()}</td>
                      <td className="py-2.5 text-right font-mono text-ink">${e.cost.toFixed(4)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </div>
      )}
    </>
  )
}
