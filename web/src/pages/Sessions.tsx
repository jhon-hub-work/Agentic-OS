import { PaperPlaneRight, Plus } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useState } from 'react'
import { AGENT_META, api, timeAgo, type AgentName, type Session } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Empty, ErrorLine, Field, PageHead, Panel, Prose, Skeleton, inputCls, spring, usePoll } from '../ui'

export default function Sessions() {
  const list = usePoll(api.sessions, 3000)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const sessions = list.data?.sessions ?? []
  const current = creating ? null : sessions.find(s => s.id === selected) ?? sessions[0]

  return (
    <>
      <PageHead eyebrow="Sessions" title="Agents side by side"
        sub="Pick two or more agents. They answer at the same time, read each other, and build on it, round after round."
        action={<ChromeButton onClick={() => setCreating(true)} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>New session</ChromeButton>} />
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[1fr_2.2fr]">
        <Panel>
          <h2 className="mb-3 text-sm font-medium text-ink-2">Sessions</h2>
          {list.error && <ErrorLine msg={list.error} />}
          {!list.data ? <Skeleton className="h-40" /> : sessions.length === 0 ? <Empty title="No sessions yet" hint="Start one to see agents work together live." /> : (
            <ul className="flex flex-col gap-1">
              {sessions.map(s => (
                <li key={s.id}>
                  <button onClick={() => { setSelected(s.id); setCreating(false) }}
                    className={`flex w-full cursor-pointer flex-col gap-1.5 rounded-xl px-3 py-2.5 text-left transition-colors ${current?.id === s.id ? 'bg-white/80' : 'hover:bg-white/50'}`}>
                    <span className="line-clamp-2 text-sm text-ink">{s.title}</span>
                    <span className="flex items-center gap-1.5">
                      {s.agents.map(a => <AgentLogo key={a} name={a} size={18} />)}
                      <span className="ml-1 font-mono text-xs text-ink-3">{s.status === 'running' ? 'live' : timeAgo(s.created)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel>{creating || !current ? <NewSession onCreated={id => { setSelected(id); setCreating(false) }} /> : <SessionView s={current} />}</Panel>
      </div>
    </>
  )
}

function NewSession({ onCreated }: { onCreated: (id: string) => void }) {
  const [agents, setAgents] = useState<AgentName[]>(['claude', 'opencode'])
  const [message, setMessage] = useState('')
  const [rounds, setRounds] = useState(2)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (agents.length < 2) { setError('Pick at least two agents.'); return }
    if (!message.trim()) { setError('Write what they should work on.'); return }
    setBusy(true); setError(null)
    try { onCreated((await api.startSession(message.trim(), agents, rounds)).id) }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <h2 className="text-lg font-medium tracking-tight text-ink">New session</h2>
      <AgentPicker multi legend="Agents (two or more)" value={agents} onChange={setAgents} />
      <Field id="s-msg" label="What should they work on?" help="Each agent sees what the others said and builds on it.">
        <textarea id="s-msg" rows={4} value={message} onChange={e => setMessage(e.target.value)} className={inputCls}
          placeholder="Plan a 4-week posting calendar for the YouTube stickman channel. Claude drafts, OpenCode checks the numbers." />
      </Field>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium text-ink">Rounds</legend>
        <div className="flex gap-2">
          {[1, 2, 3, 4].map(n => (
            <button type="button" key={n} onClick={() => setRounds(n)} aria-pressed={rounds === n}
              className={`min-h-11 min-w-11 cursor-pointer rounded-full border font-mono text-sm ${rounds === n ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2'}`}>{n}</button>
          ))}
        </div>
        <p className="text-xs text-ink-3">One round = every agent answers once. They stop early when all say they're done. A reply from you runs the same number of rounds again.</p>
      </fieldset>
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
      <div><ChromeButton type="submit" disabled={busy} icon={<PaperPlaneRight size={16} weight="bold" aria-hidden="true" />}>{busy ? 'Starting' : 'Start session'}</ChromeButton></div>
    </form>
  )
}

function SessionView({ s }: { s: Session }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const rounds = [...new Set(s.messages.map(m => m.round))]

  async function reply(e: React.FormEvent) {
    e.preventDefault()
    if (!text.trim()) return
    setBusy(true); setError(null)
    try { await api.sessionMessage(s.id, text.trim()); setText('') }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return (
    <div className="flex flex-col gap-6">
      {rounds.map(r => {
        const items = s.messages.filter(m => m.round === r)
        const user = items.filter(m => m.role === 'user')
        const agentMsgs = items.filter(m => m.role === 'agent')
        return (
          <section key={r} className="flex flex-col gap-3">
            {user.map((m, i) => (
              <div key={`u${i}`} className="chrome self-end rounded-2xl px-4 py-3 text-sm leading-relaxed sm:max-w-[80%]">{m.content}</div>
            ))}
            {agentMsgs.length > 0 && (
              <div className={`grid grid-cols-1 gap-3 ${agentMsgs.length > 1 ? 'md:grid-cols-2' : ''}`}>
                {agentMsgs.map((m, i) => (
                  <motion.div key={i} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={spring}
                    className="rounded-2xl border border-white bg-white/65 p-4">
                    <div className="mb-2 flex items-center gap-2 text-sm font-medium text-navy"><AgentLogo name={m.agent!} size={24} />{AGENT_META[m.agent!]?.label}<span className="ml-auto font-mono text-[11px] text-ink-3">round {m.round}</span></div>
                    <Prose text={m.content} />
                  </motion.div>
                ))}
              </div>
            )}
          </section>
        )
      })}
      {s.working.length > 0 && (
        <div className={`grid grid-cols-1 gap-3 ${s.working.length > 1 ? 'md:grid-cols-2' : ''}`} aria-live="polite">
          {s.working.map(a => (
            <div key={a} className="flex items-center gap-3 rounded-2xl border border-dashed border-steel/40 p-4 text-sm text-ink-2">
              <AgentLogo name={a} size={24} />{AGENT_META[a].label} is answering<span className="shimmer ml-auto h-2 w-14 rounded-full" />
            </div>
          ))}
        </div>
      )}
      <form onSubmit={reply} className="flex flex-col gap-2 border-t border-white/80 pt-4 sm:flex-row sm:items-end">
        <label htmlFor="s-reply" className="sr-only">Reply to the session</label>
        <textarea id="s-reply" rows={2} value={text} onChange={e => setText(e.target.value)} disabled={s.status === 'running'}
          placeholder={s.status === 'running' ? 'Wait for this round to finish' : 'Steer them: add info, correct, or ask for more'} className={`${inputCls} resize-none`} />
        <ChromeButton type="submit" disabled={busy || s.status === 'running'} icon={<PaperPlaneRight size={16} weight="bold" aria-hidden="true" />}>Send</ChromeButton>
      </form>
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
    </div>
  )
}
