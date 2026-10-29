import { Play, Plus, Trash } from '@phosphor-icons/react'
import { useState } from 'react'
import { AGENT_META, api, timeAgo, type AgentName, type Job } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Empty, ErrorLine, Field, PageHead, Panel, Prose, Skeleton, inputCls, usePoll } from '../ui'

const PRESETS: [string, string][] = [
  ['Weekdays 9:00', '0 9 * * 1-5'],
  ['Every day 15:00', '0 15 * * *'],
  ['Every hour', '0 * * * *'],
  ['Mondays 8:00', '0 8 * * 1'],
]

const describe = (cron: string) => PRESETS.find(p => p[1] === cron)?.[0] ?? cron

export default function Schedules() {
  const jobs = usePoll(api.jobs, 5000)
  const events = usePoll(api.jobEvents, 5000)
  const skills = usePoll(api.skills, 60000)
  const [adding, setAdding] = useState(false)

  const lastOutput = (id: string) => [...(events.data?.events ?? [])].reverse().find(e => e.type === 'job_done' && e.job_id === id)

  return (
    <>
      <PageHead eyebrow="Schedules" title="Jobs that run on their own"
        sub="Any agent, a team run, or a skill, on a timetable. Times are this PC's clock."
        action={<ChromeButton onClick={() => setAdding(a => !a)} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{adding ? 'Close' : 'New schedule'}</ChromeButton>} />
      {adding && <Panel className="mb-5"><NewJob skills={(skills.data ?? []).map(s => s.name)} onDone={() => setAdding(false)} /></Panel>}
      {jobs.error && <ErrorLine msg={jobs.error} />}
      {!jobs.data ? <Skeleton className="h-48" /> : jobs.data.length === 0 ? <Panel><Empty title="No schedules" hint="Add one: for example, agy researching new video editor jobs every weekday at 9." /></Panel> : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {jobs.data.map(j => <JobCard key={j.id} job={j} last={lastOutput(j.id)} />)}
        </div>
      )}
    </>
  )
}

function JobCard({ job, last }: { job: Job; last?: { output?: string; timestamp: string } }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const act = async (fn: () => Promise<unknown>) => { setBusy(true); setErr(''); try { await fn() } catch (x) { setErr((x as Error).message) } finally { setBusy(false) } }
  const kind = job.kind ?? 'skill'
  return (
    <Panel inner="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          {kind !== 'skill' && job.agent && <AgentLogo name={job.agent} size={36} />}
          <div>
            <p className="text-[15px] font-medium text-ink">{job.name}</p>
            <p className="text-sm text-ink-3">{kind === 'skill' ? `Skill: ${job.skill}` : `${kind === 'team' ? 'Team run from' : ''} ${AGENT_META[job.agent]?.label ?? job.agent}`}, {describe(job.cron)}</p>
          </div>
        </div>
        <label className="relative inline-flex min-h-11 cursor-pointer items-center" title={job.enabled ? 'On' : 'Off'}>
          <input type="checkbox" className="peer sr-only" checked={job.enabled} disabled={busy}
            onChange={e => act(() => api.toggleJob(job.id, e.target.checked))} aria-label={`${job.name} ${job.enabled ? 'on' : 'off'}`} />
          <span className="h-7 w-12 rounded-full bg-ink-3/30 transition-colors peer-checked:bg-navy" />
          <span className="absolute left-1 h-5 w-5 rounded-full bg-white shadow transition-transform duration-300 ease-glide peer-checked:translate-x-5" />
        </label>
      </div>
      {job.prompt && <p className="line-clamp-3 text-sm text-ink-2">{job.prompt}</p>}
      {last?.output && (
        <details className="rounded-2xl border border-white bg-white/55 p-3">
          <summary className="cursor-pointer text-xs text-ink-3">Last run {timeAgo(last.timestamp)}</summary>
          <div className="mt-2"><Prose text={last.output} /></div>
        </details>
      )}
      <div className="flex gap-2">
        <button onClick={() => act(() => api.runJob(job.id))} disabled={busy} className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:opacity-50"><Play size={14} aria-hidden="true" /> Run now</button>
        <button onClick={() => { if (confirm(`Delete "${job.name}"?`)) act(() => api.deleteJob(job.id)) }} disabled={busy} className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full px-4 text-sm text-bad hover:bg-white/60"><Trash size={14} aria-hidden="true" /> Delete</button>
      </div>
      {err && <p role="alert" className="text-sm text-bad">{err}</p>}
    </Panel>
  )
}

function NewJob({ skills, onDone }: { skills: string[]; onDone: () => void }) {
  const [name, setName] = useState('')
  const [kind, setKind] = useState<Job['kind']>('agent')
  const [agent, setAgent] = useState<AgentName>('agy')
  const [prompt, setPrompt] = useState('')
  const [skill, setSkill] = useState('')
  const [cron, setCron] = useState(PRESETS[0][1])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim()) { setError('Give it a name.'); return }
    setBusy(true); setError(null)
    try { await api.createJob({ name: name.trim(), kind, agent, prompt, skill: skill || null, cron }); onDone() }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <Field id="j-name" label="Name"><input id="j-name" value={name} onChange={e => setName(e.target.value)} className={inputCls} placeholder="Morning job scan" /></Field>
      <fieldset className="flex flex-wrap gap-2">
        <legend className="mb-2 text-sm font-medium text-ink">What runs</legend>
        {(['agent', 'team', 'skill'] as const).map(k => (
          <button type="button" key={k} aria-pressed={kind === k} onClick={() => setKind(k)}
            className={`min-h-11 cursor-pointer rounded-full border px-4 text-sm ${kind === k ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2'}`}>
            {{ agent: 'One agent', team: 'Team run', skill: 'A skill' }[k]}
          </button>
        ))}
      </fieldset>
      {kind === 'skill' ? (
        <Field id="j-skill" label="Skill">
          <select id="j-skill" value={skill} onChange={e => setSkill(e.target.value)} className={inputCls}>
            <option value="">Choose a skill</option>{skills.map(s => <option key={s}>{s}</option>)}
          </select>
        </Field>
      ) : <AgentPicker legend={kind === 'team' ? 'Starts with' : 'Agent'} value={[agent]} onChange={v => v[0] && setAgent(v[0])} />}
      <Field id="j-prompt" label={kind === 'skill' ? 'Details (optional)' : 'Task'}>
        <textarea id="j-prompt" rows={3} value={prompt} onChange={e => setPrompt(e.target.value)} className={inputCls}
          placeholder="Find 5 new remote video editor jobs paying at least USD 1,600 a month and list them with links." />
      </Field>
      <Field id="j-cron" label="When" help="Pick a preset or type a cron line: minute hour day month weekday.">
        <div className="flex flex-wrap gap-2">
          {PRESETS.map(([label, c]) => (
            <button type="button" key={c} onClick={() => setCron(c)} aria-pressed={cron === c}
              className={`min-h-11 cursor-pointer rounded-full border px-4 text-sm ${cron === c ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2'}`}>{label}</button>
          ))}
        </div>
        <input id="j-cron" value={cron} onChange={e => setCron(e.target.value)} className={`${inputCls} font-mono`} />
      </Field>
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
      <div><ChromeButton type="submit" disabled={busy} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{busy ? 'Saving' : 'Save schedule'}</ChromeButton></div>
    </form>
  )
}
