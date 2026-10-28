import { motion } from 'motion/react'
import { useEffect, useState, type ReactNode } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export const spring = { type: 'spring', bounce: 0, duration: 0.4 } as const

/** Polls an async source; keeps the last good data on error. */
export function usePoll<T>(load: () => Promise<T>, ms = 5000) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [n, setN] = useState(0)  // bumped by reload() to fetch now instead of waiting for the next tick
  useEffect(() => {
    let alive = true
    const tick = () => load().then(d => { if (alive) { setData(d); setError(null) } })
      .catch(e => alive && setError(String(e.message || e)))
    tick()
    const t = setInterval(tick, ms)
    return () => { alive = false; clearInterval(t) }
  }, [n])  // eslint-disable-line react-hooks/exhaustive-deps
  return { data, error, reload: () => setN(x => x + 1) }
}

export function Panel({ children, className = '', inner = '' }: { children: ReactNode; className?: string; inner?: string }) {
  return (
    <motion.div className={`glass-shell ${className}`} initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} transition={spring}>
      <div className={`glass h-full p-5 md:p-6 ${inner}`}>{children}</div>
    </motion.div>
  )
}

export function Eyebrow({ children }: { children: ReactNode }) {
  return <span className="inline-flex self-start rounded-full border border-white/90 bg-white/50 px-3 py-1 text-[10px] font-medium uppercase tracking-[0.2em] text-ink-2">{children}</span>
}

export function PageHead({ eyebrow, title, sub, action }: { eyebrow: string; title: string; sub?: string; action?: ReactNode }) {
  return (
    <header className="mb-8 flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
      <div className="flex flex-col gap-3">
        <Eyebrow>{eyebrow}</Eyebrow>
        <h1 className="text-3xl font-medium leading-none tracking-tight text-ink md:text-4xl">{title}</h1>
        {sub && <p className="max-w-[60ch] text-sm leading-relaxed text-ink-2">{sub}</p>}
      </div>
      {action && <div className="self-start md:self-auto">{action}</div>}
    </header>
  )
}

export function ChromeButton({ children, icon, onClick, disabled, type = 'button' }: { children: ReactNode; icon?: ReactNode; onClick?: () => void; disabled?: boolean; type?: 'button' | 'submit' }) {
  return (
    <button type={type} onClick={onClick} disabled={disabled}
      className="chrome group inline-flex w-fit shrink-0 self-end min-h-11 cursor-pointer items-center gap-3 rounded-full py-1.5 pl-5 pr-1.5 text-sm font-medium transition-transform duration-200 ease-glide active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-45">
      {children}
      {icon && <span className="flex h-8 w-8 items-center justify-center rounded-full bg-white/15 transition-transform duration-300 ease-glide group-hover:translate-x-0.5 group-hover:-translate-y-px">{icon}</span>}
    </button>
  )
}

export function StatusDot({ on }: { on: boolean }) {
  return (
    <span className="relative flex h-2.5 w-2.5" aria-hidden="true">
      {on && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-ok opacity-40 motion-reduce:hidden" />}
      <span className={`relative inline-flex h-2.5 w-2.5 rounded-full ${on ? 'bg-ok' : 'bg-ink-3'}`} />
    </span>
  )
}

/** Official mark of each agent (web/public/agents). */
export function AgentLogo({ name, size = 28 }: { name: string; size?: number }) {
  return (
    <span className="flex shrink-0 items-center justify-center rounded-[30%] border border-white bg-[#FFFFFF] shadow-[0_4px_12px_-6px_rgba(29,43,58,0.35)]"
      style={{ width: size, height: size }}>
      <img src={`/agents/${name}.svg`} alt="" className="h-[68%] w-[68%] object-contain" />
    </span>
  )
}

const AGENT_LABELS: Record<string, string> = { claude: 'Claude Code', opencode: 'OpenCode', hermes: 'Hermes', agy: 'agy' }

/** Pill toggles for choosing one or several agents. */
export function AgentPicker<T extends string>({ value, onChange, multi = false, legend }: { value: T[]; onChange: (v: T[]) => void; multi?: boolean; legend: string }) {
  const all = Object.keys(AGENT_LABELS) as T[]
  return (
    <fieldset className="flex flex-wrap gap-2">
      <legend className="mb-2 text-sm font-medium text-ink">{legend}</legend>
      {all.map(a => {
        const on = value.includes(a)
        return (
          <button type="button" key={a} aria-pressed={on}
            onClick={() => onChange(multi ? (on ? value.filter(v => v !== a) : [...value, a]) : [a])}
            className={`flex min-h-11 cursor-pointer items-center gap-2 rounded-full border py-1 pl-1.5 pr-4 text-sm transition-colors duration-200 ${on ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2 hover:bg-white'}`}>
            <AgentLogo name={a} size={26} />{AGENT_LABELS[a]}
          </button>
        )
      })}
    </fieldset>
  )
}

export const inputCls = 'w-full rounded-2xl border border-white bg-white/70 px-4 py-3 text-[15px] text-ink placeholder:text-ink-3 focus:outline-2 focus:outline-offset-2 focus:outline-navy'

export function Field({ id, label, help, error, children }: { id: string; label: string; help?: string; error?: string | null; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-sm font-medium text-ink">{label}</label>
      {children}
      {help && <p id={`${id}-help`} className="text-xs text-ink-3">{help}</p>}
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
    </div>
  )
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`shimmer rounded-xl ${className}`} />
}

export function Empty({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="flex flex-col items-start gap-1 py-6">
      <p className="text-sm font-medium text-ink">{title}</p>
      <p className="max-w-[48ch] text-sm text-ink-3">{hint}</p>
    </div>
  )
}

/** Agent replies are markdown; render them as readable text, tables included. */
export function Prose({ text }: { text: string }) {
  return (
    <div className="prose-glacier text-sm leading-relaxed text-ink [overflow-wrap:anywhere]">
      <Markdown remarkPlugins={[remarkGfm]}>{text}</Markdown>
    </div>
  )
}

export function ErrorLine({ msg }: { msg: string }) {
  return <p role="alert" className="text-sm text-bad">Couldn't load: {msg}. It retries every few seconds.</p>
}
