import { Keyboard, Microphone, Stop, SpeakerHigh, SpeakerSlash } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { inputCls, spring } from './ui'

export type Mood = 'normal' | 'good' | 'urgent'
type Msg = { id: string; role: 'owner' | 'jharvis'; text: string; timestamp: string; mood?: Mood }

const level = (an: AnalyserNode, buf: Uint8Array<ArrayBuffer>, gain: number) => {
  an.getByteTimeDomainData(buf)
  let sum = 0
  for (const v of buf) sum += ((v - 128) / 128) ** 2
  return Math.min(1, Math.sqrt(sum / buf.length) * gain)
}

/** Jharvis's voice on his page: subtitles, mic, a keyboard for quiet rooms, mute.
 *  Drives the waves through `energy` (mic or his voice) and `mood` (colour of his reply). */
export default function JharvisTalk({ energy, mood, onReply, sample }: { energy: { current: number }; mood: { current: Mood }; onReply: () => void; sample?: string }) {
  const [subtitle, setSubtitle] = useState(sample ?? '')
  const [status, setStatus] = useState<'idle' | 'listening' | 'transcribing' | 'thinking'>('idle')
  const [typing, setTyping] = useState(false)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [muted, setMuted] = useState(() => { try { return localStorage.getItem('jharvis-muted') === 'on' } catch { return false } })
  const mutedRef = useRef(muted)
  mutedRef.current = muted
  const audioCtx = useRef<AudioContext | undefined>(undefined)
  const stopSpeech = useRef<() => void>(() => {})
  const stopMic = useRef<() => void>(() => {})
  const calm = useRef(0)
  const seen = useRef<Set<string> | null>(null)
  const ctx = () => (audioCtx.current ??= new AudioContext())

  // browsers keep audio locked until the first click or key on the page
  useEffect(() => {
    const unlock = () => { ctx().resume() }
    addEventListener('pointerdown', unlock, { once: true })
    addEventListener('keydown', unlock, { once: true })
    return () => { removeEventListener('pointerdown', unlock); removeEventListener('keydown', unlock) }
  }, [])

  useEffect(() => {
    let alive = true
    const tick = async () => {
      try {
        const r = await fetch('/api/jharvis/messages?limit=20')
        const { messages } = await r.json() as { messages: Msg[] }
        if (!alive) return
        if (seen.current === null) {
          seen.current = new Set(messages.map(m => m.id))
          const last = [...messages].reverse().find(m => m.role === 'jharvis')
          if (last && !sample) setSubtitle(last.text)
          return
        }
        for (const m of messages) if (!seen.current.has(m.id)) {
          seen.current.add(m.id)
          if (m.role === 'jharvis') { setStatus('idle'); setSubtitle(m.text); onReply(); setMood(m.mood ?? 'normal'); speak(m.text) }
        }
      } catch { /* the OS restarting; try again next tick */ }
    }
    tick()
    const t = setInterval(tick, 2500)
    return () => { alive = false; clearInterval(t); stopMic.current(); stopSpeech.current(); speechSynthesis?.cancel() }
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  function setMood(m: Mood) {
    clearTimeout(calm.current)
    mood.current = m
  }
  // back to blue a few seconds after he stops talking
  const settle = (ms = 4000) => { clearTimeout(calm.current); calm.current = window.setTimeout(() => { mood.current = 'normal' }, ms) }

  async function speak(reply: string) {
    if (mutedRef.current) { settle(10000); return }
    try {
      // VoiceStudio voice via the OS; the waves follow the real audio level
      const r = await fetch('/api/jharvis/speak', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: reply }) })
      if (!r.ok) throw new Error()
      const c = ctx()
      await c.resume()
      const src = c.createBufferSource()
      src.buffer = await c.decodeAudioData(await r.arrayBuffer())
      const an = c.createAnalyser()
      an.fftSize = 512
      src.connect(an); an.connect(c.destination)
      const buf = new Uint8Array(an.fftSize)
      let raf = 0
      const loop = () => { energy.current = level(an, buf, 5); raf = requestAnimationFrame(loop) }
      src.onended = () => { cancelAnimationFrame(raf); energy.current = 0; settle() }
      stopSpeech.current(); stopSpeech.current = () => { try { src.stop() } catch { /* already ended */ } }
      src.start(); loop()
    } catch {
      if (!('speechSynthesis' in window)) { settle(); return }  // VoiceStudio off: fall back to the browser voice
      const u = new SpeechSynthesisUtterance(reply.replace(/[*_`#>\[\]()]/g, '').slice(0, 600))
      let on = true
      const pump = () => { if (on) { energy.current = 0.35 + Math.random() * 0.5; setTimeout(pump, 110) } }
      u.onstart = pump
      u.onend = u.onerror = () => { on = false; energy.current = 0; settle() }
      speechSynthesis.cancel()
      speechSynthesis.speak(u)
    }
  }

  async function send(m: string) {
    if (!m.trim()) return
    setError(null); setStatus('thinking'); setSubtitle(m)
    try {
      const r = await fetch('/api/jharvis/messages', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: 'owner', text: m.trim() }) })
      if (!r.ok) throw new Error(`Request failed (${r.status})`)
      seen.current?.add((await r.json() as Msg).id)
    } catch (err) { setStatus('idle'); setError((err as Error).message) }
  }

  async function toggleMic() {
    if (status === 'listening') { stopMic.current(); return }
    setError(null); stopSpeech.current(); speechSynthesis?.cancel()
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const c = ctx()
      await c.resume()
      const an = c.createAnalyser()
      an.fftSize = 512
      const srcNode = c.createMediaStreamSource(stream)
      srcNode.connect(an)
      const buf = new Uint8Array(an.fftSize)
      let raf = 0
      const loop = () => { energy.current = level(an, buf, 6); raf = requestAnimationFrame(loop) }
      loop()
      const rec = new MediaRecorder(stream)
      const chunks: Blob[] = []
      rec.ondataavailable = e => chunks.push(e.data)
      rec.onstop = async () => {
        setStatus('transcribing')
        try {
          const form = new FormData()
          form.append('file', new Blob(chunks, { type: rec.mimeType }), 'talk.webm')
          const r = await fetch('/api/jharvis/transcribe', { method: 'POST', body: form })
          if (!r.ok) throw new Error('I couldn’t hear that. Is VoiceStudio running?')
          const { text: said } = await r.json() as { text: string }
          if (said) send(said)
          else { setStatus('idle'); setError('I didn’t catch anything. Try again.') }
        } catch (err) { setStatus('idle'); setError((err as Error).message) }
      }
      rec.start()
      stopMic.current = () => {
        cancelAnimationFrame(raf); srcNode.disconnect(); rec.stop(); stream.getTracks().forEach(t => t.stop())
        energy.current = 0; stopMic.current = () => {}
      }
      setStatus('listening')
    } catch {
      setError(window.isSecureContext ? 'Microphone blocked. Allow it in the browser.' : 'The mic only works on the PC (localhost) or over https.')
    }
  }

  const toggleMute = () => {
    const next = !muted
    setMuted(next)
    try { localStorage.setItem('jharvis-muted', next ? 'on' : 'off') } catch { /* private mode */ }
    if (next) { speechSynthesis?.cancel(); stopSpeech.current() }
  }

  const roundBtn = 'flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-white/70 bg-white/40 text-ink-2 backdrop-blur-md transition-colors hover:bg-white/70 hover:text-ink'
  const line = status === 'listening' ? 'Listening…' : status === 'transcribing' ? 'Got it…' : status === 'thinking' ? `“${subtitle}”` : subtitle.replace(/[*_`#>]|^\s*- /gm, '')

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col items-center gap-4 px-4">
      <AnimatePresence mode="wait">
        <motion.p key={line} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={spring}
          aria-live="polite" title={subtitle} className="line-clamp-3 text-center text-[17px] leading-relaxed text-ink [text-shadow:0_1px_12px_rgba(240,246,250,0.9)]">
          {line || 'Tap the mic and talk to me.'}
        </motion.p>
      </AnimatePresence>
      {status === 'thinking' && <span className="shimmer h-1.5 w-24 rounded-full" aria-label="Jharvis is thinking" />}
      {typing && (
        <form onSubmit={e => { e.preventDefault(); send(text); setText(''); setTyping(false) }} className="w-full max-w-xl">
          <label htmlFor="jh-input" className="sr-only">Message Jharvis</label>
          <input id="jh-input" autoFocus value={text} onChange={e => setText(e.target.value)} onKeyDown={e => { if (e.key === 'Escape') setTyping(false) }}
            placeholder="Type to Jharvis, Enter to send" className={`${inputCls} min-h-11 bg-white/50 backdrop-blur-md`} />
        </form>
      )}
      <div className="flex items-center gap-3">
        <button onClick={() => setTyping(t => !t)} aria-pressed={typing} aria-label="Type instead" className={roundBtn}><Keyboard size={18} /></button>
        <button onClick={toggleMic} aria-pressed={status === 'listening'} aria-label={status === 'listening' ? 'Stop and send' : 'Talk'}
          className={`flex h-16 w-16 cursor-pointer items-center justify-center rounded-full border transition-transform active:scale-95 ${status === 'listening' ? 'chrome border-transparent' : 'border-white/80 bg-white/50 text-ink backdrop-blur-md hover:bg-white/80'}`}>
          {status === 'listening' ? <Stop size={22} weight="fill" /> : <Microphone size={24} />}
        </button>
        <button onClick={toggleMute} aria-pressed={muted} aria-label={muted ? 'Unmute Jharvis' : 'Mute Jharvis'} className={roundBtn}>
          {muted ? <SpeakerSlash size={18} /> : <SpeakerHigh size={18} />}
        </button>
      </div>
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
    </div>
  )
}
