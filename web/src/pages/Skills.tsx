import { PaperPlaneRight, Play, Plus } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import { api, type AgentName, type LabRun, type Skill } from '../api'
import { AgentPicker, ChromeButton, Empty, ErrorLine, Field, PageHead, Panel, Prose, Skeleton, inputCls, usePoll } from '../ui'

const summary = (s: Skill) =>
  s.description.match(/^description:\s*(.+)$/m)?.[1] ?? s.description.replace(/---[\s\S]*?---/, '').trim().slice(0, 120)

export default function Skills() {
  const { data, error } = usePoll(api.skills, 30000)
  const [open, setOpen] = useState<string | null>(null)
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<{ agent: string; output: string } | null>(null)
  const [runError, setRunError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)

  async function run(name: string) {
    setRunning(true); setResult(null); setRunError(null)
    try { setResult(await api.runSkill(name, input)) }
    catch (e) { setRunError((e as Error).message) }
    finally { setRunning(false) }
  }

  const skills = (data ?? []).filter(s => !s.name.startsWith('_'))

  return (
    <>
      <PageHead eyebrow="Skills" title="What the OS knows how to do" sub="Each skill is a recipe an agent follows. Pick one, add details, run it."
        action={<ChromeButton onClick={() => setAdding(a => !a)} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{adding ? 'Close' : 'New skill'}</ChromeButton>} />
      {adding && <Panel className="mb-5"><NewSkill onDone={() => setAdding(false)} /></Panel>}
      <Panel className="mb-5"><SkillLab /></Panel>
      {error && <ErrorLine msg={error} />}
      {!data ? <Skeleton className="h-64" /> : skills.length === 0 ? <Empty title="No skills" hint="Add a folder under skills/ with a SKILL.md." /> : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {skills.map(s => (
            <Panel key={s.name}>
              <button onClick={() => { setOpen(open === s.name ? null : s.name); setResult(null); setRunError(null) }}
                aria-expanded={open === s.name} className="flex w-full cursor-pointer flex-col items-start gap-1 text-left">
                <span className="text-[15px] font-medium text-ink">{s.name.replace(/-/g, ' ')}</span>
                <span className="text-sm text-ink-3">{summary(s)}</span>
              </button>
              {open === s.name && (
                <div className="mt-4 flex flex-col gap-3 border-t border-white/80 pt-4">
                  <label htmlFor={`in-${s.name}`} className="text-sm font-medium text-ink">Details (optional)</label>
                  <textarea id={`in-${s.name}`} rows={2} value={input} onChange={e => setInput(e.target.value)}
                    className="rounded-2xl border border-white bg-white/70 px-4 py-3 text-sm text-ink focus:outline-2 focus:outline-offset-2 focus:outline-navy" />
                  <div><ChromeButton onClick={() => run(s.name)} disabled={running} icon={<Play size={14} weight="fill" aria-hidden="true" />}>{running ? 'Running' : 'Run skill'}</ChromeButton></div>
                  {running && <div className="shimmer h-16 rounded-2xl" aria-label="Running" />}
                  {runError && <p role="alert" className="text-sm text-bad">{runError}</p>}
                  {result && (
                    <div className="rounded-2xl border border-white bg-white/60 p-4">
                      <p className="mb-2 text-xs text-ink-3">via {result.agent}</p>
                      <Prose text={result.output} />
                    </div>
                  )}
                </div>
              )}
            </Panel>
          ))}
        </div>
      )}
    </>
  )
}

function NewSkill({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [instructions, setInstructions] = useState('')
  const [agent, setAgent] = useState<AgentName>('claude')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const slug = name.trim().toLowerCase().replace(/\s+/g, '-')
    if (!/^[a-z0-9-]+$/.test(slug)) { setError('Name: letters, numbers and dashes only.'); return }
    if (!description.trim() || !instructions.trim()) { setError('Add a one-line description and the instructions.'); return }
    setBusy(true); setError(null)
    try { await api.addSkill({ name: slug, description: description.trim(), instructions: instructions.trim(), agent }); onDone() }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Field id="sk-name" label="Name"><input id="sk-name" value={name} onChange={e => setName(e.target.value)} className={inputCls} placeholder="job-cover-letter" /></Field>
        <Field id="sk-desc" label="One-line description"><input id="sk-desc" value={description} onChange={e => setDescription(e.target.value)} className={inputCls} placeholder="Writes a tailored cover letter from a job post" /></Field>
      </div>
      <Field id="sk-ins" label="Instructions" help="Write it like you'd brief a person: steps, rules, what good output looks like.">
        <textarea id="sk-ins" rows={6} value={instructions} onChange={e => setInstructions(e.target.value)} className={inputCls} />
      </Field>
      <AgentPicker legend="Runs on" value={[agent]} onChange={v => v[0] && setAgent(v[0])} />
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
      <div><ChromeButton type="submit" disabled={busy} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{busy ? 'Saving' : 'Save skill'}</ChromeButton></div>
    </form>
  )
}

const LAB_STATUS: Record<LabRun['status'], string> = { running: 'Working…', passed: 'Passed', failed: 'Failed', blocked: 'Blocked', error: 'Stopped' }

/** "Install this skill": find it, scan it, test and improve it in the sandbox, install only at the pass mark. */
function SkillLab() {
  const { data, reload } = usePoll(api.skillLab, 3000)
  const [text, setText] = useState('')
  const [rounds, setRounds] = useState(6)
  const [err, setErr] = useState('')
  const end = useRef<HTMLDivElement>(null)
  const runs = data?.runs.slice(-5) ?? []
  const busy = runs.some(r => r.status === 'running')
  const count = runs.reduce((n, r) => n + r.messages.length, 0)
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }) }, [count])
  async function send(e: React.FormEvent) {
    e.preventDefault()
    if (!text.trim()) { setErr('Say which skill, or paste a GitHub link.'); return }
    setErr('')
    try { await api.startSkillLab(text.trim(), rounds); setText(''); reload() } catch (x) { setErr((x as Error).message) }
  }
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="text-sm font-medium text-ink-2">Skill lab</h2>
        <p className="text-xs text-ink-3">Ask for a skill. It's found, checked by NVIDIA SkillSpector and a safety read, then tested in a sandbox (its scripts never run). Each round it's scored and improved. It installs for every agent only at {data?.pass ?? 85}% or better.</p>
      </div>
      <div className="flex max-h-[28rem] flex-col gap-4 overflow-y-auto rounded-2xl border border-white bg-white/45 p-4" aria-live="polite">
        {runs.length === 0 && <p className="text-sm text-ink-3">Try: "install a skill for writing changelogs" or paste a GitHub link.</p>}
        {runs.map(r => (
          <div key={r.id} className="flex flex-col gap-2">
            {r.messages.map((m, i) => m.who === 'you'
              ? <p key={i} className="max-w-[80%] self-end rounded-2xl bg-navy px-4 py-2 text-sm text-white">{m.text}</p>
              : <div key={i} className="max-w-[90%] self-start rounded-2xl border border-white bg-white/75 px-4 py-2"><Prose text={m.text} /></div>)}
            {r.scores.length > 0 && <Scores scores={r.scores} pass={data?.pass ?? 85} rounds={r.rounds} />}
            <p className={`self-start text-xs ${r.status === 'passed' ? 'text-ok' : r.status === 'running' ? 'text-ink-3' : 'text-bad'}`}>
              {r.name || 'skill'}: {LAB_STATUS[r.status]}{r.installed_to ? ' and installed' : ''}</p>
            {!busy && (r.status === 'blocked' && (r.blocked_by === 'scanner' || r.messages[r.messages.length - 1]?.text.startsWith('**Blocked by the scanner**')) || r.status === 'error' && r.override) && (
              <button type="button" onClick={() => api.installAnyway(r.id).then(reload, x => setErr((x as Error).message))}
                className="min-h-10 self-start rounded-full border border-steel/80 bg-white/70 px-4 text-sm text-ink transition-colors duration-200 hover:bg-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-navy">
                Install anyway (the safety read and tests still decide)
              </button>
            )}
          </div>
        ))}
        <div ref={end} />
      </div>
      <form onSubmit={send} className="flex flex-col gap-3 md:flex-row md:items-end">
        <label className="flex flex-1 flex-col gap-1 text-sm font-medium text-ink">Message
          <input value={text} onChange={e => { setText(e.target.value); setErr('') }} disabled={busy}
            placeholder={busy ? 'Wait for the current skill to finish' : 'install the … skill'} className={inputCls} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-ink">Rounds: {rounds}
          <input type="range" min={4} max={10} value={rounds} onChange={e => setRounds(+e.target.value)} disabled={busy} className="w-40" />
        </label>
        <ChromeButton type="submit" disabled={busy} icon={<PaperPlaneRight size={14} weight="fill" aria-hidden="true" />}>Send</ChromeButton>
      </form>
      {err && <p role="alert" className="text-sm text-bad">{err}</p>}
    </div>
  )
}

/** One bar per round; the line is the pass mark. */
function Scores({ scores, pass, rounds }: { scores: number[]; pass: number; rounds: number }) {
  return (
    <div className="self-start" aria-label={`Scores per round: ${scores.join(', ')}%. Pass is ${pass}%.`}>
      <div className="relative flex h-16 items-end gap-1.5">
        <div className="absolute inset-x-0 border-t border-dashed border-ok" style={{ bottom: `${pass}%` }} />
        {Array.from({ length: rounds }, (_, i) => (
          <div key={i} title={scores[i] != null ? `Round ${i + 1}: ${scores[i]}%` : `Round ${i + 1}`}
            className={`w-5 rounded-t ${scores[i] == null ? 'bg-ink-3/15' : scores[i] >= pass ? 'bg-ok' : 'bg-navy/70'}`}
            style={{ height: `${Math.max(scores[i] ?? 4, 4)}%` }} />
        ))}
      </div>
      <p className="mt-1 text-[11px] text-ink-3">Rounds; the dashed line is the {pass}% pass mark</p>
    </div>
  )
}
