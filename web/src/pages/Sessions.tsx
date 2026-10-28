import { DotsThree, PaperPlaneRight, Plus } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { AGENT_META, api, fmtTokens, timeAgo, type AgentName, type Session } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Empty, ErrorLine, Field, PageHead, Panel, Prose, Skeleton, inputCls, spring, usePoll } from '../ui'

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
  const groups: [string, Session[]][] = [
    ['Pinned', live.filter(s => s.pinned)],
    ...sections.map(sec => [sec, live.filter(s => !s.pinned && s.section === sec)] as [string, Session[]]),
    [sections.length ? 'Other' : 'Sessions', live.filter(s => !s.pinned && !s.section)],
  ]
  const archived = sessions.filter(s => s.archived)

  function open(s: Session) {
    setSelected(s.id); setCreating(false)
    if (s.read === false) api.patchSession(s.id, { read: true }).then(list.reload).catch(() => {})
  }
  async function act(fn: () => Promise<unknown>) {
    try { await fn(); list.reload() } catch (err) { alert((err as Error).message) }
  }
  const row = (s: Session) => (
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
        sub="Pick two or more agents. They answer at the same time, read each other, and build on it, round after round."
        action={<ChromeButton onClick={() => setCreating(true)} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>New session</ChromeButton>} />
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[300px_1fr]">
        <Panel>
          {list.error && <ErrorLine msg={list.error} />}
          {!list.data ? <Skeleton className="h-40" /> : sessions.length === 0 ? <Empty title="No sessions yet" hint="Start one to see agents work together live." /> : (
            <div className="flex flex-col gap-4">
              {groups.filter(([, items]) => items.length).map(([name, items]) => (
                <section key={name}>
                  <h2 className="mb-1.5 px-3 text-xs font-medium uppercase tracking-wide text-ink-3">{name}</h2>
                  <ul className="flex flex-col gap-1">{items.map(row)}</ul>
                </section>
              ))}
              {archived.length > 0 && (
                <section>
                  <button type="button" onClick={() => setShowArchived(v => !v)} aria-expanded={showArchived}
                    className="cursor-pointer px-3 text-xs font-medium uppercase tracking-wide text-ink-3 hover:text-ink">
                    Archived ({archived.length}) {showArchived ? '▾' : '▸'}
                  </button>
                  {showArchived && <ul className="mt-1.5 flex flex-col gap-1">{archived.map(row)}</ul>}
                </section>
              )}
            </div>
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
                    <div className="mb-2 flex items-center gap-2 text-sm font-medium text-navy"><AgentLogo name={m.agent!} size={24} />{AGENT_META[m.agent!]?.label}<span className="ml-auto font-mono text-[11px] text-ink-3">{m.prompt_tokens ? `~${fmtTokens(m.prompt_tokens)} sent · ` : ''}round {m.round}</span></div>
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

type RowProps = {
  s: Session; active: boolean; sections: string[]; renaming: boolean
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
            <button role="menuitem" aria-haspopup="menu" aria-expanded={sub} className={btn} onClick={() => setSub(v => !v)}>Move to section<span className="text-ink-3">›</span></button>
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
