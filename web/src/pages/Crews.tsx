import { Play, Plus, Trash } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { AGENT_META, api, timeAgo, type AgentName, type Crew, type CrewRole } from '../api'
import { AgentLogo, ChromeButton, Empty, ErrorLine, Field, ModelPick, Panel, Prose, Skeleton, inputCls, usePoll, useModelInfo } from '../ui'

const AGENTS = Object.keys(AGENT_META) as AgentName[]
const ROLE_HELP = { agent: 'Does the work.', advisor: 'Decides what to make, before the agent starts.', helper: 'Checks facts and links for the agent.' }
const STATUS: Record<string, string> = { running: 'Working', awaiting_approval: 'Waiting for your Approve (Approvals page)', approved: 'Approved',
  posting: 'Posting', posted: 'Posted', denied: 'Denied', failed: 'Failed' }
const smallBtn = 'inline-flex min-h-10 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:opacity-50'

// Designated agents (Jhon 30 Oct): each with its own task, skills, model, and an optional advisor and helper.
export default function Crews() {
  const list = usePoll(api.crews, 4000)
  const [making, setMaking] = useState(false)
  return (
    <Panel className="mb-5">
      <div className="mb-3 flex items-center gap-3">
        <h2 className="text-lg font-medium tracking-tight text-ink">Designated agents</h2>
        <button type="button" onClick={() => setMaking(!making)} className={`${smallBtn} ml-auto`}><Plus size={14} aria-hidden="true" />{making ? 'Close' : 'New agent'}</button>
      </div>
      {making && list.data && <NewCrew actions={list.data.actions} onDone={() => { setMaking(false); list.reload() }} />}
      {list.error && <ErrorLine msg={list.error} />}
      {!list.data ? <Skeleton className="h-24" /> : list.data.crews.length === 0
        ? <Empty title="No designated agents yet" hint="Make one with a task, skills and models. Jharvis can make them too." />
        : <div className="flex flex-col gap-4">{list.data.crews.map(c => <CrewCard key={c.id} c={c} onChange={list.reload} />)}</div>}
    </Panel>
  )
}

function RolePick({ role, value, onChange, optional }: { role: keyof typeof ROLE_HELP; value: CrewRole | null; onChange: (v: CrewRole | null) => void; optional?: boolean }) {
  const info = useModelInfo(value?.agent ?? 'claude')
  return (
    <fieldset className="flex flex-col gap-2 rounded-2xl border border-white bg-white/45 p-3">
      <legend className="px-1 text-sm font-medium capitalize text-ink">{role}{optional && ' (optional)'}</legend>
      <p className="text-xs text-ink-3">{ROLE_HELP[role]}</p>
      <div className="flex flex-wrap items-center gap-2">
        <select aria-label={`${role} agent`} value={value?.agent ?? ''} onChange={e => onChange(e.target.value ? { agent: e.target.value } : null)}
          className="min-h-10 rounded-xl border border-white bg-white/70 px-3 text-sm text-ink">
          {optional && <option value="">None</option>}
          {AGENTS.map(a => <option key={a} value={a}>{AGENT_META[a].label}</option>)}
        </select>
        {value && <ModelPick agent={value.agent} info={info} value={value} onChange={v => onChange({ ...value, ...v })}
          base={{ model: info?.current ?? '', effort: info?.current_effort ?? '' }} />}
      </div>
    </fieldset>
  )
}

function NewCrew({ actions, onDone }: { actions: Record<string, string>; onDone: () => void }) {
  const [name, setName] = useState('')
  const [task, setTask] = useState('')
  const [skills, setSkills] = useState('')
  const [agent, setAgent] = useState<CrewRole | null>({ agent: 'opencode' })
  const [advisor, setAdvisor] = useState<CrewRole | null>(null)
  const [helper, setHelper] = useState<CrewRole | null>(null)
  const [action, setAction] = useState('')
  const [schedule, setSchedule] = useState('')
  const [first, setFirst] = useState(3)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [known, setKnown] = useState<string[]>([])
  useEffect(() => { api.skills().then(s => setKnown(s.map(x => x.name))).catch(() => {}) }, [])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim() || !task.trim() || !agent) { setErr('Give it a name, a task and an agent.'); return }
    setBusy(true); setErr(null)
    try {
      await api.createCrew({ name: name.trim(), task: task.trim(), skills: skills.split(',').map(s => s.trim()).filter(Boolean),
        agent, advisor, helper, action, schedule: schedule.trim(), approve_first: first })
      onDone()
    } catch (x) { setErr((x as Error).message) } finally { setBusy(false) }
  }
  return (
    <form onSubmit={submit} className="mb-5 flex flex-col gap-4 rounded-2xl border border-white bg-white/40 p-4">
      <Field id="c-name" label="Name"><input id="c-name" value={name} onChange={e => setName(e.target.value)} className={inputCls} placeholder="IG carousels" /></Field>
      <Field id="c-task" label="Its task" help="What it should make or do each run, in your words.">
        <textarea id="c-task" rows={3} value={task} onChange={e => setTask(e.target.value)} className={inputCls}
          placeholder="Make a carousel of useful GitHub repos for freelancers and agencies, one topic per post." />
      </Field>
      <Field id="c-skills" label="Skills (optional)" help={known.length ? `Comma-separated. Yours: ${known.slice(0, 12).join(', ')}${known.length > 12 ? '…' : ''}` : 'Comma-separated skill names.'}>
        <input id="c-skills" value={skills} onChange={e => setSkills(e.target.value)} className={inputCls} placeholder="ig-carousel" />
      </Field>
      <RolePick role="agent" value={agent} onChange={setAgent} />
      <RolePick role="advisor" value={advisor} onChange={setAdvisor} optional />
      <RolePick role="helper" value={helper} onChange={setHelper} optional />
      <Field id="c-action" label="When it's done">
        <select id="c-action" value={action} onChange={e => setAction(e.target.value)} className={inputCls}>
          {Object.entries(actions).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field id="c-sched" label="Schedule (optional)" help="Cron in the PC's clock (US Pacific). 0 1 * * * = 5 PM Manila daily.">
          <input id="c-sched" value={schedule} onChange={e => setSchedule(e.target.value)} className={`${inputCls} font-mono`} placeholder="0 1 * * *" />
        </Field>
        <Field id="c-first" label="You approve the first" help="Runs that wait for your Approve; after that it runs on its own.">
          <input id="c-first" type="number" min={0} max={20} value={first} onChange={e => setFirst(Number(e.target.value))} className={inputCls} />
        </Field>
      </div>
      {err && <ErrorLine msg={err} />}
      <div><ChromeButton type="submit" disabled={busy} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{busy ? 'Making it' : 'Make agent'}</ChromeButton></div>
    </form>
  )
}

function roleLine(label: string, r: CrewRole | null) {
  return r && <span className="inline-flex items-center gap-1.5"><AgentLogo name={r.agent as AgentName} size={18} />{label}: {AGENT_META[r.agent as AgentName]?.label ?? r.agent}{r.model ? ` (${r.model})` : ''}</span>
}

function CrewCard({ c, onChange }: { c: Crew; onChange: () => void }) {
  const [err, setErr] = useState<string | null>(null)
  const act = (fn: () => Promise<unknown>) => fn().then(onChange).catch(x => setErr((x as Error).message))
  const left = Math.max(0, c.approve_first - c.approved)
  return (
    <article className="rounded-2xl border border-white bg-white/55 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-[15px] font-medium text-ink">{c.name}</h3>
        <span className="text-xs text-ink-3">{left ? `you approve the next ${left}` : 'runs on its own'}{c.schedule && ` · cron ${c.schedule}`}</span>
        <div className="ml-auto flex gap-2">
          <button type="button" onClick={() => act(() => api.runCrew(c.id))} className={smallBtn}><Play size={14} aria-hidden="true" />Run now</button>
          <button type="button" aria-label={`Delete ${c.name}`} onClick={() => { if (confirm(`Delete ${c.name}?`)) act(() => api.deleteCrew(c.id)) }} className={smallBtn}><Trash size={14} aria-hidden="true" /></button>
        </div>
      </div>
      <p className="mt-1 text-sm text-ink-2">{c.task}</p>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-2">{roleLine('Advisor', c.advisor)}{roleLine('Helper', c.helper)}{roleLine('Agent', c.agent)}</div>
      {err && <ErrorLine msg={err} />}
      {c.runs.slice(-3).reverse().map(r => (
        <details key={r.id} className="mt-3 rounded-xl border border-white bg-white/50 p-3" open={r.status === 'awaiting_approval'}>
          <summary className="cursor-pointer text-sm text-ink">{STATUS[r.status]}{r.step && ` · ${r.step}`}<span className="ml-2 text-xs text-ink-3">{timeAgo(r.at)}</span></summary>
          {r.error && <p role="alert" className="mt-2 text-sm text-bad">{r.error}</p>}
          {!!r.slides && <div className="mt-3 flex gap-2 overflow-x-auto">{Array.from({ length: r.slides }, (_, n) => (
            <img key={n} src={`/api/crews/${c.id}/runs/${r.id}/slide/${n}`} alt={`Slide ${n + 1}`} className="h-48 rounded-lg border border-white" loading="lazy" />))}</div>}
          {r.post && <p className="mt-3 whitespace-pre-wrap text-sm text-ink">{r.post.caption}</p>}
          {!r.post && r.output && <div className="mt-3"><Prose text={r.output} /></div>}
          {r.advice && <details className="mt-2"><summary className="cursor-pointer text-xs text-ink-3">Advisor</summary><Prose text={r.advice} /></details>}
          {r.help && <details className="mt-2"><summary className="cursor-pointer text-xs text-ink-3">Helper</summary><Prose text={r.help} /></details>}
        </details>
      ))}
    </article>
  )
}
