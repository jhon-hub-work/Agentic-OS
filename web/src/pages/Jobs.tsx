import { ArrowSquareOut, Check, Copy, MagnifyingGlass, X } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, timeAgo, type JobPost } from '../api'
import { ChromeButton, Empty, ErrorLine, PageHead, Panel, Skeleton, usePoll } from '../ui'

const is = (...s: JobPost['status'][]) => (j: JobPost) => s.includes(j.status)
const TABS: { id: string; label: string; match: (j: JobPost) => boolean }[] = [
  { id: 'todo', label: 'To approve', match: is('drafted') },
  { id: 'you', label: 'Needs you', match: is('needs_you', 'draft_blocked', 'handoff') },
  { id: 'follow', label: 'Follow up', match: j => j.status === 'applied' && j.follow_up === 'due' },
  { id: 'queued', label: 'Queued', match: is('queued', 'new') },
  { id: 'approved', label: 'Applying', match: is('approved', 'applying') },
  { id: 'done', label: 'Applied / replied / closed / skipped', match: is('applied', 'replied', 'closed', 'skipped') },
]

// Platform groups, matched on the job link. New platforms get a line here; empty ones (other than the first three) stay hidden.
const PLATFORMS: { id: string; label: string; host: RegExp }[] = [
  { id: 'olj', label: 'OnlineJobs', host: /onlinejobs\.ph/ },
  { id: 'reddit', label: 'Reddit', host: /reddit\.com/ },
  { id: 'indeed', label: 'Indeed', host: /indeed\.com/ },
  { id: 'x', label: 'X', host: /(^|\/\/|\.)(x|twitter)\.com/ },
  { id: 'instagram', label: 'Instagram', host: /instagram\.com/ },
  { id: 'facebook', label: 'Facebook', host: /facebook\.com|fb\.com/ },
  { id: 'linkedin', label: 'LinkedIn', host: /linkedin\.com/ },
]
const platformOf = (j: JobPost) => PLATFORMS.find(p => p.host.test(j.url))?.id ?? 'other'

// One tab per person the OS job-hunts for: the owner first, then config/owner.json "people". Each has their own board.
export default function Jobs() {
  const { data: people } = usePoll(api.jobPeople, 60000)
  const [who, setWho] = useState('')
  const list = people ?? [{ id: '', name: 'Me' }]
  return (
    <>
      <div className="mb-6 flex flex-wrap gap-2" role="tablist" aria-label="Whose job search">
        {list.map(p => (
          <button key={p.id} role="tab" aria-selected={who === p.id} onClick={() => setWho(p.id)}
            className={`min-h-12 cursor-pointer rounded-2xl border px-6 text-base font-medium ${who === p.id ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2'}`}>
            {p.id ? `For ${p.name}` : `For me (${p.name})`}
          </button>
        ))}
      </div>
      <Board key={who} who={who} person={list.find(p => p.id === who)} />
    </>
  )
}

function Board({ who, person }: { who: string; person?: { name: string; summary?: string } }) {
  const [platform, setPlatform] = useState('all')
  const { data, error } = usePoll(() => api.jobsBoard(who), 5000)
  const [tab, setTab] = useState('todo')
  const [starting, setStarting] = useState(false)
  const [link, setLink] = useState('')
  const [adding, setAdding] = useState(false)
  const [addMsg, setAddMsg] = useState('')
  const [runErr, setRunErr] = useState('')
  const run = data?.runs[0]

  async function addLink(e: React.FormEvent) {
    e.preventDefault()
    setAdding(true); setAddMsg('Reading it in Hoot\'s Brave, then scoring and drafting (about a minute)…')
    try {
      const j = await api.addIndeedJob(link.trim())
      setLink(''); setAddMsg(`Added: ${j.title} (score ${j.score?.toFixed(1) ?? '?'})`)
    } catch (err) { setAddMsg((err as Error).message) } finally { setAdding(false) }
  }
  const all = data?.jobs ?? []
  const inPlatform = all.filter(j => platform === 'all' || platformOf(j) === platform)
  const rows = inPlatform.filter(j => TABS.find(t => t.id === tab)!.match(j))
  const groups = [{ id: 'all', label: 'All' }, ...PLATFORMS, { id: 'other', label: 'Other boards' }]
    .map(p => ({ ...p, n: p.id === 'all' ? all.length : all.filter(j => platformOf(j) === p.id).length }))
    .filter((p, i) => i <= 3 || p.n > 0 || p.id === platform)

  async function runNow() {
    setStarting(true); setRunErr('')
    try { await api.runJobSearch(who) } catch (err) { setRunErr((err as Error).message) } finally { setStarting(false) }
  }

  return (
    <>
      <PageHead eyebrow={who ? `Jobs: ${person?.name ?? ''}` : 'Jobs: me'} title={who ? `${person?.name ?? ''}'s job search` : 'Job search'}
        sub={who ? `Bookkeeping roles on OnlineJobs.ph for ${person?.name ?? ''}: $8/h floor, Australian clients ranked first, same rules as yours. Clean drafts are sent from her own Brave; anything only she can do waits here.` : "Found on OnlineJobs.ph, company boards and public boards, filtered by Hoot's rules, scored by OpenCode, drafted by Claude. Nothing goes out until you Approve; then the OS applies like Hoot, or hands it to you when only you can finish."}
        action={<ChromeButton onClick={runNow} disabled={starting || data?.running} icon={<MagnifyingGlass size={16} weight="bold" aria-hidden="true" />}>{data?.running ? 'Searching' : 'Search now'}</ChromeButton>} />
      {error && <ErrorLine msg={error} />}
      {runErr && <p role="alert" className="mb-5 text-sm text-bad">Search didn't start: {runErr}</p>}
      {run && (
        <Panel className="mb-5" inner="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {[['Found', run.fetched], ['New', run.new], ['Passed rules', run.kept], ['Drafted', run.drafted]].map(([k, v]) => (
            <div key={k}><p className="text-xs text-ink-3">{k}</p><p className="font-mono text-2xl text-ink">{v}</p></div>
          ))}
          <p className="col-span-2 text-xs text-ink-3 sm:col-span-4">Last run {timeAgo(run.started)}{run.errors.length ? `, ${run.errors.length} source problem(s): ${run.errors.join('; ')}` : ''}</p>
        </Panel>
      )}
      <div className="mb-5 flex flex-wrap items-center gap-2 text-sm">
        <span className="text-ink-3">Export:</span>
        <a href={`/api/jobs/export.xlsx?who=${who}`} download className="inline-flex min-h-11 items-center rounded-full border border-white bg-white/70 px-4 text-ink hover:bg-white">Google Sheets (.xlsx)</a>
        <a href={`/api/jobs/export.html?who=${who}`} target="_blank" rel="noreferrer" className="inline-flex min-h-11 items-center rounded-full border border-white bg-white/70 px-4 text-ink hover:bg-white">PDF</a>
      </div>
      {!who && <form onSubmit={addLink} className="mb-5 flex flex-wrap items-center gap-2">
        <input value={link} onChange={e => setLink(e.target.value)} placeholder="Paste an Indeed job link"
          aria-label="Indeed job link" className="min-w-0 flex-1 rounded-full border border-white bg-white/55 px-4 py-2 text-sm text-ink" />
        <ChromeButton type="submit" disabled={adding || !link.trim()}>{adding ? 'Adding' : 'Add'}</ChromeButton>
        {addMsg && <p className="w-full text-xs text-ink-3">{addMsg}</p>}
      </form>}
      <div className="mb-3 flex flex-wrap gap-2" role="tablist" aria-label="Platform">
        {groups.map(p => (
          <button key={p.id} role="tab" aria-selected={platform === p.id} onClick={() => setPlatform(p.id)}
            className={`min-h-11 cursor-pointer rounded-full border px-4 text-sm ${platform === p.id ? 'border-navy text-navy bg-white' : 'border-white bg-white/60 text-ink-2'}`}>
            {p.label} <span className="font-mono">{p.n}</span>
          </button>
        ))}
      </div>
      <div className="mb-4 flex flex-wrap gap-2" role="tablist" aria-label="Status">
        {TABS.map(t => {
          const n = inPlatform.filter(t.match).length
          return (
            <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}
              className={`min-h-11 cursor-pointer rounded-full border px-4 text-sm ${tab === t.id ? 'border-navy bg-navy text-white' : 'border-white bg-white/60 text-ink-2'}`}>
              {t.label} <span className="font-mono">{n}</span>
            </button>
          )
        })}
      </div>
      {!data ? <Skeleton className="h-48" /> : rows.length === 0 ? (
        <Panel><Empty title="Nothing here" hint={data.running ? 'A search is running now.' : 'Run a search, or wait for the daily one.'} /></Panel>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">{rows.map(j => <JobCard key={j.id} j={j} who={who} />)}</div>
      )}
    </>
  )
}

function JobCard({ j, who }: { j: JobPost; who: string }) {
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const [err, setErr] = useState('')
  const act = async (d: 'approve' | 'skip' | 'applied' | 'replied' | 'followed_up') => {
    setBusy(true); setErr('')
    try { await api.decideJob(j.id, d, who) } catch (x) { setErr((x as Error).message) } finally { setBusy(false) }
  }
  const copy = async (t = j.draft) => { await navigator.clipboard.writeText(t); setCopied(true); setTimeout(() => setCopied(false), 1500) }
  const btn = 'inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:opacity-50'
  return (
    <Panel inner="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <a href={j.url} target="_blank" rel="noreferrer" className="flex items-center gap-1.5 text-[15px] font-medium text-ink hover:underline">
            <span className="truncate">{j.title}</span><ArrowSquareOut size={14} aria-hidden="true" className="shrink-0" />
          </a>
          <p className="text-sm text-ink-3">{j.company}, {j.source}, {j.pay || 'pay not stated'}</p>
        </div>
        {j.score != null && <span className="chrome shrink-0 rounded-full px-3 py-1 font-mono text-xs">{j.score.toFixed(1)}</span>}
      </div>
      {j.reason && <p className="text-sm text-ink-2">{j.reason}</p>}
      {j.status === 'needs_you' && <p className="text-sm text-warn">Needs you: {j.needs_why || 'a step only you can do'}</p>}
      {j.problem && <p className="text-sm text-bad">Draft held back: {j.problem}</p>}
      {j.status === 'handoff' && <p className="text-sm text-warn">Finish on PC: {j.apply_result}</p>}
      {j.status === 'applying' && <p className="text-sm text-ink-2">Applying now…</p>}
      {j.status === 'applied' && j.apply_result && <p className="text-sm text-ok">Sent</p>}
      {j.status === 'replied' && <p className="text-sm text-ok">They replied</p>}
      {j.follow_up === 'due' && j.status === 'applied' && <>
        <p className="text-sm text-warn">No reply in 48 h. {j.followup_problem ? `Not sent: ${j.followup_problem}. ` : ''}Send this follow-up:</p>
        {j.followup_draft && <p className="whitespace-pre-wrap rounded-2xl border border-white bg-white/55 p-3 text-sm text-ink">{j.followup_draft}</p>}
      </>}
      {j.draft && (
        <details className="rounded-2xl border border-white bg-white/55 p-3" open={j.status === 'drafted'}>
          <summary className="cursor-pointer text-xs text-ink-3">Draft</summary>
          <p className="mt-2 whitespace-pre-wrap text-sm leading-relaxed text-ink">{j.draft}</p>
        </details>
      )}
      <div className="flex flex-wrap gap-2">
        {j.draft && <button onClick={() => copy()} className={btn}><Copy size={14} aria-hidden="true" />{copied ? 'Copied' : 'Copy draft'}</button>}
        {['drafted', 'needs_you', 'draft_blocked', 'queued', 'new'].includes(j.status) && <>
          <button onClick={() => act('approve')} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />Approve</button>
          <button onClick={() => act('skip')} disabled={busy} className={btn}><X size={14} aria-hidden="true" />Skip</button>
        </>}
        {j.follow_up === 'due' && j.status === 'applied' && j.followup_draft && <button onClick={() => copy(j.followup_draft)} className={btn}><Copy size={14} aria-hidden="true" />Copy follow-up</button>}
        {j.follow_up === 'due' && j.status === 'applied' && <button onClick={() => act('followed_up')} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />I followed up</button>}
        {j.status === 'applied' && <button onClick={() => act('replied')} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />Got a reply</button>}
        {j.status === 'handoff' && <button onClick={() => act('applied')} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />I applied</button>}
      </div>
      {err && <p role="alert" className="text-sm text-bad">{err}</p>}
    </Panel>
  )
}
