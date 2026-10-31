import { PaperPlaneRight, PencilSimple, Plus, Trash } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { AGENT_META, api, fmtTokens, timeAgo, type AgentName, type ChatContext, type ChatItem, type ChatMsg, type ModelChoice, type Skill, type ToolStep } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Empty, ModelPick, PageHead, Panel, Prose, inputCls, spring, useModelInfo } from '../ui'

// Smart picks the agent per message (server smart_pick): code -> OpenCode, web -> Hermes, hard -> Claude under the guard
const SMART = { smart: { label: 'Smart', role: 'Picks the agent for each message: code to OpenCode, web to Hermes, hard thinking to Claude (only under your Claude limit).' } }

export default function Chats() {
  const [agent, setAgent] = useState<AgentName>('claude')
  const meta_ = agent === 'smart' ? SMART.smart : AGENT_META[agent]
  const [msgs, setMsgs] = useState<ChatMsg[] | null>(null)
  const [chats, setChats] = useState<ChatItem[]>([])
  const [find, setFind] = useState('')  // searches this agent's chat names and messages on the server
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [live, setLive] = useState<{ text: string; steps: ToolStep[] } | null>(null)
  const end = useRef<HTMLDivElement>(null)
  const info = useModelInfo(agent)
  const [pick, setPick] = useState<ModelChoice>({})  // this chat's model + effort; {} = the agent's default
  const [skills, setSkills] = useState<Skill[]>([])
  useEffect(() => { api.skills().then(setSkills).catch(() => {}) }, [])
  const [meta, setMeta] = useState<{ context: ChatContext | null; commands: string[]; live: boolean }>({ context: null, commands: [], live: false })
  // typing "/" lists the agent's own commands (Claude Code's, like /compact) and the OS skills; "/name details" runs it
  const agentCmds = [...new Set(['clear', ...meta.commands])]
  const menu = [...agentCmds.map(name => ({ name, hint: 'command', os: false })),
    ...skills.filter(s => !agentCmds.includes(s.name)).map(s => ({ name: s.name, hint: /description:\s*(.+)/.exec(s.description)?.[1] ?? s.description, os: true }))]
  const slash = /^\/([\w:-]*)$/.exec(text)
  const q = slash?.[1].toLowerCase() ?? ''
  const matches = slash ? menu.filter(c => c.name.startsWith(q) || c.name.split(':')[1]?.startsWith(q)).slice(0, 8) : []

  useEffect(() => {
    let alive = true  // a slow answer for the agent picked before must not show in this one's thread
    setMsgs(null); setMeta({ context: null, commands: [], live: false }); setPick({})
    api.chatHistory(agent).then(r => alive && setMsgs(r.messages)).catch(e => alive && setError(e.message))
    api.chatMeta(agent).then(r => alive && setMeta(r)).catch(() => {})
    return () => { alive = false }
  }, [agent])
  useEffect(() => {
    let alive = true
    const t = setTimeout(() => api.chatList(agent, find).then(r => alive && setChats(r.chats)).catch(() => {}), find ? 250 : 0)
    return () => { alive = false; clearTimeout(t) }
  }, [agent, find])
  const reloadChats = () => api.chatList(agent, find).then(r => setChats(r.chats)).catch(() => {})

  async function renameChat(c: ChatItem) {
    const name = prompt('Name this chat (empty goes back to the first message)', c.title)
    if (name === null) return
    try { await api.chatRename(agent, c.id, name); await reloadChats() } catch (err) { setError((err as Error).message) }
  }

  async function deleteChat(c: ChatItem) {
    if (!confirm(`Delete this chat?${c.current ? ' A new chat starts.' : ''} It moves to data/chat-deleted.jsonl.`)) return
    setBusy(true); setError(null)
    try {
      await api.chatDelete(agent, c.id)
      if (c.current) { setMsgs([]); setMeta(x => ({ ...x, context: null })) }
      await reloadChats()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  async function openChat(id: string) {
    setBusy(true); setError(null)
    try {
      await api.chatOpen(agent, id)
      setMsgs((await api.chatHistory(agent)).messages)
      setMeta(x => ({ ...x, context: null }))
      await reloadChats()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [msgs, busy, live])

  async function send(e: React.FormEvent) {
    e.preventDefault()
    const m = text.trim()
    if (busy) return  // Enter still submits while a reply runs; two runs would share one session
    if (!m) { setError('Type a message first.'); return }
    setText('')
    await run(m)
  }

  async function run(m: string) {
    setBusy(true); setError(null)
    if (/^\/(clear|new)$/.test(m)) {  // every agent: file this chat away in the list, start an empty one
      try { await api.chat(agent, m, pick); setMsgs([]); setMeta(x => ({ ...x, context: null })); await reloadChats() }
      catch (err) { setError((err as Error).message) }
      finally { setBusy(false) }
      return
    }
    setMsgs(prev => [...(prev ?? []), { id: 'pending', role: 'user', agent, content: m, timestamp: new Date().toISOString() }])
    try {
      const osSkill = menu.find(c => c.os && c.name === /^\/([\w-]+)/.exec(m)?.[1])
      if (meta.live && !osSkill) {
        setLive({ text: '', steps: [] })
        await api.chatStream(agent, m, e => {
          if (e.type === 'context') setMeta(x => ({ ...x, context: e.used ? { used: e.used, window: e.window ?? x.context?.window ?? null } : null }))
          else if (e.type === 'text') setLive(l => l && { ...l, text: l.text + e.text })
          else if (e.type === 'tool') setLive(l => l && { ...l, steps: [...l.steps.filter(s => s.toolCallId !== e.toolCallId), { ...l.steps.find(s => s.toolCallId === e.toolCallId), ...e }] })
          else if (e.type === 'error') setError(e.error)
          else if (e.type === 'done') setMsgs(prev => [...(prev ?? []), e.message])
        }, pick)
        if (agent === 'claude') api.chatMeta(agent).then(r => setMeta(x => ({ ...x, commands: r.commands }))).catch(() => {})
      } else {
        const r = await api.chat(agent, m, pick)
        setMsgs(prev => [...(prev ?? []), r.response])
      }
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false); setLive(null); reloadChats() }
  }

  return (
    <>
      <PageHead eyebrow="Chats" title="Talk to one agent" sub="Private chats with each agent. New chat starts fresh; earlier chats stay in the list, click one to carry on. Claude Code and Hermes show their steps live." />
      <Panel className="mb-5" inner="flex flex-col gap-4">
        <AgentPicker legend="Agent" value={[agent]} disabled={busy} onChange={v => v[0] && setAgent(v[0])} extra={SMART} />
        {info && (info.models !== null || info.efforts.length > 0) && (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-ink-2">Model for this chat</span>
            <ModelPick agent={agent} info={info} value={pick} onChange={setPick} disabled={busy} base={{ model: info.current, effort: info.current_effort }} />
          </div>
        )}
      </Panel>
      <div className="grid gap-5 lg:grid-cols-[240px_minmax(0,1fr)]">
      <Panel inner="flex flex-col gap-3">
        <button type="button" disabled={busy} onClick={() => run('/clear')}
          className="flex cursor-pointer items-center justify-center gap-1.5 rounded-xl border border-white bg-white/60 px-3 py-2 text-sm text-ink hover:bg-white disabled:opacity-50">
          <Plus size={14} weight="bold" aria-hidden="true" />New chat
        </button>
        <label htmlFor="chat-find" className="sr-only">Search chats</label>
        <input id="chat-find" type="search" value={find} onChange={e => setFind(e.target.value)} placeholder="Search chats" className={inputCls} />
        <nav aria-label={`Chats with ${meta_.label}`} className="max-h-[30dvh] overflow-y-auto lg:max-h-[60dvh]">
          <ul className="flex flex-col gap-1">
            {chats.map(c => {
              const title = c.title || (c.id.startsWith('old-') ? 'Earlier chats' : 'New chat')
              return (
              <li key={c.id} className={`flex items-start rounded-xl ${c.current ? 'bg-white/80' : 'hover:bg-white/60'}`}>
                <button type="button" disabled={busy || c.current} onClick={() => openChat(c.id)} aria-current={c.current || undefined}
                  className={`flex min-w-0 flex-1 cursor-pointer flex-col items-start rounded-xl py-2 pl-3 text-left disabled:cursor-default ${c.current ? '' : 'disabled:opacity-50'}`}>
                  <span className="w-full truncate text-sm text-ink">{title}</span>
                  {c.snippet && <span className="line-clamp-2 w-full text-xs text-ink-2">{c.snippet}</span>}
                  <span className="font-mono text-[11px] text-ink-3">{c.updated ? `${timeAgo(c.updated)}, ${c.count} msg` : 'empty'}</span>
                </button>
                <button type="button" disabled={busy} onClick={() => renameChat(c)} aria-label={`Rename ${title}`} title="Rename"
                  className="mt-1 cursor-pointer rounded-lg p-1.5 text-ink-3 hover:bg-white hover:text-ink disabled:opacity-50">
                  <PencilSimple size={14} aria-hidden="true" />
                </button>
                <button type="button" disabled={busy} onClick={() => deleteChat(c)} aria-label={`Delete ${title}`} title="Delete"
                  className="mt-1 mr-1 cursor-pointer rounded-lg p-1.5 text-ink-3 hover:bg-white hover:text-bad disabled:opacity-50">
                  <Trash size={14} aria-hidden="true" />
                </button>
              </li>
            )})}
          </ul>
          {find && chats.length === 0 && <p className="px-3 py-2 text-sm text-ink-3">No chats match.</p>}
        </nav>
      </Panel>
      <Panel inner="flex min-h-[55dvh] flex-col">
        {meta.live && (
          <div className="mb-3 flex items-center gap-3 border-b border-white/80 pb-3">
            {meta.context?.window ? (
              <>
                <div role="meter" aria-label="Context used" aria-valuemin={0} aria-valuemax={meta.context.window} aria-valuenow={meta.context.used}
                  className="h-2 flex-1 overflow-hidden rounded-full bg-white/70">
                  <div className={`h-full rounded-full ${meta.context.used / meta.context.window > 0.8 ? 'bg-bad' : 'bg-navy'}`}
                    style={{ width: `${Math.max(1, Math.min(100, (meta.context.used / meta.context.window) * 100))}%` }} />
                </div>
                <span className="font-mono text-[11px] text-ink-3">{fmtTokens(meta.context.used)} / {fmtTokens(meta.context.window)} context, {Math.round((meta.context.used / meta.context.window) * 100)}%</span>
              </>
            ) : (
              <span className="flex-1 font-mono text-[11px] text-ink-3">{meta.context ? `~${fmtTokens(meta.context.used)} tokens last turn` : 'Fresh chat, no context used yet'}</span>
            )}
          </div>
        )}
        <div className="flex-1 space-y-4 overflow-y-auto pb-4" aria-live="polite">
          {msgs === null ? <div className="shimmer h-24 rounded-2xl" /> : msgs.length === 0 ? (
            <Empty title={`No messages with ${meta_.label} yet`} hint={meta_.role} />
          ) : (
            <AnimatePresence initial={false}>
              {(msgs as (ChatMsg & { by?: string; why?: string })[]).map((m, i) => (
                <motion.div key={m.id + i} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={spring}
                  className={`flex gap-3 ${m.role === 'user' ? 'justify-end' : ''}`}>
                  {m.role !== 'user' && <AgentLogo name={m.by ?? m.agent} size={30} />}
                  <div className={`max-w-[85%] rounded-2xl px-4 py-3 ${m.role === 'user' ? 'chrome' : 'border border-white bg-white/65'}`}>
                    {m.role === 'user' ? <p className="whitespace-pre-wrap text-sm leading-relaxed">{m.content}</p> : <Prose text={m.content} />}
                    <p className={`mt-1.5 font-mono text-[11px] ${m.role === 'user' ? 'text-on-chrome/60' : 'text-ink-3'}`}>
                      {timeAgo(m.timestamp)}{m.by && ` · ${AGENT_META[m.by]?.label ?? m.by}, ${m.why}`}</p>
                  </div>
                </motion.div>
              ))}
            </AnimatePresence>
          )}
          {busy && (live && (live.text || live.steps.length) ? (
            <div className="flex gap-3">
              <AgentLogo name={agent} size={30} />
              <div className="max-w-[85%] rounded-2xl border border-white bg-white/65 px-4 py-3">
                {live.steps.length > 0 && (
                  <ul aria-label="Steps" className="mb-2 flex flex-wrap gap-1.5">
                    {live.steps.map(s => (
                      <li key={s.toolCallId} className={`rounded-full border border-white px-2.5 py-1 font-mono text-[11px] ${s.status === 'running' ? 'shimmer text-ink' : 'bg-white/70 text-ink-3'}`}>
                        {s.emoji} {s.tool}{s.label ? `: ${s.label}` : ''}{s.status === 'completed' ? ' ✓' : ''}
                      </li>
                    ))}
                  </ul>
                )}
                {live.text && <Prose text={live.text} />}
              </div>
            </div>
          ) : <div className="flex items-center gap-3"><AgentLogo name={agent} size={30} /><span className="shimmer h-10 w-40 rounded-2xl" aria-label="Thinking" /></div>)}
          <div ref={end} />
        </div>
        {matches.length > 0 && (
          <ul role="listbox" aria-label="Commands and skills" className="mb-2 flex flex-col gap-1 border-t border-white/80 pt-3">
            {matches.map(s => (
              <li key={s.name}>
                <button type="button" onClick={() => { setText(`/${s.name} `); document.getElementById('chat-input')?.focus() }}
                  className="flex w-full cursor-pointer items-baseline gap-3 rounded-xl px-3 py-2 text-left hover:bg-white/70">
                  <span className="font-mono text-sm text-ink">/{s.name}</span>
                  <span className="truncate text-xs text-ink-3">{s.os ? s.hint : `${meta_.label} ${agent === 'claude' ? 'command' : 'skill'}`}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <form onSubmit={send} className="flex flex-col gap-2 border-t border-white/80 pt-4 sm:flex-row sm:items-end">
          <label htmlFor="chat-input" className="sr-only">Message</label>
          <textarea id="chat-input" rows={2} value={text} onChange={e => { setText(e.target.value); setError(null) }}
            onKeyDown={e => {
              if (e.key === 'Tab' && matches[0]) { e.preventDefault(); setText(`/${matches[0].name} `); return }
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }}
            placeholder={`Message ${meta_.label}, or / for a skill`} className={`${inputCls} resize-none`} />
          <ChromeButton type="submit" disabled={busy} icon={<PaperPlaneRight size={16} weight="bold" aria-hidden="true" />}>Send</ChromeButton>
        </form>
        {error && <p role="alert" className="mt-2 text-sm text-bad">{error}</p>}
      </Panel>
      </div>
    </>
  )
}
