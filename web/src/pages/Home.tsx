import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { spring } from '../ui'
import JharvisTalk, { type Mood } from '../JharvisTalk'
import WaveField from '../WaveField'

type Panel = { id: string; title: string; lines: string[] }

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
        className="absolute inset-0 h-full w-full [mask-image:radial-gradient(ellipse_75%_75%_at_50%_50%,black_55%,transparent)]" />

      {/* panels spread to both sides of the waves, like data on Tony Stark's screen */}
      {(['left', 'right'] as const).map(side => (
        <div key={side} className={`absolute top-[20%] flex w-[min(340px,44vw)] flex-col gap-4 ${side === 'left' ? 'left-0 md:left-6' : 'right-0 md:right-6'}`}>
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
