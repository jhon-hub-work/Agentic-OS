import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { spring } from '../ui'
import JharvisTalk, { type Mood } from '../JharvisTalk'
import WaveField from '../WaveField'

type Panel = { id: string; title: string; lines: string[] }
type Ask = { id: string; tool: string; args: Record<string, unknown> }
type Need = { kind: string; title: string; detail?: string; action?: string; link?: string }
// stored error text can carry mangled emoji (e.g. "â±"); keep the readable part, first line only
const clean = (t: string) => t.split('\n')[0].replace(/[^\x20-\x7E‘’“”–—]+/g, ' ').replace(/\s+/g, ' ').trim()

// ?demo in the URL shows sample panels and cycles the wave colours, for design review
const DEMO = new URLSearchParams(location.search).has('demo')
const DEMO_MOOD = new URLSearchParams(location.search).get('demo') as Mood | null  // ?demo=good or ?demo=urgent holds one colour
const SAMPLE: Panel[] = [
  { id: 'jobs', title: '4 jobs to approve', lines: ['Video editor, Shorts · $8/h · score 86', 'YouTube cutter · $6/h · score 81', 'Reels editor, clinic · $7/h · score 78', 'Podcast clipper · $5/h · score 74'] },
  { id: 'today', title: 'Today', lines: ['DYE post at 15:00 Manila, approved', 'Job search ran 14:00, 1 sent', 'D: drive 91% full'] },
]

/** Jharvis's page: only his waves, the glass panels he puts on screen, his subtitles and the mic. */
export default function Home() {
  const energy = useRef(0)            // voice level for the waves (mic or Jharvis speaking)
  const mood = useRef<Mood>(DEMO_MOOD || 'normal')  // colour of his latest reply
  const [replies, setReplies] = useState(0)
  const [panels, setPanels] = useState<Panel[]>(DEMO ? SAMPLE : [])
  // what needs Jhon, straight from the OS: always on screen, whether or not Jharvis puts up a panel
  const [needs, setNeeds] = useState<Need[]>([])
  useEffect(() => {
    if (DEMO) return
    let alive = true
    const load = () => fetch('/api/jharvis/home').then(r => r.ok ? r.json() : null)
      .then(h => { if (h && alive) setNeeds([h.urgent, ...h.next].filter(Boolean)) }).catch(() => {})
    load()
    const t = setInterval(load, 15000)
    return () => { alive = false; clearInterval(t) }
  }, [])
  // Jharvis waits for these before a gated action (e.g. a shell command) in a dashboard chat
  const [asks, setAsks] = useState<Ask[]>([])
  const [deciding, setDeciding] = useState<string | null>(null)
  useEffect(() => {
    if (DEMO) return
    let alive = true
    const load = () => fetch('/api/jharvis/approvals').then(r => r.ok ? r.json() : null)
      .then(d => { if (d && alive) setAsks(d.approvals) }).catch(() => {})
    load()
    const t = setInterval(load, 2000)
    return () => { alive = false; clearInterval(t) }
  }, [])
  async function decide(id: string, approve: boolean) {
    setDeciding(id)
    await fetch(`/api/jharvis/approvals/${id}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ approve }) }).catch(() => {})
    setAsks(a => a.filter(x => x.id !== id)); setDeciding(null)
  }

  useEffect(() => {
    if (DEMO_MOOD) return
    if (DEMO) {
      const moods: Mood[] = ['normal', 'good', 'urgent']
      let i = 0
      const t = setInterval(() => { mood.current = moods[++i % 3] }, 4000)
      return () => clearInterval(t)
    }
    let alive = true
    const tick = async () => {
      try {
        const r = await fetch('/api/jharvis/panels')
        if (r.ok && alive) setPanels((await r.json()).panels)
      } catch { /* the OS restarting */ }
    }
    tick()
    const t = setInterval(tick, 2500)
    return () => { alive = false; clearInterval(t) }
  }, [])

  return (
    <div className="relative h-[calc(100dvh-10rem)] min-h-[520px] md:h-[calc(100dvh-4rem)]">
      <div className="pointer-events-none absolute inset-[22%_8%] rounded-full bg-[radial-gradient(closest-side,rgba(63,182,236,0.25),transparent)] blur-3xl" />
      <WaveField pulse={replies} energy={energy} mood={mood}
        className="absolute inset-0 h-full w-full" />

      {/* panels spread to both sides of the waves, like data on Tony Stark's screen */}
      {(['left', 'right'] as const).map(side => (
        <div key={side} className={`absolute top-[20%] flex w-[min(340px,44vw)] flex-col gap-4 ${side === 'left' ? 'left-0 md:left-6' : 'right-0 md:right-6'}`}>
          {side === 'right' && asks.map(a => (
            <section key={a.id} aria-label="Jharvis asks" className="liquid-glass rounded-[1.75rem] border-2 border-navy/40 p-5">
              <h2 className="mb-2 text-[15px] font-medium tracking-tight text-ink">Jharvis asks: {a.tool.replace(/_/g, ' ')}</h2>
              <pre className="mb-4 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-white/60 p-3 font-mono text-xs text-ink">
                {typeof a.args.command === 'string' ? a.args.command : JSON.stringify(a.args, null, 2)}
              </pre>
              <div className="flex items-center justify-center gap-3">
                <button type="button" disabled={deciding === a.id} onClick={() => decide(a.id, true)}
                  className="chrome min-h-11 min-w-28 cursor-pointer rounded-full px-6 text-sm font-medium disabled:opacity-50">Approve</button>
                <button type="button" disabled={deciding === a.id} onClick={() => decide(a.id, false)}
                  className="min-h-11 min-w-28 cursor-pointer rounded-full border border-white bg-white/60 px-6 text-sm text-ink hover:bg-white disabled:opacity-50">Deny</button>
              </div>
            </section>
          ))}
          {side === 'left' && needs.length > 0 && (
            <section aria-label="Waiting on you" className="liquid-glass rounded-[1.75rem] p-5">
              <h2 className="mb-3 text-[15px] font-medium tracking-tight text-ink">Waiting on you</h2>
              <ul className="flex flex-col gap-3">
                {needs.map((n, k) => (
                  <li key={k} className="text-sm leading-snug">
                    <p className={n.kind === 'problem' ? 'text-bad' : 'text-ink'}>{clean(n.title)}</p>
                    {n.detail && <p className="mt-0.5 text-xs text-ink-3">{clean(n.detail)}</p>}
                    {n.link && <a href={n.link} className="mt-1 inline-block text-xs font-medium text-navy underline underline-offset-2">{n.action ?? 'Open'}</a>}
                  </li>
                ))}
              </ul>
            </section>
          )}
          <AnimatePresence>
            {panels.filter((_, i) => (i % 2 === 0) === (side === 'right')).map(p => (
              <motion.section key={p.id} layout aria-label={p.title}
                initial={{ opacity: 0, scale: 0.92, y: 16, filter: 'blur(8px)' }} animate={{ opacity: 1, scale: 1, y: 0, filter: 'blur(0px)' }}
                exit={{ opacity: 0, scale: 0.94, filter: 'blur(8px)' }} transition={spring}
                className="liquid-glass rounded-[1.75rem] p-5">
                <h2 className="mb-3 text-[15px] font-medium tracking-tight text-ink">{p.title}</h2>
                <ul className="flex flex-col gap-2">
                  {p.lines.map((l, k) => <li key={k} className="text-sm leading-snug text-ink-2">{l}</li>)}
                </ul>
              </motion.section>
            ))}
          </AnimatePresence>
        </div>
      ))}

      <div className="absolute inset-x-0 bottom-2">
        <JharvisTalk energy={energy} mood={mood} onReply={() => setReplies(n => n + 1)}
          sample={DEMO ? 'You have 4 jobs to approve, sir. I put them on the right. The top one pays best.' : undefined} />
      </div>
    </div>
  )
}
