import { Play, Plus } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type AgentName, type Skill } from '../api'
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
