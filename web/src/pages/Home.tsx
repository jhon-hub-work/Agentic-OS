import { ArrowRight, Lightbulb } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useRef, useState } from 'react'
import { AGENT_META, timeAgo, type AgentName } from '../api'
import { AgentLogo, Panel, Skeleton, StatusDot, spring, usePoll } from '../ui'
import JharvisTalk from '../JharvisTalk'
import WaveField from '../WaveField'

type Item = { kind: string; title: string; detail?: string; action?: string; link?: string }
type HomeData = {
  online: boolean; urgent: Item | null; next: Item[]; next_run: string | null; suggestion: string
  owner: { name: string; timezone: string }
  agents: { name: AgentName; status: string }[]; last_run: { finished?: string; drafted: number } | null
}

const getHome = async (): Promise<HomeData> => {
  const r = await fetch('/api/jharvis/home')
  if (!r.ok) throw new Error(`Request failed (${r.status})`)
  return r.json()
}

const greeting = (tz: string) => {
  // the owner's own timezone (config/owner.json), not the PC's clock
  const h = +new Date().toLocaleString('en-US', { hour: 'numeric', hour12: false, timeZone: tz })
  return h < 12 ? 'Morning' : h < 18 ? 'Afternoon' : 'Evening'
}

/** "0 23 * * *" on the PC's clock -> the owner's timezone. */
const localTime = (cron: string | null, tz: string) => {
  const m = cron?.match(/^(\d+) (\d+) \* \* \*$/)
  if (!m) return cron
  const d = new Date()
  d.setHours(+m[2], +m[1], 0, 0)
  return d.toLocaleTimeString('en-AU', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: tz })
}

export default function Home() {
  const { data } = usePoll(getHome, 6000)
  const urgent = data?.urgent
  const energy = useRef(0)            // voice level for the waves (mic or Jharvis speaking)
  const [replies, setReplies] = useState(0)

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <span className="inline-flex items-center gap-2.5 rounded-full border border-white/90 bg-white/50 py-1.5 pl-2.5 pr-3.5 text-[11px] font-medium uppercase tracking-[0.2em] text-ink-2">
          <StatusDot on={!!data?.online} /> Jharvis · {data ? (data.online ? 'online' : 'not checking in') : '…'}
        </span>
        {data?.last_run?.finished && <span className="font-mono text-xs text-ink-3">Last job run {timeAgo(data.last_run.finished)}</span>}
      </header>

      <section className="grid grid-cols-1 items-center gap-6 lg:grid-cols-[1.1fr_1fr]">
        <div className="relative h-[260px] sm:h-[340px]">
          <div className="pointer-events-none absolute inset-[18%_6%] rounded-full bg-[radial-gradient(closest-side,rgba(63,182,236,0.28),transparent)] blur-2xl" />
          <WaveField pulse={(urgent ? urgent.title.length : 0) + replies} energy={energy} className="relative h-full w-full" />
          <p className="absolute inset-x-0 bottom-1 text-center font-mono text-[11px] uppercase tracking-[0.24em] text-ink-2">
            Listening · {data ? data.agents.filter(a => a.status === 'online').length : '–'} agents
          </p>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <h1 className="text-4xl font-medium leading-none tracking-tight text-ink md:text-5xl">{greeting(data?.owner.timezone ?? 'UTC')}, {data?.owner.name ?? ''}.</h1>
          <p className="max-w-[52ch] text-[15px] leading-relaxed text-ink-2">
            {urgent ? 'One thing needs you first.' : 'Nothing urgent. I’ll message you in Slack when that changes.'}
          </p>
          {!data ? <Skeleton className="h-44" /> : urgent ? (
            <motion.div key={urgent.title} initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={spring} className="glass-shell">
              <div className="glass flex flex-col gap-4 p-6">
                <span className="chrome self-start rounded-full px-3 py-1 text-[11px] uppercase tracking-[0.16em]">Most urgent</span>
                <h2 className="text-2xl font-medium leading-tight tracking-tight text-ink">{urgent.title}</h2>
                {urgent.detail && <p className="text-[15px] leading-relaxed text-ink-2">{urgent.detail}</p>}
                {urgent.link && (
                  <a href={urgent.link} className="chrome group inline-flex min-h-12 w-fit items-center gap-3 rounded-full py-1.5 pl-5 pr-1.5 text-[15px] font-medium transition-transform duration-200 ease-glide active:scale-[0.97]">
                    {urgent.action}
                    <span className="flex h-9 w-9 items-center justify-center rounded-full bg-white/15 transition-transform duration-300 ease-glide group-hover:translate-x-0.5"><ArrowRight size={16} weight="bold" aria-hidden="true" /></span>
                  </a>
                )}
              </div>
            </motion.div>
          ) : null}
        </div>
      </section>

      <JharvisTalk energy={energy} onReply={() => setReplies(n => n + 1)} />

      <section className="grid grid-cols-1 gap-5 md:grid-cols-3">
        <Panel>
          <h3 className="mb-2 text-sm font-medium text-ink-2">Next up</h3>
          {!data ? <Skeleton className="h-24" /> : (
            <ul className="divide-y divide-white/80">
              {data.next.map((i, k) => (
                <li key={k} className="flex items-center justify-between gap-3 py-3 text-[15px] text-ink">
                  {i.link ? <a href={i.link} className="hover:underline">{i.title}</a> : <span>{i.title}</span>}
                </li>
              ))}
              {data.next_run && (
                <li className="flex items-center justify-between gap-3 py-3 text-[15px] text-ink">
                  <span>Next job search</span><span className="font-mono text-xs text-ink-3">{localTime(data.next_run, data.owner.timezone)}</span>
                </li>
              )}
            </ul>
          )}
        </Panel>
        <Panel>
          <h3 className="mb-3 text-sm font-medium text-ink-2">Agents</h3>
          <div className="grid grid-cols-2 gap-2">
            {(data?.agents ?? []).map(a => (
              <div key={a.name} className="flex items-center gap-2.5 rounded-2xl bg-white/60 p-2.5 text-sm text-ink">
                <AgentLogo name={a.name} size={26} />{AGENT_META[a.name]?.label}
              </div>
            ))}
          </div>
        </Panel>
        <div className="glass-shell">
          <div className="flex h-full flex-col gap-3 rounded-[calc(1.75rem-0.375rem)] border border-white/15 bg-[linear-gradient(160deg,rgba(31,45,68,0.94),rgba(14,23,38,0.97))] p-6 text-[#DCE6F0]">
            <h3 className="flex items-center gap-2 text-sm font-medium text-[#9FDDF7]"><Lightbulb size={18} weight="light" aria-hidden="true" /> Jharvis suggests</h3>
            <p className="text-[15px] leading-relaxed">{data?.suggestion ?? '…'}</p>
          </div>
        </div>
      </section>
    </div>
  )
}
