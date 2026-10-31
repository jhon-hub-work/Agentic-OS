import { CaretLeft, CaretRight, Check, DotsThree, PaperPlaneRight, Plus, Waveform, X } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { AGENT_META, api, fmtTokens, timeAgo, type AgentName, type ModelChoice, type Session, type SessionItem, type SessionMsg } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Empty, ErrorLine, Field, ModelPick, PageHead, Panel, Prose, Skeleton, inputCls, spring, usePoll, useModelInfo } from '../ui'

export default function Sessions() {
  const list = usePoll(api.sessions, 3000)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  const [renaming, setRenaming] = useState<string | null>(null)
  const sessions = [...list.data?.sessions ?? []].sort((a, b) => b.created.localeCompare(a.created))
  const current = creating ? null : sessions.find(s => s.id === selected) ?? sessions.find(s => !s.archived)
  const sections = [...new Set(sessions.map(s => s.section).filter((x): x is string => !!x))].sort()
  const live = sessions.filter(s => !s.archived)
  const groups: [string, SessionItem[]][] = [
    ['Pinned', live.filter(s => s.pinned)],
    ...sections.map(sec => [sec, live.filter(s => !s.pinned && s.section === sec)] as [string, SessionItem[]]),
    [sections.length ? 'Other' : 'Sessions', live.filter(s => !s.pinned && !s.section)],
  ]
  const archived = sessions.filter(s => s.archived)
  const focused = selected !== null || creating || (list.data !== undefined && sessions.length === 0)  // phone shows this one panel

  function open(s: SessionItem) {
    setSelected(s.id); setCreating(false)
    if (s.read === false) api.patchSession(s.id, { read: true }).then(list.reload).catch(() => {})
  }
  async function act(fn: () => Promise<unknown>) {
    try { await fn(); list.reload() } catch (err) { alert((err as Error).message) }
  }
  const row = (s: SessionItem) => (
    <SessionRow key={s.id} s={s} active={current?.id === s.id} sections={sections} renaming={renaming === s.id}
      onOpen={() => open(s)} onRenameStart={() => setRenaming(s.id)}
      onRename={title => { setRenaming(null); if (title && title !== s.title) act(() => api.patchSession(s.id, { title })) }}
      onPatch={p => act(() => api.patchSession(s.id, p))}
      onFork={() => act(async () => { const f = await api.forkSession(s.id); setSelected(f.id) })}
      onDelete={() => { if (confirm(`Delete "${s.title}"? It moves to data/sessions/_deleted.`)) act(() => api.deleteSession(s.id)) }} />
  )

  return (
    <>
      <PageHead eyebrow="Sessions" title="Agents side by side"
        sub="Pick two or more agents. They answer at the same time, read each other, and build on it, round after round. Then Jharvis reads it all and gives you one answer."
        action={<ChromeButton onClick={() => setCreating(true)} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>New session</ChromeButton>} />
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[300px_1fr]">
        {/* on a phone: the list, or the open session with a way back, never both stacked */}
        <Panel className={focused ? 'hidden lg:block' : ''}>
          {list.error && <ErrorLine msg={list.error} />}
          {!list.data ? <Skeleton className="h-40" /> : sessions.length === 0 ? <Empty title="No sessions yet" hint="Start one to see agents work together live." /> : (
            <div className="flex flex-col gap-4">
              {groups.filter(([, items]) => items.length).map(([name, items]) => (
                <section key={name}>
                  <h2 className="mb-1.5 px-3 text-xs font-medium text-ink-3">{name}</h2>
                  <ul className="flex flex-col gap-1">{items.map(row)}</ul>
                </section>
              ))}
              {archived.length > 0 && (
                <section>
                  <button type="button" onClick={() => setShowArchived(v => !v)} aria-expanded={showArchived}
                    className="cursor-pointer px-3 text-xs font-medium text-ink-3 hover:text-ink">
                    Archived ({archived.length}) {showArchived ? '▾' : '▸'}
                  </button>
                  {showArchived && <ul className="mt-1.5 flex flex-col gap-1">{archived.map(row)}</ul>}
                </section>
              )}
            </div>
          )}
        </Panel>
        <Panel className={focused ? '' : 'hidden lg:block'}>
          <button type="button" onClick={() => { setSelected(null); setCreating(false) }}
            className="mb-3 flex min-h-11 cursor-pointer items-center gap-1.5 text-sm text-ink-2 hover:text-ink lg:hidden">
            <CaretLeft size={16} aria-hidden="true" />All sessions
          </button>
          {creating || !current ? <NewSession onCreated={id => { setSelected(id); setCreating(false) }} /> : <SessionDetail key={current.id} id={current.id} />}</Panel>
      </div>
    </>
  )
}

function NewSession({ onCreated }: { onCreated: (id: string) => void }) {
  const [agents, setAgents] = useState<AgentName[]>(['claude', 'opencode'])
  const [message, setMessage] = useState('')
  const [rounds, setRounds] = useState(2)
  const [overrides, setOverrides] = useState<Record<AgentName, ModelChoice>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (agents.length < 2) { setError('Pick at least two agents.'); return }
    if (!message.trim()) { setError('Write what they should work on.'); return }
    setBusy(true); setError(null)
    const picked = Object.fromEntries(agents.filter(a => overrides[a]).map(a => [a, overrides[a]]))  // only agents still chosen
    try { onCreated((await api.startSession(message.trim(), agents, rounds, picked)).id) }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <h2 className="text-lg font-medium tracking-tight text-ink">New session</h2>
      <AgentPicker multi legend="Agents (two or more)" value={agents} onChange={setAgents} />
      <SessionModels agents={agents} value={overrides} onChange={setOverrides} />
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

// The list carries no messages; the open session's full copy is polled here on its own
function SessionDetail({ id }: { id: string }) {
  const d = usePoll(() => api.session(id), 3000)
  if (!d.data) return d.error ? <ErrorLine msg={d.error} /> : <Skeleton className="h-40" />
  return <SessionView s={d.data} />
}

function SessionView({ s }: { s: Session }) {
  const [text, setText] = useState('')
  const [models, setModels] = useState(s.overrides ?? {})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // one run = your message(s), the agents' rounds, then Jharvis's verdict
  const runs: SessionMsg[][] = []
  for (const m of s.messages) {
    const last = runs[runs.length - 1]
    if (!last || (m.role === 'user' && last.some(x => x.role !== 'user'))) runs.push([m])
    else last.push(m)
  }

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
      {runs.map((run, ri) => {
        const agentMsgs = run.filter(m => m.role === 'agent')
        const verdict = run.find(m => m.role === 'verdict')
        const debate = (
          <div className="flex flex-col gap-3">
            {[...new Set(agentMsgs.map(m => m.round))].map(r => {
              const inRound = agentMsgs.filter(m => m.round === r)
              return (
                <div key={r} className={`grid grid-cols-1 gap-3 ${inRound.length > 1 ? 'md:grid-cols-2' : ''}`}>
                  {inRound.map((m, i) => (
                    <motion.div key={i} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={spring}
                      className="rounded-2xl border border-white bg-white/65 p-4">
                      <div className="mb-2 flex items-center gap-2 text-sm font-medium text-navy"><AgentLogo name={m.agent!} size={24} />{AGENT_META[m.agent!]?.label}<span className="ml-auto font-mono text-[11px] text-ink-3">{m.prompt_tokens ? `~${fmtTokens(m.prompt_tokens)} sent, ` : ''}round {m.round}</span></div>
                      <Prose text={m.content} />
                    </motion.div>
                  ))}
                </div>
              )
            })}
          </div>
        )
        return (
          <section key={ri} className="flex flex-col gap-3">
            {run.filter(m => m.role === 'user').map((m, i) => (
              <div key={`u${i}`} className="chrome self-end rounded-2xl px-4 py-3 text-sm leading-relaxed sm:max-w-[80%]">{m.content}</div>
            ))}
            {verdict && (
              <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={spring}
                className="rounded-2xl border border-white bg-white/85 p-5">
                <div className="mb-2 flex items-center gap-2 text-sm font-medium text-navy"><Waveform size={20} aria-hidden="true" />Jharvis's answer</div>
                <Prose text={verdict.content} />
                <Decision s={s} verdict={verdict} />
              </motion.div>
            )}
            {agentMsgs.length > 0 && (verdict
              ? <details><summary className="cursor-pointer text-sm text-ink-2 hover:text-ink">See how they got there ({agentMsgs.length} replies)</summary><div className="mt-3">{debate}</div></details>
              : debate)}
          </section>
        )
      })}
      {s.status === 'running' && s.judging && (
        <div className="flex items-center gap-3 rounded-2xl border border-dashed border-steel/40 p-4 text-sm text-ink-2" aria-live="polite">
          <Waveform size={20} aria-hidden="true" />Jharvis is reading the debate and deciding<span className="shimmer ml-auto h-2 w-14 rounded-full" />
        </div>
      )}
      {s.working.length > 0 && (
        <div className={`grid grid-cols-1 gap-3 ${s.working.length > 1 ? 'md:grid-cols-2' : ''}`} aria-live="polite">
          {s.working.map(a => (
            <div key={a} className="flex items-center gap-3 rounded-2xl border border-dashed border-steel/40 p-4 text-sm text-ink-2">
              <AgentLogo name={a} size={24} />{AGENT_META[a].label} is answering<span className="shimmer ml-auto h-2 w-14 rounded-full" />
            </div>
          ))}
        </div>
      )}
      <SessionModels agents={s.agents} value={models} onChange={setModels} disabled={s.status === 'running'}
        onDone={() => api.patchSession(s.id, { overrides: models }).then(r => setModels(r.overrides ?? {})).catch(err => setError((err as Error).message))} />
      {s.status === 'awaiting_user' && !!s.questions?.length && (
        <div className="rounded-2xl border border-steel/40 p-4 text-sm text-ink-2" aria-live="polite">
          <p className="font-medium text-ink">Waiting on you</p>
          <ul className="mt-2 list-disc pl-5">{s.questions.map(q => <li key={q}>{q}</li>)}</ul>
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

/** Per-agent model + effort for a session. Collapsed it reads "Models: defaults. Change"; onDone saves (an open session). */
function SessionModels({ agents, value, onChange, disabled, onDone }: {
  agents: AgentName[]; value: Record<AgentName, ModelChoice>; onChange: (v: Record<AgentName, ModelChoice>) => void; disabled?: boolean; onDone?: () => void
}) {
  const [open, setOpen] = useState(false)
  const set = agents.filter(a => value[a]?.model || value[a]?.effort)
  const summary = set.length ? set.map(a => `${AGENT_META[a]?.label}: ${[value[a].model, value[a].effort && `${value[a].effort} effort`].filter(Boolean).join(', ')}`).join('; ') : 'defaults'
  return (
    <div className="flex flex-col gap-2">
      <p className="text-sm text-ink-2">Models: {summary}. <button type="button" disabled={disabled} aria-expanded={open}
        onClick={() => { if (open) onDone?.(); setOpen(!open) }}
        className="cursor-pointer text-navy underline-offset-2 hover:underline disabled:cursor-default disabled:opacity-50">{open ? (onDone ? 'save' : 'done') : 'change'}</button></p>
      {open && (
        <ul className="flex flex-col gap-2">
          {agents.map(a => <SessionModelRow key={a} agent={a} value={value[a] ?? {}} disabled={disabled} onChange={v => onChange({ ...value, [a]: v })} />)}
        </ul>
      )}
    </div>
  )
}

function SessionModelRow({ agent, value, onChange, disabled }: { agent: AgentName; value: ModelChoice; onChange: (v: ModelChoice) => void; disabled?: boolean }) {
  const info = useModelInfo(agent)
  if (info && info.models === null && !info.efforts.length) return null  // an added agent without model/effort flags
  return (
    <li className="flex flex-wrap items-center gap-2">
      <AgentLogo name={agent} size={24} /><span className="w-24 text-sm text-ink">{AGENT_META[agent]?.label}</span>
      <ModelPick agent={agent} info={info} value={value} onChange={onChange} disabled={disabled} base={{ model: info?.current ?? '', effort: info?.current_effort ?? '' }} />
    </li>
  )
}

type RowProps = {
  s: SessionItem; active: boolean; sections: string[]; renaming: boolean
  onOpen: () => void; onRenameStart: () => void; onRename: (title: string) => void
  onPatch: (p: Partial<Pick<Session, 'pinned' | 'read' | 'section' | 'archived'>>) => void; onFork: () => void; onDelete: () => void
}

// One session in the list, with a "…" menu like the Claude app's (keys work while the menu is open)
function SessionRow({ s, active, sections, renaming, onOpen, onRenameStart, onRename, onPatch, onFork, onDelete }: RowProps) {
  const [menu, setMenu] = useState(false)
  const [sub, setSub] = useState(false)
  const ref = useRef<HTMLLIElement>(null)
  const unread = s.read === false
  const items: { label: string; key: string; run: () => void; danger?: boolean }[] = [
    { label: s.pinned ? 'Unpin' : 'Pin', key: 'P', run: () => onPatch({ pinned: !s.pinned }) },
    { label: unread ? 'Mark as read' : 'Mark as unread', key: 'U', run: () => onPatch({ read: unread }) },
    { label: 'Rename', key: 'R', run: onRenameStart },
    { label: 'Fork', key: 'F', run: onFork },
    { label: s.archived ? 'Unarchive' : 'Archive', key: 'A', run: () => onPatch({ archived: !s.archived }) },
    { label: 'Delete', key: 'D', run: onDelete, danger: true },
  ]
  const close = () => { setMenu(false); setSub(false) }
  useEffect(() => {
    if (!menu) return
    const click = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) close() }
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') return close()
      const it = items.find(i => i.key === e.key.toUpperCase())
      if (it) { e.preventDefault(); close(); it.run() }
    }
    document.addEventListener('mousedown', click); document.addEventListener('keydown', key)
    return () => { document.removeEventListener('mousedown', click); document.removeEventListener('keydown', key) }
  })

  const btn = 'flex w-full cursor-pointer items-center justify-between gap-6 rounded-lg px-3 py-1.5 text-left text-sm hover:bg-white'
  return (
    <li ref={ref} className="group relative">
      {renaming ? (
        <input autoFocus defaultValue={s.title} aria-label="Session name" className={`${inputCls} py-2 text-sm`}
          onBlur={e => onRename(e.currentTarget.value.trim())}
          onKeyDown={e => { if (e.key === 'Enter') e.currentTarget.blur(); if (e.key === 'Escape') onRename('') }} />
      ) : (
        <button onClick={onOpen}
          className={`flex w-full cursor-pointer flex-col gap-1.5 rounded-xl px-3 py-2.5 pr-10 text-left transition-colors ${active ? 'bg-white/80' : 'hover:bg-white/50'}`}>
          <span className={`line-clamp-2 text-sm ${unread ? 'font-medium text-navy' : 'text-ink'}`}>
            {unread && <span className="mr-1.5 inline-block h-2 w-2 rounded-full bg-navy align-middle" aria-label="Unread" />}{s.title}
          </span>
          <span className="flex items-center gap-1.5">
            {s.agents.map(a => <AgentLogo key={a} name={a} size={18} />)}
            <span className="ml-1 font-mono text-xs text-ink-3">{s.status === 'running' ? 'live' : timeAgo(s.created)}</span>
          </span>
        </button>
      )}
      {!renaming && (
        <button type="button" aria-label={`More for ${s.title}`} aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu(m => !m)}
          className={`absolute right-2 top-2 cursor-pointer rounded-lg p-1.5 text-ink-3 hover:bg-white hover:text-ink ${menu ? 'opacity-100' : 'opacity-0 focus:opacity-100 group-hover:opacity-100'}`}>
          <DotsThree size={18} weight="bold" aria-hidden="true" />
        </button>
      )}
      {menu && (
        <div role="menu" className="absolute right-2 top-10 z-30 w-56 rounded-xl border border-white bg-ice p-1.5 shadow-lg">
          {items.slice(0, 4).map(i => (
            <button key={i.key} role="menuitem" className={btn} onClick={() => { close(); i.run() }}>{i.label}<span className="font-mono text-xs text-ink-3">{i.key}</span></button>
          ))}
          <div className="my-1 border-t border-ink-3/15" />
          <div className="relative">
            <button role="menuitem" aria-haspopup="menu" aria-expanded={sub} className={btn} onClick={() => setSub(v => !v)}>Move to section<CaretRight size={14} className="text-ink-3" aria-hidden="true" /></button>
            {sub && (
              <div role="menu" className="mt-1 flex flex-col rounded-lg bg-white/70 p-1">
                {sections.filter(x => x !== s.section).map(x => (
                  <button key={x} role="menuitem" className={btn} onClick={() => { close(); onPatch({ section: x }) }}>{x}</button>
                ))}
                <button role="menuitem" className={btn} onClick={() => { const x = prompt('New section name')?.trim(); close(); if (x) onPatch({ section: x }) }}>New section…</button>
                {s.section && <button role="menuitem" className={btn} onClick={() => { close(); onPatch({ section: '' }) }}>Remove from "{s.section}"</button>}
              </div>
            )}
          </div>
          <div className="my-1 border-t border-ink-3/15" />
          {items.slice(4).map(i => (
            <button key={i.key} role="menuitem" className={`${btn} ${i.danger ? 'text-bad' : ''}`} onClick={() => { close(); i.run() }}>{i.label}<span className="font-mono text-xs text-ink-3">{i.key}</span></button>
          ))}
        </div>
      )}
    </li>
  )
}

// Approve: a ticket that gets planned, built by Claude and checked by another agent. Deny: more rounds, then a new decision.
function Decision({ s, verdict }: { s: Session; verdict: SessionMsg }) {
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const d = verdict.decision
  if (d) return <p className="mt-3 text-sm text-ink-2">{d.approved ? `You approved it. Building it as ${d.ticket} (see Tickets).` : 'You denied it. They went another round.'}</p>
  if (s.status !== 'idle' || s.messages[s.messages.length - 1] !== verdict) return null
  const btn = 'inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:cursor-not-allowed disabled:opacity-50'
  async function decide(approve: boolean) {
    setBusy(true); setErr(null)
    try { await api.sessionDecision(s.id, approve, note.trim()) }
    catch (e) { setErr((e as Error).message) }
    finally { setBusy(false) }
  }
  return (
    <div className="mt-4 flex flex-col gap-2 border-t border-white pt-4">
      <input value={note} onChange={e => setNote(e.target.value)} aria-label="Why you deny it (optional)"
        placeholder="Denying? Say what's wrong (optional, else they ask you)" className={inputCls} />
      <div className="flex flex-wrap gap-2">
        <button onClick={() => decide(true)} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />Approve and build</button>
        <button onClick={() => decide(false)} disabled={busy} className={btn}><X size={14} aria-hidden="true" />Deny</button>
      </div>
      {err && <ErrorLine msg={err} />}
    </div>
  )
}
