import { ArrowDown, PaperPlaneRight } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { AGENT_META, api, timeAgo, type AgentName, type Thread } from '../api'
import { AgentLogo, ChromeButton, Empty, ErrorLine, PageHead, Panel, Prose, Skeleton, spring, usePoll } from '../ui'

const AGENTS = Object.keys(AGENT_META) as AgentName[]

export default function Team() {
  const list = usePoll(api.threads, 4000)
  const [selected, setSelected] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [agent, setAgent] = useState<AgentName>('claude')
  const [sending, setSending] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const threads = list.data?.threads ?? []
  const current = threads.find(t => t.id === selected) ?? threads[0]

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!message.trim()) { setFormError('Write a task first.'); return }
    setSending(true); setFormError(null)
    try {
      const t = await api.startTeam(message.trim(), agent)
      setSelected(t.id); setMessage('')
    } catch (err) { setFormError((err as Error).message) }
    finally { setSending(false) }
  }

  return (
    <>
      <PageHead eyebrow="Team" title="Agents working together" sub="Give one agent a task. When another is better placed, it hands over, up to 5 hops. Nothing leaves the OS without you." />

      <Panel className="mb-5">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <label htmlFor="task" className="text-sm font-medium text-ink">Task</label>
            <textarea id="task" rows={3} value={message} onChange={e => { setMessage(e.target.value); setFormError(null) }}
              placeholder="Research Legrand Prep, then draft a short cover letter for their video editor role."
              aria-describedby="task-help task-error"
              className="w-full resize-y rounded-2xl border border-white bg-white/70 px-4 py-3 text-[15px] text-ink placeholder:text-ink-3 focus:outline-2 focus:outline-offset-2 focus:outline-navy" />
            <p id="task-help" className="text-xs text-ink-3">Starts with the agent you pick; it decides who goes next.</p>
            {formError && <p id="task-error" role="alert" className="text-sm text-bad">{formError}</p>}
          </div>
          <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
            <fieldset className="flex flex-wrap gap-2">
              <legend className="mb-2 text-sm font-medium text-ink">Start with</legend>
              {AGENTS.map(a => (
                <button type="button" key={a} onClick={() => setAgent(a)} aria-pressed={agent === a}
                  className={`min-h-11 cursor-pointer rounded-full border px-4 text-sm transition-colors duration-200 ${agent === a ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2 hover:bg-white'}`}>
                  <span className="flex items-center gap-2"><AgentLogo name={a} size={22} />{AGENT_META[a].label}</span>
                </button>
              ))}
            </fieldset>
            <ChromeButton type="submit" disabled={sending} icon={<PaperPlaneRight size={16} weight="bold" aria-hidden="true" />}>
              {sending ? 'Starting' : 'Run team'}
            </ChromeButton>
          </div>
        </form>
      </Panel>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[1fr_2fr]">
        <Panel>
          <h2 className="mb-3 text-sm font-medium text-ink-2">Runs</h2>
          {list.error && <ErrorLine msg={list.error} />}
          {!list.data ? <Skeleton className="h-40" /> : threads.length === 0 ? (
            <Empty title="No runs yet" hint="Your first team run will appear here." />
          ) : (
            <ul className="flex flex-col gap-1">
              {threads.map(t => (
                <li key={t.id}>
                  <button onClick={() => setSelected(t.id)}
                    className={`flex w-full cursor-pointer flex-col items-start gap-1 rounded-xl px-3 py-2.5 text-left transition-colors ${current?.id === t.id ? 'bg-white/80' : 'hover:bg-white/50'}`}>
                    <span className="line-clamp-2 text-sm text-ink">{t.message}</span>
                    <span className="font-mono text-xs text-ink-3">{t.status === 'running' ? `running · ${t.active}` : `${t.steps.length} hops`} · {timeAgo(t.created)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel>
          {current ? <ThreadView key={current.id} initial={current} /> : <Empty title="Pick a run" hint="Or start one above." />}
        </Panel>
      </div>
    </>
  )
}

function ThreadView({ initial }: { initial: Thread }) {
  const [t, setT] = useState(initial)
  useEffect(() => { setT(initial) }, [initial])

  return (
    <div className="flex flex-col gap-4">
      <div>
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-ink-3">Task</p>
        <p className="mt-1 text-[15px] leading-relaxed text-ink">{t.message}</p>
      </div>
      <ol className="flex flex-col">
        <AnimatePresence initial={false}>
          {t.steps.map((s, i) => (
            <motion.li key={i} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={spring} className="flex flex-col">
              <div className="rounded-2xl border border-white bg-white/60 p-4">
                <div className="mb-2 flex items-baseline justify-between gap-3">
                  <span className="flex items-center gap-2 text-sm font-medium text-navy"><AgentLogo name={s.agent} size={24} />{AGENT_META[s.agent]?.label ?? s.agent}</span>
                  <span className="font-mono text-xs text-ink-3">{timeAgo(s.timestamp)}</span>
                </div>
                {i > 0 && <p className="mb-2 text-xs text-ink-3">Asked to: {s.task}</p>}
                <Prose text={s.output} />
              </div>
              {s.handoff_to && (
                <div className="flex items-center gap-2 py-2 pl-4 text-xs text-ink-2">
                  <ArrowDown size={14} aria-hidden="true" /> handed to {AGENT_META[s.handoff_to]?.label ?? s.handoff_to}
                </div>
              )}
            </motion.li>
          ))}
        </AnimatePresence>
        {t.status === 'running' && (
          <li className="flex items-center gap-3 rounded-2xl border border-dashed border-steel/40 p-4 text-sm text-ink-2" aria-live="polite">
            <span className="shimmer h-2 w-16 rounded-full" aria-hidden="true" />
            {AGENT_META[t.active as AgentName]?.label ?? t.active} is working
          </li>
        )}
      </ol>
    </div>
  )
}
