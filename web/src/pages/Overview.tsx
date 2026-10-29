import { ArrowRight, Coins, UsersThree } from '@phosphor-icons/react'
import { AGENT_META, api, timeAgo } from '../api'
import { AgentLogo, Empty, ErrorLine, PageHead, Panel, Skeleton, StatusDot, usePoll } from '../ui'

export default function Overview({ go }: { go: (page: string) => void }) {
  const status = usePoll(api.status, 8000)
  const cost = usePoll(api.cost, 8000)
  const team = usePoll(api.threads, 5000)
  const audit = usePoll(() => api.audit(8), 6000)

  const today = new Date().toISOString().slice(0, 10)
  const todays = cost.data?.entries.filter(e => e.timestamp.startsWith(today)) ?? []
  const online = status.data?.agents.filter(a => a.status === 'online').length ?? 0

  return (
    <>
      <PageHead eyebrow="Overview" title="Good to see you" sub="Your agents, what they did today, and what they handed to each other." />

      <div className="grid grid-cols-1 gap-5 md:grid-cols-[2fr_1fr]">
        <Panel>
          <div className="mb-5 flex items-baseline justify-between">
            <h2 className="text-sm font-medium text-ink-2">Agents</h2>
            <span className="font-mono text-xs text-ink-3">{status.data ? `${online}/${status.data.agents.length} online` : ''}</span>
          </div>
          {status.error && <ErrorLine msg={status.error} />}
          {!status.data ? <div className="grid grid-cols-2 gap-3">{[0, 1, 2, 3].map(i => <Skeleton key={i} className="h-20" />)}</div> : (
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {status.data.agents.map(a => (
                <li key={a.name} className="flex items-start gap-3 rounded-2xl border border-white/80 bg-white/45 p-4">
                  <AgentLogo name={a.name} size={40} />
                  <div>
                    <p className="flex items-center gap-2 text-[15px] font-medium text-ink">{AGENT_META[a.name]?.label ?? a.name} <StatusDot on={a.status === 'online'} /></p>
                    <p className="text-sm text-ink-3">{AGENT_META[a.name]?.role}</p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel inner="flex flex-col justify-between gap-6">
          <div className="flex items-center gap-2 text-sm font-medium text-ink-2"><Coins size={18} weight="light" aria-hidden="true" /> Today</div>
          {!cost.data ? <Skeleton className="h-24" /> : (
            <div className="flex flex-col gap-4">
              <div>
                <p className="font-mono text-4xl font-medium tracking-tight text-ink">{todays.length}</p>
                <p className="text-sm text-ink-3">agent calls</p>
              </div>
              <div>
                <p className="font-mono text-xl text-ink">${todays.reduce((s, e) => s + e.cost, 0).toFixed(3)}</p>
                <p className="text-sm text-ink-3">Claude at API prices (your subscription pays $0)</p>
              </div>
            </div>
          )}
          <button onClick={() => go('cost')} className="inline-flex cursor-pointer items-center gap-1.5 self-start text-sm font-medium text-navy hover:underline">Cost details <ArrowRight size={14} aria-hidden="true" /></button>
        </Panel>

        <Panel>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="flex items-center gap-2 text-sm font-medium text-ink-2"><UsersThree size={18} weight="light" aria-hidden="true" /> Team runs</h2>
            <button onClick={() => go('team')} className="cursor-pointer text-sm font-medium text-navy hover:underline">Open team</button>
          </div>
          {!team.data ? <Skeleton className="h-28" /> : team.data.threads.length === 0 ? (
            <Empty title="No team runs yet" hint="Give the team a task and watch the agents hand it to each other." />
          ) : (
            <ul className="divide-y divide-white/80">
              {team.data.threads.slice(0, 4).map(t => (
                <li key={t.id} className="flex items-center justify-between gap-4 py-3">
                  <p className="truncate text-sm text-ink">{t.message}</p>
                  <span className="shrink-0 font-mono text-xs text-ink-3">{t.status === 'running' ? `${t.active} working` : `${t.steps.length} hops`}</span>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel>
          <h2 className="mb-4 text-sm font-medium text-ink-2">Latest activity</h2>
          {!audit.data ? <Skeleton className="h-28" /> : audit.data.entries.length === 0 ? (
            <Empty title="Quiet so far" hint="Chats, skill runs and team runs show up here." />
          ) : (
            <ul className="flex flex-col gap-3">
              {[...audit.data.entries].reverse().slice(0, 5).map((e, i) => (
                <li key={e.id ?? i} className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="truncate text-ink">{e.action.replace(/_/g, ' ')}{e.agent ? `, ${e.agent}` : ''}{e.skill ? `, ${e.skill}` : ''}</span>
                  <span className="shrink-0 font-mono text-xs text-ink-3">{timeAgo(e.timestamp)}</span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </>
  )
}
