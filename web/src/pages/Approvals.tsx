import { ArrowSquareOut, Briefcase, Check, Lightning, Ticket, Waveform, X, type Icon } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, timeAgo, type Approval } from '../api'
import { Empty, ErrorLine, PageHead, Panel, Skeleton, inputCls, usePoll } from '../ui'

// Per kind: icon, and what Approve / Deny mean there (the source's own words).
const KIND: Record<Approval['kind'], { icon: Icon; label: string; yes: string; no: string }> = {
  ticket: { icon: Ticket, label: 'Ticket plan', yes: 'Approve plan', no: 'Reject with note' },
  job: { icon: Briefcase, label: 'Job application', yes: 'Approve and send', no: 'Skip' },
  jharvis: { icon: Waveform, label: 'Jharvis action', yes: 'Approve', no: 'Deny' },
  skill: { icon: Lightning, label: 'Skill install', yes: 'Install anyway', no: 'Don\'t install' },
}
const btn = 'inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:cursor-not-allowed disabled:opacity-50'

export default function Approvals() {
  const { data, error, reload } = usePoll(api.approvals, 4000)
  return (
    <>
      <PageHead eyebrow="Approvals" title="Waiting for your OK"
        sub="Everything your agents want to do that changes something outside: sends, posts, applications, installs, file changes. Nothing here happens until you approve it." />
      {error && <ErrorLine msg={error} />}
      {!data ? <Skeleton className="h-48" /> : data.approvals.length === 0
        ? <Panel><Empty title="Nothing waiting" hint="When an agent asks before acting, it shows up here." /></Panel>
        : <div className="flex flex-col gap-4">{data.approvals.map(a => <Item key={a.id} a={a} onDone={reload} />)}</div>}
    </>
  )
}

export function Item({ a, onDone }: { a: Approval; onDone: () => void }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [note, setNote] = useState('')
  const k = KIND[a.kind]
  const needsNote = a.kind === 'ticket'  // a rejected plan is planned again: Claude needs the why
  const decide = async (d: 'approve' | 'deny') => {
    setBusy(true); setErr('')
    try { await api.decideApproval(a.id, d, note.trim()); onDone() } catch (x) { setErr((x as Error).message); setBusy(false) }
  }
  return (
    <Panel inner="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2 text-xs text-ink-3">
        <span className="inline-flex items-center gap-1.5 rounded-full border border-white bg-white/60 px-2.5 py-1 font-medium text-ink-2">
          <k.icon size={14} aria-hidden="true" />{k.label}
        </span>
        <span>{a.agent}</span>
        {a.created && <span>· {timeAgo(a.created)}</span>}
        {a.expires && <span className="text-warn">· answer before {new Date(a.expires).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}</span>}
        <a href={a.url || a.link} {...(a.url ? { target: '_blank', rel: 'noreferrer' } : {})}
          className="ml-auto inline-flex min-h-8 items-center gap-1 text-ink-2 hover:text-ink hover:underline">
          Open<ArrowSquareOut size={13} aria-hidden="true" />
        </a>
      </div>
      <h2 className="text-[15px] font-medium text-ink">{a.title}</h2>
      {a.detail && (
        <details className="rounded-2xl border border-white bg-white/55 p-3" open={a.detail.length < 400}>
          <summary className="cursor-pointer text-xs text-ink-3">Details</summary>
          <p className="mt-2 max-h-72 overflow-y-auto whitespace-pre-wrap break-words text-sm leading-relaxed text-ink">{a.detail}</p>
        </details>
      )}
      {needsNote && (
        <input value={note} onChange={e => setNote(e.target.value)} aria-label="Note for Claude"
          placeholder="Note for Claude (needed to reject)" className={inputCls} />
      )}
      <div className="flex flex-wrap gap-2">
        <button onClick={() => decide('approve')} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />{k.yes}</button>
        <button onClick={() => decide('deny')} disabled={busy || (needsNote && !note.trim())} className={btn}><X size={14} aria-hidden="true" />{k.no}</button>
      </div>
      {err && <ErrorLine msg={err} />}
    </Panel>
  )
}
