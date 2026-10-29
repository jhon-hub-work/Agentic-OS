import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { AGENT_META, api, timeAgo, type AgentName } from '../api'
import { AgentLogo, Empty, ErrorLine, PageHead, Panel, Skeleton, usePoll } from '../ui'

export default function Cost() {
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
