import { PaperPlaneRight } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { AGENT_META, api, timeAgo, type AgentName, type ChatMsg } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Empty, PageHead, Panel, Prose, inputCls, spring } from '../ui'

export default function Chats() {
  const [agent, setAgent] = useState<AgentName>('claude')
  const [msgs, setMsgs] = useState<ChatMsg[] | null>(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setMsgs(null)
    api.chatHistory(agent).then(r => setMsgs(r.messages)).catch(e => setError(e.message))
  }, [agent])
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [msgs, busy])

  async function send(e: React.FormEvent) {
    e.preventDefault()
    const m = text.trim()
    if (!m) { setError('Type a message first.'); return }
    setBusy(true); setError(null); setText('')
    setMsgs(prev => [...(prev ?? []), { id: 'pending', role: 'user', agent, content: m, timestamp: new Date().toISOString() }])
    try {
      const r = await api.chat(agent, m)
      setMsgs(prev => [...(prev ?? []), r.response])
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return (
    <>
      <PageHead eyebrow="Chats" title="Talk to one agent" sub="A private thread with each agent. It remembers the last few turns of this chat." />
      <Panel className="mb-5"><AgentPicker legend="Agent" value={[agent]} onChange={v => v[0] && setAgent(v[0])} /></Panel>
      <Panel inner="flex min-h-[55dvh] flex-col">
        <div className="flex-1 space-y-4 overflow-y-auto pb-4" aria-live="polite">
          {msgs === null ? <div className="shimmer h-24 rounded-2xl" /> : msgs.length === 0 ? (
            <Empty title={`No messages with ${AGENT_META[agent].label} yet`} hint={AGENT_META[agent].role} />
          ) : (
            <AnimatePresence initial={false}>
              {msgs.map((m, i) => (
                <motion.div key={m.id + i} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={spring}
                  className={`flex gap-3 ${m.role === 'user' ? 'justify-end' : ''}`}>
                  {m.role !== 'user' && <AgentLogo name={m.agent} size={30} />}
                  <div className={`max-w-[85%] rounded-2xl px-4 py-3 ${m.role === 'user' ? 'chrome' : 'border border-white bg-white/65'}`}>
                    {m.role === 'user' ? <p className="whitespace-pre-wrap text-sm leading-relaxed">{m.content}</p> : <Prose text={m.content} />}
                    <p className={`mt-1.5 font-mono text-[11px] ${m.role === 'user' ? 'text-white/60' : 'text-ink-3'}`}>{timeAgo(m.timestamp)}</p>
                  </div>
                </motion.div>
              ))}
            </AnimatePresence>
          )}
          {busy && <div className="flex items-center gap-3"><AgentLogo name={agent} size={30} /><span className="shimmer h-10 w-40 rounded-2xl" aria-label="Thinking" /></div>}
          <div ref={end} />
        </div>
        <form onSubmit={send} className="flex flex-col gap-2 border-t border-white/80 pt-4 sm:flex-row sm:items-end">
          <label htmlFor="chat-input" className="sr-only">Message</label>
          <textarea id="chat-input" rows={2} value={text} onChange={e => { setText(e.target.value); setError(null) }}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }}
            placeholder={`Message ${AGENT_META[agent].label}`} className={`${inputCls} resize-none`} />
          <ChromeButton type="submit" disabled={busy} icon={<PaperPlaneRight size={16} weight="bold" aria-hidden="true" />}>Send</ChromeButton>
        </form>
        {error && <p role="alert" className="mt-2 text-sm text-bad">{error}</p>}
      </Panel>
    </>
  )
}
