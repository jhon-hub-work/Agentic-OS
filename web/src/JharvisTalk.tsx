import { Microphone, MicrophoneSlash, PaperPlaneRight, SpeakerHigh, SpeakerSlash } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { timeAgo } from './api'
import { ChromeButton, Panel, Prose, inputCls, spring } from './ui'

type Msg = { id: string; role: 'owner' | 'jharvis'; text: string; timestamp: string }

/** Dashboard backup channel to Jharvis (Slack is the main one). Drives the waves through `energy`:
 *  the mic level while you talk, a speech envelope while Jharvis reads a reply aloud. */
export default function JharvisTalk({ energy, onReply }: { energy: { current: number }; onReply: () => void }) {
  const [msgs, setMsgs] = useState<Msg[]>([])
  const [text, setText] = useState('')
  const [waiting, setWaiting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [listening, setListening] = useState(false)
  const [voice, setVoice] = useState(() => { try { return localStorage.getItem('jharvis-voice') === 'on' } catch { return false } })
  const seen = useRef<Set<string> | null>(null)
  const stopMic = useRef<() => void>(() => {})
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let alive = true
    const tick = async () => {
      try {
        const r = await fetch('/api/jharvis/messages?limit=40')
        const { messages } = await r.json() as { messages: Msg[] }
        if (!alive) return
        if (seen.current === null) seen.current = new Set(messages.map(m => m.id))
        else for (const m of messages) if (!seen.current.has(m.id)) {
          seen.current.add(m.id)
          if (m.role === 'jharvis') { setWaiting(false); onReply(); speak(m.text) }
        }
        setMsgs(messages)
      } catch { /* the OS restarting; try again next tick */ }
    }
    tick()
    const t = setInterval(tick, 2500)
    return () => { alive = false; clearInterval(t); stopMic.current(); speechSynthesis?.cancel() }
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }) }, [msgs, waiting])

  function speak(reply: string) {
    if (!voice || !('speechSynthesis' in window)) return
    const u = new SpeechSynthesisUtterance(reply.replace(/[*_`#>\[\]()]/g, '').slice(0, 600))
    let on = true
    const pump = () => { if (on) { energy.current = 0.35 + Math.random() * 0.5; setTimeout(pump, 110) } }
    u.onstart = pump
    u.onend = u.onerror = () => { on = false; energy.current = 0 }
    speechSynthesis.cancel()
    speechSynthesis.speak(u)
  }

  async function toggleMic() {
    if (listening) { stopMic.current(); return }
    setError(null)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const ctx = new AudioContext()
      const an = ctx.createAnalyser()
      an.fftSize = 512
      ctx.createMediaStreamSource(stream).connect(an)
      const buf = new Uint8Array(an.fftSize)
      let raf = 0
      const loop = () => {
        an.getByteTimeDomainData(buf)
        let sum = 0
        for (const v of buf) sum += ((v - 128) / 128) ** 2
        energy.current = Math.min(1, Math.sqrt(sum / buf.length) * 6)
        raf = requestAnimationFrame(loop)
      }
      loop()
      // Words too, where the browser can (Chrome/Edge); Brave turns speech recognition off.
      const SR = (window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike })
      const Rec = SR.SpeechRecognition ?? SR.webkitSpeechRecognition
      const rec = Rec ? new Rec() : null
      if (rec) {
        rec.continuous = true; rec.interimResults = true
        rec.onresult = e => setText(Array.from(e.results).map(r => r[0].transcript).join(' '))
        rec.start()
      }
      stopMic.current = () => {
        cancelAnimationFrame(raf); rec?.stop(); stream.getTracks().forEach(t => t.stop()); ctx.close()
        energy.current = 0; setListening(false); stopMic.current = () => {}
      }
      setListening(true)
    } catch {
      setError(window.isSecureContext ? 'Microphone blocked. Allow it in the browser.' : 'The mic only works on the PC (localhost) or over https, not over the home Wi-Fi address.')
    }
  }

  async function send(e: React.FormEvent) {
    e.preventDefault()
    const m = text.trim()
    if (!m) { setError('Type or say something first.'); return }
    stopMic.current()
    setError(null); setText(''); setWaiting(true)
    try {
      const r = await fetch('/api/jharvis/messages', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: 'owner', text: m }) })
      if (!r.ok) throw new Error(`Request failed (${r.status})`)
      const item = await r.json() as Msg
      seen.current?.add(item.id)
      setMsgs(prev => [...prev, item])
    } catch (err) { setWaiting(false); setError((err as Error).message) }
  }

  const toggleVoice = () => {
    const next = !voice
    setVoice(next)
    try { localStorage.setItem('jharvis-voice', next ? 'on' : 'off') } catch { /* private mode */ }
    if (!next) speechSynthesis?.cancel()
  }

  return (
    <Panel inner="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-medium text-ink-2">Talk to Jharvis <span className="text-ink-3">· also in Slack</span></h3>
        <button onClick={toggleVoice} aria-pressed={voice} aria-label={voice ? 'Voice replies on' : 'Voice replies off'}
          className="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-full border border-white bg-white/60 text-ink-2 hover:bg-white">
          {voice ? <SpeakerHigh size={18} /> : <SpeakerSlash size={18} />}
        </button>
      </div>
      <div className="flex max-h-80 flex-col gap-3 overflow-y-auto pr-1" aria-live="polite">
        {msgs.length === 0 && <p className="text-sm text-ink-3">Jharvis's messages show here too. Ask it anything.</p>}
        <AnimatePresence initial={false}>
          {msgs.slice(-30).map(m => (
            <motion.div key={m.id} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={spring}
              className={`max-w-[88%] rounded-2xl px-4 py-2.5 ${m.role === 'owner' ? 'chrome self-end' : 'self-start border border-white bg-white/65'}`}>
              {m.role === 'owner' ? <p className="whitespace-pre-wrap text-sm">{m.text}</p> : <Prose text={m.text} />}
              <p className={`mt-1 font-mono text-[10px] ${m.role === 'owner' ? 'text-white/60' : 'text-ink-3'}`}>{timeAgo(m.timestamp)}</p>
            </motion.div>
          ))}
        </AnimatePresence>
        {waiting && <span className="shimmer h-9 w-40 self-start rounded-2xl" aria-label="Jharvis is thinking" />}
        <div ref={end} />
      </div>
      <form onSubmit={send} className="flex items-end gap-2">
        <button type="button" onClick={toggleMic} aria-pressed={listening} aria-label={listening ? 'Stop listening' : 'Talk'}
          className={`flex min-h-11 min-w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border ${listening ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2 hover:bg-white'}`}>
          {listening ? <MicrophoneSlash size={18} /> : <Microphone size={18} />}
        </button>
        <label htmlFor="jh-input" className="sr-only">Message Jharvis</label>
        <textarea id="jh-input" rows={1} value={text} onChange={e => { setText(e.target.value); setError(null) }}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }}
          placeholder={listening ? 'Listening…' : 'Message Jharvis'} className={`${inputCls} min-h-11 resize-none py-2.5`} />
        <ChromeButton type="submit" disabled={waiting} icon={<PaperPlaneRight size={16} weight="bold" aria-hidden="true" />}>Send</ChromeButton>
      </form>
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
    </Panel>
  )
}

type SpeechRecognitionLike = {
  continuous: boolean; interimResults: boolean; start(): void; stop(): void
  onresult: (e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void
}
