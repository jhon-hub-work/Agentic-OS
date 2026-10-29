import { Keyboard, Microphone, Stop, SpeakerHigh, SpeakerSlash } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { inputCls, spring } from './ui'

export type Mood = 'normal' | 'good' | 'urgent'
type Msg = { id: string; role: 'owner' | 'jharvis'; text: string; timestamp: string; mood?: Mood }

// Alerts are written for Slack (<url|Job title>, :emoji:, "Jhon,"); on his page he says just the gist
export const brief = (t: string) => t
  .replace(/<https?:[^|>]+\|([^>]+)>/g, '$1')
  .replace(/<https?:[^>]+>|https?:\/\/\S+/g, '')
  .replace(/:[a-z_]+:/g, '')
  .replace(/^\s*Jhon,/, 'Sir,')
  .replace(/\s+/g, ' ').trim()

const level = (an: AnalyserNode, buf: Uint8Array<ArrayBuffer>, gain: number) => {
  an.getByteTimeDomainData(buf)
  let sum = 0
  for (const v of buf) sum += ((v - 128) / 128) ** 2
  return Math.min(1, Math.sqrt(sum / buf.length) * gain)
}

/** Jharvis's voice on his page: subtitles, mic, a keyboard for quiet rooms, mute.
 *  Drives the waves through `energy` (mic or his voice), `mood` (colour of his reply) and `speaking` (his voice only). */
const ACKS = {
  ask: ['Let me check.', 'Good question, one sec.', 'Looking into it.'],
  task: ['On it.', 'Right away, sir.', 'Working on it.'],
  other: ['Mm, let me think.', 'One moment, sir.'],
}
const ACKS_ALL = [...ACKS.ask, ...ACKS.task, ...ACKS.other]
const IS_PHONE = /Android|iPhone|iPad/i.test(navigator.userAgent)  // the phone app / phone browser
const SMALL_TALK =/^(hi|hey|hello|yo|thanks?|thank you|ok(ay)?|yes|yeah|yep|no|nope|cool|nice|great|good( (morning|night))?)\b[\s!.?]*$/i

/** The filler for this message, or '' for none: small talk and a flowing conversation (a filler under 45 s ago) get none. */
function pickAck(m: string, last: { text: string; at: number }, now = Date.now()): string {
  const t = m.trim()
  if (SMALL_TALK.test(t) || t.split(/\s+/).length <= 2 || now - last.at < 45000) return ''
  const kind = /\?$|^(what|how|why|when|where|who|which|can|could|is|are|do|does|did|should|will|would)\b/i.test(t) ? 'ask'
    : /^(please\s+)?(open|show|make|send|find|check|start|stop|run|turn|play|close|add|create|draft|look|put|set|tell)\b/i.test(t) ? 'task' : 'other'
  const choices = ACKS[kind].filter(a => a !== last.text)
  return choices[Math.floor(Math.random() * choices.length)]
}

export default function JharvisTalk({ energy, mood, speaking, onReply, sample, big }: { energy: { current: number }; mood: { current: Mood }; speaking?: { current: boolean }; onReply: () => void; sample?: string; big?: boolean }) {
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
  const stopMic = useRef<(send?: boolean) => void>(() => {})
  // hands-free: one tap starts a conversation; it sends when you go quiet and listens again after he answers
  const [handsFree, setHandsFree] = useState(false)
  const handsFreeRef = useRef(false)
  const setConversation = (on: boolean) => { handsFreeRef.current = on; setHandsFree(on) }
  const again = () => { if (handsFreeRef.current) setTimeout(() => { if (handsFreeRef.current) listen() }, 350) }
  const calm = useRef(0)
  const seen = useRef<Set<string> | null>(null)
  const askedHere = useRef<boolean | null>(null)  // was the last question asked on this device? null: none since it opened
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
          if (last && !sample) setSubtitle(brief(last.text))
          return
        }
        for (const m of messages) if (!seen.current.has(m.id)) {
          seen.current.add(m.id)
          if (m.role === 'owner') askedHere.current = false  // Jhon asked from his other device: that one answers aloud
          if (m.role === 'jharvis') {
            setStatus('idle'); setSubtitle(brief(m.text)); onReply(); setMood(m.mood ?? 'normal')
            // only the device he talked to speaks (PC and phone talked over each other, 29 Oct); unprompted: the PC
            if (askedHere.current ?? !IS_PHONE) speak(brief(m.text)); else settle()
          }
        }
      } catch { /* the OS restarting; try again next tick */ }
    }
    tick()
    const t = setInterval(tick, 1000)  // a 2.5 s poll added ~1 s to every answer
    return () => { alive = false; clearInterval(t); stopMic.current(); stopSpeech.current(); window.speechSynthesis?.cancel() }
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  function setMood(m: Mood) {
    clearTimeout(calm.current)
    mood.current = m
  }
  // back to blue a few seconds after he stops talking
  const settle = (ms = 4000) => { clearTimeout(calm.current); calm.current = window.setTimeout(() => { mood.current = 'normal' }, ms) }

  async function speak(reply: string) {
    if (mutedRef.current) { settle(10000); again(); return }
    // the dots rise into his orb or face while the voice is made (several seconds), so it's formed when he starts
    if (speaking) speaking.current = true
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
      src.onended = () => { cancelAnimationFrame(raf); energy.current = 0; if (speaking) speaking.current = false; settle(); again() }
      stopSpeech.current(); stopSpeech.current = () => { try { src.stop() } catch { /* already ended */ } }
      src.start(); loop()
    } catch {
      if (!('speechSynthesis' in window)) { if (speaking) speaking.current = false; settle(); again(); return }  // VoiceStudio off: fall back to the browser voice
      const u = new SpeechSynthesisUtterance(reply.replace(/[*_`#>\[\]()]/g, '').slice(0, 600))
      let on = true
      const pump = () => { if (on) { energy.current = 0.35 + Math.random() * 0.5; setTimeout(pump, 110) } }
      u.onstart = () => { if (speaking) speaking.current = true; pump() }
      u.onend = u.onerror = () => { on = false; energy.current = 0; if (speaking) speaking.current = false; settle(); again() }
      speechSynthesis.cancel()
      speechSynthesis.speak(u)
    }
  }

  // A short filler the moment you finish, so there's no dead air while he thinks. Jhon (29 Oct): the same "One moment,
  // sir" every time was annoying, so it fits the ask, never repeats back to back, and is skipped for small talk and
  // while a conversation is flowing.
  const acks = useRef(new Map<string, Promise<ArrayBuffer | null>>())
  const lastAck = useRef({ text: '', at: 0 })
  const loadAck = (text: string) => {
    if (!acks.current.has(text)) acks.current.set(text, fetch('/api/jharvis/speak', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) })
      .then(r => r.ok ? r.arrayBuffer() : null).catch(() => null))
    return acks.current.get(text)!
  }
  useEffect(() => {  // made one after another up front (VoiceStudio needs ~3 s each), so they play instantly
    let i = 0
    const next = () => { const t = ACKS_ALL[i++]; if (t) loadAck(t).then(next) }
    next()
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps
  async function acknowledge(m: string) {
    if (mutedRef.current) return
    const text = pickAck(m, lastAck.current)
    if (!text) return
    lastAck.current = { text, at: Date.now() }
    const data = await loadAck(text)
    if (!data) { acks.current.delete(text); return }
    const c = ctx(), src = c.createBufferSource()
    src.buffer = await c.decodeAudioData(data.slice(0))
    src.connect(c.destination); src.start()
  }

  async function send(m: string) {
    if (!m.trim()) return
    acknowledge(m)
    setError(null); setStatus('thinking'); setSubtitle(m)
    askedHere.current = true
    try {
      const r = await fetch('/api/jharvis/messages', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: 'owner', text: m.trim(), client: IS_PHONE ? 'phone' : 'pc' }) })
      if (!r.ok) throw new Error(`Request failed (${r.status})`)
      seen.current?.add((await r.json() as Msg).id)
    } catch (err) { setStatus('idle'); setError((err as Error).message); setConversation(false) }
  }

  // tap: start a conversation; tap again: send what was said (if anything) and end it
  function toggleMic() {
    if (handsFreeRef.current || status === 'listening') {
      setConversation(false)
      stopMic.current(true)
      return
    }
    setConversation(true)
    listen()
  }

  async function listen() {
    setError(null); stopSpeech.current(); window.speechSynthesis?.cancel()
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
      // voice detection: once you've spoken, ~1.1 s of quiet sends it; 8 s of nothing ends the conversation
      const began = performance.now()
      let heard = false, quietSince = 0
      const loop = () => {
        const lvl = level(an, buf, 6), now = performance.now()
        energy.current = lvl
        if (lvl > 0.18) { heard = true; quietSince = 0 }
        else if (heard && lvl < 0.1) quietSince ||= now
        if (heard && quietSince && now - quietSince > 1100) return stopMic.current(true)
        if (heard && now - began > 30000) return stopMic.current(true)
        if (!heard && now - began > 8000) { setConversation(false); return stopMic.current(false) }
        raf = requestAnimationFrame(loop)
      }
      loop()
      const rec = new MediaRecorder(stream)
      const chunks: Blob[] = []
      let keep = true
      rec.ondataavailable = e => chunks.push(e.data)
      rec.onstop = async () => {
        if (!keep || !heard) { setStatus('idle'); return }
        setStatus('transcribing')
        try {
          const form = new FormData()
          form.append('file', new Blob(chunks, { type: rec.mimeType }), 'talk.webm')
          const r = await fetch('/api/jharvis/transcribe', { method: 'POST', body: form })
          if (!r.ok) throw new Error('I couldn’t hear that. Try again in a moment.')
          const { text: said } = await r.json() as { text: string }
          if (said) send(said)
          else if (handsFreeRef.current) listen()  // only noise: keep listening
          else setStatus('idle')
        } catch (err) { setStatus('idle'); setError((err as Error).message); setConversation(false) }
      }
      rec.start()
      stopMic.current = (send = false) => {
        keep = send
        cancelAnimationFrame(raf); srcNode.disconnect(); rec.stop(); stream.getTracks().forEach(t => t.stop())
        energy.current = 0; stopMic.current = () => {}
      }
      setStatus('listening')
    } catch {
      setConversation(false)
      setError(window.isSecureContext ? 'Microphone blocked. Allow it in the browser.' : 'The mic only works on the PC (localhost) or over https.')
    }
  }

  const toggleMute = () => {
    const next = !muted
    setMuted(next)
    try { localStorage.setItem('jharvis-muted', next ? 'on' : 'off') } catch { /* private mode */ }
    if (next) { window.speechSynthesis?.cancel(); stopSpeech.current() }
  }

  const roundBtn = 'flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-white/70 bg-white/40 text-ink-2 backdrop-blur-md transition-colors hover:bg-white/70 hover:text-ink'
  const line = status === 'listening' ? 'Listening… just talk, I’ll answer when you pause.' : status === 'transcribing' ? 'Got it…' : status === 'thinking' ? `“${subtitle}”` : subtitle.replace(/[*_`#>]|^\s*- /gm, '')

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col items-center gap-4 px-4">
      <AnimatePresence mode="wait">
        <motion.p key={line} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={spring}
          aria-live="polite" className="max-h-[38vh] overflow-y-auto whitespace-pre-line text-center text-[17px] leading-relaxed text-ink [text-shadow:0_1px_12px_var(--color-ice)]">
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
        <button onClick={toggleMic} aria-pressed={handsFree} aria-label={handsFree ? 'End the conversation' : 'Talk'}
          title={handsFree ? 'Talking hands-free. Tap to end.' : 'Tap once, then just talk'}
          className={`relative flex ${big ? 'h-24 w-24' : 'h-16 w-16'} cursor-pointer items-center justify-center rounded-full border transition-transform active:scale-95 ${handsFree ? 'chrome border-transparent' : 'border-white/80 bg-white/50 text-ink backdrop-blur-md hover:bg-white/80'}`}>
          {status === 'listening' && <span className="absolute inset-0 animate-ping rounded-full border-2 border-glacier/60" aria-hidden="true" />}
          {handsFree ? <Stop size={big ? 30 : 22} weight="fill" /> : <Microphone size={big ? 36 : 24} />}
        </button>
        <button onClick={toggleMute} aria-pressed={muted} aria-label={muted ? 'Unmute Jharvis' : 'Mute Jharvis'} className={roundBtn}>
          {muted ? <SpeakerSlash size={18} /> : <SpeakerHigh size={18} />}
        </button>
      </div>
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
    </div>
  )
}
