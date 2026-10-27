import { ArrowSquareOut, Check, Copy, MagnifyingGlass, X } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, timeAgo, type JobPost } from '../api'
import { ChromeButton, Empty, ErrorLine, PageHead, Panel, Skeleton, usePoll } from '../ui'

const TABS: { id: string; label: string; match: JobPost['status'][] }[] = [
  { id: 'todo', label: 'To approve', match: ['drafted'] },
  { id: 'you', label: 'Needs you', match: ['needs_you', 'draft_blocked', 'handoff'] },
  { id: 'queued', label: 'Queued', match: ['queued', 'new'] },
  { id: 'approved', label: 'Applying', match: ['approved', 'applying'] },
  { id: 'done', label: 'Applied / closed / skipped', match: ['applied', 'closed', 'skipped'] },
]

export default function Jobs() {
  const { data, error } = usePoll(api.jobsBoard, 5000)
  const [tab, setTab] = useState('todo')
  const [starting, setStarting] = useState(false)
  const run = data?.runs[0]
  const rows = (data?.jobs ?? []).filter(j => TABS.find(t => t.id === tab)!.match.includes(j.status))

  async function runNow() {
    setStarting(true)
    try { await api.runJobSearch() } finally { setStarting(false) }
  }

  return (
    <>
      <PageHead eyebrow="Jobs" title="Job search"
        sub="Found on OnlineJobs.ph, company boards and public boards, filtered by Hoot's rules, scored by OpenCode, drafted by Claude. Nothing goes out until you Approve; then the OS applies like Hoot, or hands it to you when only you can finish."
        action={<ChromeButton onClick={runNow} disabled={starting || data?.running} icon={<MagnifyingGlass size={16} weight="bold" aria-hidden="true" />}>{data?.running ? 'Searching' : 'Search now'}</ChromeButton>} />
      {error && <ErrorLine msg={error} />}
      {run && (
        <Panel className="mb-5" inner="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {[['Found', run.fetched], ['New', run.new], ['Passed rules', run.kept], ['Drafted', run.drafted]].map(([k, v]) => (
            <div key={k}><p className="text-xs text-ink-3">{k}</p><p className="font-mono text-2xl text-ink">{v}</p></div>
          ))}
          <p className="col-span-2 text-xs text-ink-3 sm:col-span-4">Last run {timeAgo(run.started)}{run.errors.length ? ` · ${run.errors.length} source problem(s): ${run.errors.join('; ')}` : ''}</p>
        </Panel>
      )}
      <div className="mb-4 flex flex-wrap gap-2" role="tablist">
        {TABS.map(t => {
          const n = (data?.jobs ?? []).filter(j => t.match.includes(j.status)).length
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
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">{rows.map(j => <JobCard key={j.id} j={j} />)}</div>
      )}
    </>
  )
}

function JobCard({ j }: { j: JobPost }) {
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const act = async (d: 'approve' | 'skip' | 'applied') => { setBusy(true); try { await api.decideJob(j.id, d) } finally { setBusy(false) } }
  const copy = async () => { await navigator.clipboard.writeText(j.draft); setCopied(true); setTimeout(() => setCopied(false), 1500) }
  const btn = 'inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:opacity-50'
  return (
    <Panel inner="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <a href={j.url} target="_blank" rel="noreferrer" className="flex items-center gap-1.5 text-[15px] font-medium text-ink hover:underline">
            <span className="truncate">{j.title}</span><ArrowSquareOut size={14} aria-hidden="true" className="shrink-0" />
          </a>
          <p className="text-sm text-ink-3">{j.company} · {j.source} · {j.pay || 'pay not stated'}</p>
        </div>
        {j.score != null && <span className="chrome shrink-0 rounded-full px-3 py-1 font-mono text-xs">{j.score.toFixed(1)}</span>}
      </div>
      {j.reason && <p className="text-sm text-ink-2">{j.reason}</p>}
      {j.status === 'needs_you' && <p className="text-sm text-warn">Needs you: {j.needs_why || 'a step only you can do'}</p>}
      {j.problem && <p className="text-sm text-bad">Draft held back: {j.problem}</p>}
      {j.status === 'handoff' && <p className="text-sm text-warn">Finish on PC: {j.apply_result}</p>}
      {j.status === 'applying' && <p className="text-sm text-ink-2">Applying now…</p>}
      {j.status === 'applied' && j.apply_result && <p className="text-sm text-ok">Sent</p>}
      {j.draft && (
        <details className="rounded-2xl border border-white bg-white/55 p-3" open={j.status === 'drafted'}>
          <summary className="cursor-pointer text-xs text-ink-3">Draft</summary>
          <p className="mt-2 whitespace-pre-wrap text-sm leading-relaxed text-ink">{j.draft}</p>
        </details>
      )}
      <div className="flex flex-wrap gap-2">
        {j.draft && <button onClick={copy} className={btn}><Copy size={14} aria-hidden="true" />{copied ? 'Copied' : 'Copy draft'}</button>}
        {['drafted', 'needs_you', 'draft_blocked', 'queued', 'new'].includes(j.status) && <>
          <button onClick={() => act('approve')} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />Approve</button>
          <button onClick={() => act('skip')} disabled={busy} className={btn}><X size={14} aria-hidden="true" />Skip</button>
        </>}
        {j.status === 'handoff' && <button onClick={() => act('applied')} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />I applied</button>}
      </div>
    </Panel>
  )
}
