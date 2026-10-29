import { Check, Paperclip, Plus, X } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { api, timeAgo, type Ticket, type TicketCategory, type TicketSeverity } from '../api'
import { ChromeButton, Empty, ErrorLine, Field, PageHead, Panel, Prose, Skeleton, inputCls, usePoll } from '../ui'

const SEV_CLS: Record<TicketSeverity, string> = { P1: 'border-bad text-bad', P2: 'border-warn text-warn', P3: 'border-navy text-navy', P4: 'border-ink-3 text-ink-3' }
const STATUS_LABEL: Record<Ticket['status'], string> = {
  new: 'New', planning: 'Planning', awaiting_approval: 'Awaiting your approval', approved: 'Approved',
  in_progress: 'Fixing', waiting_on_you: 'Waiting on you', resolved: 'Resolved', closed: 'Closed',
}
const btn = 'inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:opacity-50'
const IMG = /\.(png|jpe?g|gif|webp)$/i
/** Screenshots pasted with Ctrl+V become files; named by time so two pastes don't clash. */
const pasted = (e: React.ClipboardEvent) => [...e.clipboardData.files].filter(f => f.type.startsWith('image/'))
  .map((f, i) => new File([f], `screenshot-${Date.now()}-${i}.${f.type.split('/')[1] || 'png'}`, { type: f.type }))
const isOpen = (t: Ticket) => t.status !== 'resolved' && t.status !== 'closed'

/** "due in 3 h" / "5 h late"; red once past. */
function Due({ t }: { t: Ticket }) {
  if (!isOpen(t)) return <span className="text-xs text-ink-3">{STATUS_LABEL[t.status]} {timeAgo(t.updated)}</span>
  const h = (new Date(t.due).getTime() - Date.now()) / 3600000
  const span = Math.abs(h) < 48 ? `${Math.round(Math.abs(h))} h` : `${Math.round(Math.abs(h) / 24)} d`
  return <span className={`text-xs ${h < 0 ? 'font-medium text-bad' : 'text-ink-3'}`}>{h < 0 ? `${span} late` : `due in ${span}`}</span>
}

export default function Tickets() {
  const { data, error, reload } = usePoll(api.tickets, 5000)
  const [sel, setSel] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [showDone, setShowDone] = useState(false)
  const all = data?.tickets ?? []
  const rows = all.filter(t => showDone || isOpen(t))
  const t = all.find(x => x.id === sel)

  return (
    <>
      <PageHead eyebrow="Tickets" title="IT tickets"
        sub="Open a ticket; Claude picks it up by severity, writes a plan, and waits for your Approve before it changes anything."
        action={<ChromeButton onClick={() => setCreating(c => !c)} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>New ticket</ChromeButton>} />
      {error && <ErrorLine msg={error} />}
      <Review />
      {creating && <NewTicket sla={data?.sla} onDone={id => { setCreating(false); setSel(id); reload() }} />}
      <label className="mb-4 flex items-center gap-2 text-sm text-ink-2">
        <input type="checkbox" checked={showDone} onChange={e => setShowDone(e.target.checked)} className="h-4 w-4" />
        Show resolved and closed
      </label>
      {!data ? <Skeleton className="h-48" /> : (
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <div className="flex flex-col gap-2">
            {rows.length === 0 ? <Panel><Empty title="No open tickets" hint="Open one with New ticket." /></Panel> : rows.map(x => (
              <button key={x.id} onClick={() => setSel(x.id)} aria-pressed={sel === x.id}
                className={`flex cursor-pointer flex-col gap-1 rounded-2xl border p-4 text-left transition-colors ${sel === x.id ? 'border-navy bg-white/85' : 'border-white bg-white/55 hover:bg-white/75'}`}>
                <span className="flex items-center gap-2">
                  <span className={`rounded-full border px-2 font-mono text-[11px] ${SEV_CLS[x.severity]}`}>{x.severity}</span>
                  <span className="font-mono text-xs text-ink-3">{x.id}</span>
                  <span className="ml-auto"><Due t={x} /></span>
                </span>
                <span className="truncate text-[15px] font-medium text-ink">{x.title}</span>
                <span className="text-xs text-ink-2">{STATUS_LABEL[x.status]}, {x.category}</span>
              </button>
            ))}
          </div>
          {t ? <Detail key={t.id} t={t} reload={reload} /> : <Panel><Empty title="Pick a ticket" hint="Its plan, result and history show here." /></Panel>}
        </div>
      )}
    </>
  )
}

function NewTicket({ sla, onDone }: { sla?: Record<TicketSeverity, [number, number]>; onDone: (id: string) => void }) {
  const [f, setF] = useState({ title: '', description: '', category: 'bug' as TicketCategory, severity: 'P3' as TicketSeverity, project: '' })
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [files, setFiles] = useState<File[]>([])
  const [tid, setTid] = useState<string | null>(null)  // set once the ticket exists, so a retry after a failed upload doesn't open a second one
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value })
  async function submit(e: React.FormEvent) {
    e.preventDefault(); setErr(null)
    const bad = files.find(x => !x.size || x.size > 10 * 1024 * 1024)
    if (bad) { setErr(`${bad.name} is empty or over 10 MB. Remove it first.`); return }
    if (files.length > 10) { setErr('Up to 10 files per ticket.'); return }
    setBusy(true)
    let id = tid
    try {
      if (!id) { id = (await api.createTicket(f)).id; setTid(id) }
      let left = files
      for (const file of files) { await api.attachTicketFile(id, file); left = left.slice(1); setFiles(left) }  // uploaded ones leave the list
      onDone(id)
    } catch (x) {
      setErr(id ? `Ticket ${id} is open, but a file didn't upload: ${(x as Error).message}. Press Open again to send the files still listed.` : (x as Error).message)
    } finally { setBusy(false) }
  }
  const s = sla?.[f.severity]
  return (
    <Panel className="mb-5">
      <form onSubmit={submit} className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="md:col-span-2"><Field id="t-title" label="Title"><input id="t-title" required maxLength={160} value={f.title} onChange={set('title')} className={inputCls} /></Field></div>
        <div className="md:col-span-2"><Field id="t-desc" label="Description" help="What's wrong, where you saw it, what you expected. Paste a screenshot here with Ctrl+V.">
          <textarea id="t-desc" rows={4} value={f.description} onChange={set('description')} onPaste={e => setFiles(x => [...x, ...pasted(e)])} className={inputCls} /></Field></div>
        <div className="md:col-span-2"><Files files={files} setFiles={setFiles} /></div>
        <Field id="t-cat" label="Category"><select id="t-cat" value={f.category} onChange={set('category')} className={inputCls}>
          {['incident', 'bug', 'request', 'change'].map(c => <option key={c}>{c}</option>)}</select></Field>
        <Field id="t-sev" label="Severity" help={s ? `Picked up within ${s[0]} h, due in ${s[1]} h` : undefined}>
          <select id="t-sev" value={f.severity} onChange={set('severity')} className={inputCls}>
            {['P1', 'P2', 'P3', 'P4'].map(c => <option key={c}>{c}</option>)}</select></Field>
        <div className="md:col-span-2"><Field id="t-proj" label="Project folder" help="Where Claude works. Empty = the Agentic OS itself." error={err}>
          <input id="t-proj" value={f.project} onChange={set('project')} placeholder="D:\Work\Claude\..." className={inputCls} /></Field></div>
        <div className="md:col-span-2 flex justify-end"><ChromeButton type="submit" disabled={busy || !f.title.trim()}>{busy ? 'Opening' : 'Open ticket'}</ChromeButton></div>
      </form>
    </Panel>
  )
}

function Detail({ t, reload }: { t: Ticket; reload: () => void }) {
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const act = async (fn: () => Promise<unknown>, clear = false) => {
    setBusy(true); setErr(null)
    try { await fn(); if (clear) setNote(''); reload() } catch (x) { setErr((x as Error).message) } finally { setBusy(false) }
  }
  const waiting = t.status === 'awaiting_approval'
  return (
    <Panel inner="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded-full border px-2 font-mono text-xs ${SEV_CLS[t.severity]}`}>{t.severity}</span>
        <span className="font-mono text-sm text-ink-3">{t.id}</span>
        <span className="text-sm text-ink-2">{STATUS_LABEL[t.status]}, {t.category}</span>
        <span className="ml-auto"><Due t={t} /></span>
      </div>
      <h2 className="text-xl font-medium text-ink">{t.title}</h2>
      {t.description && <p className="whitespace-pre-wrap text-sm text-ink-2">{t.description}</p>}
      <Attachments t={t} reload={reload} />
      <p className="text-xs text-ink-3 [overflow-wrap:anywhere]">Folder: {t.project}, opened {timeAgo(t.created)}</p>
      {(t.status === 'planning' || t.status === 'in_progress') && <p className="text-sm text-ink-2">Claude is working on it now…</p>}
      {t.plan && (
        <details open={waiting} className="rounded-2xl border border-white bg-white/55 p-4">
          <summary className="cursor-pointer text-sm font-medium text-ink">Claude's plan</summary>
          <div className="mt-3"><Prose text={t.plan} /></div>
        </details>
      )}
      {t.result && (
        <details open={t.status === 'waiting_on_you' || t.status === 'resolved'} className="rounded-2xl border border-white bg-white/55 p-4">
          <summary className="cursor-pointer text-sm font-medium text-ink">Result</summary>
          <div className="mt-3"><Prose text={t.result} /></div>
        </details>
      )}
      <textarea rows={2} value={note} onChange={e => setNote(e.target.value)} aria-label={waiting ? 'Note for Claude' : 'Comment'}
        placeholder={waiting ? 'Note for Claude (needed to reject)' : 'Add a comment'} className={inputCls} />
      <div className="flex flex-wrap gap-2">
        {waiting && <>
          <button onClick={() => act(() => api.approveTicket(t.id))} disabled={busy} className={btn}><Check size={14} aria-hidden="true" />Approve plan</button>
          <button onClick={() => act(() => api.rejectTicket(t.id, note), true)} disabled={busy || !note.trim()} className={btn}><X size={14} aria-hidden="true" />Reject with note</button>
        </>}
        <button onClick={() => act(() => api.commentTicket(t.id, note), true)} disabled={busy || !note.trim()} className={btn}>Comment</button>
        {t.status !== 'closed'
          ? <button onClick={() => act(() => api.patchTicket(t.id, { status: 'closed' }))} disabled={busy} className={btn}>Close</button>
          : <button onClick={() => act(() => api.patchTicket(t.id, { status: 'new' }))} disabled={busy} className={btn}>Reopen</button>}
        {t.status === 'resolved' && <button onClick={() => act(() => api.patchTicket(t.id, { status: 'new' }))} disabled={busy} className={btn}>Reopen</button>}
      </div>
      {err && <p role="alert" className="text-sm text-bad">{err}</p>}
      <Changes t={t} reload={reload} />
      <div>
        <p className="mb-2 text-sm font-medium text-ink">Activity</p>
        <ol className="flex flex-col gap-2">
          {[...t.log].reverse().map((l, i) => (
            <li key={i} className="text-sm"><span className="text-ink-3">{timeAgo(l.at)}, {l.who}:</span> <span className="whitespace-pre-wrap text-ink-2">{l.text}</span></li>
          ))}
        </ol>
      </div>
    </Panel>
  )
}

/** Files picked or pasted before the ticket exists; they upload right after it opens. */
function Files({ files, setFiles }: { files: File[]; setFiles: (f: File[]) => void }) {
  return (
    <div className="flex flex-col gap-2">
      <label className={`${btn} w-fit`}>
        <Paperclip size={14} aria-hidden="true" />Attach files or screenshots
        <input type="file" multiple className="sr-only" onChange={e => { setFiles([...files, ...e.target.files!]); e.target.value = '' }} />
      </label>
      {files.length > 0 && <ul className="flex flex-wrap gap-2">
        {files.map((f, i) => (
          <li key={i} className="flex items-center gap-1 rounded-full border border-white bg-white/70 py-1 pl-3 pr-1 text-xs text-ink-2">
            {f.name}<button type="button" aria-label={`Remove ${f.name}`} onClick={() => setFiles(files.filter((_, j) => j !== i))}
              className="flex h-6 w-6 cursor-pointer items-center justify-center rounded-full hover:bg-white"><X size={12} aria-hidden="true" /></button>
          </li>
        ))}
      </ul>}
      <p className="text-xs text-ink-3">Up to 10 files, 10 MB each. Claude opens them while planning and fixing.</p>
    </div>
  )
}

function Attachments({ t, reload }: { t: Ticket; reload: () => void }) {
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const add = async (list: File[]) => {
    setBusy(true); setErr(null)
    try { for (const f of list) await api.attachTicketFile(t.id, f); reload() } catch (x) { setErr((x as Error).message) } finally { setBusy(false) }
  }
  const url = (f: string) => `/api/tickets/${t.id}/files/${encodeURIComponent(f)}`
  return (
    <div className="flex flex-col gap-2" onPaste={e => { const p = pasted(e); if (p.length) add(p) }}>
      {(t.files ?? []).length > 0 && <div className="flex flex-wrap gap-2">
        {t.files!.map(f => IMG.test(f)
          ? <a key={f} href={url(f)} target="_blank" rel="noreferrer" title={f}><img src={url(f)} alt={f} className="h-24 w-auto max-w-40 rounded-xl border border-white object-cover" /></a>
          : <a key={f} href={url(f)} download className="inline-flex min-h-11 items-center gap-1 rounded-full border border-white bg-white/70 px-3 text-xs text-ink hover:bg-white"><Paperclip size={12} aria-hidden="true" />{f}</a>)}
      </div>}
      <label className={`${btn} w-fit ${busy ? 'opacity-50' : ''}`}>
        <Paperclip size={14} aria-hidden="true" />{busy ? 'Uploading' : 'Add file'}
        <input type="file" multiple disabled={busy} className="sr-only" onChange={e => { add([...e.target.files!]); e.target.value = '' }} />
      </label>
      {err && <p role="alert" className="text-sm text-bad">{err}</p>}
    </div>
  )
}

/** Files the fix changed (from the change journal), with one Undo for all of them. */
function Changes({ t, reload }: { t: Ticket; reload: () => void }) {
  const [list, setList] = useState<Awaited<ReturnType<typeof api.ticketChanges>>['changes']>([])
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { api.ticketChanges(t.id).then(r => setList(r.changes)).catch(() => {}) }, [t.id, t.status])
  if (!list.length) return null
  async function undo() {
    if (!confirm(`Put ${list.length} file(s) back as they were before the fix?`)) return
    setBusy(true); setErr(null)
    try { await api.undoTicket(t.id); setList([]); reload() } catch (x) { setErr((x as Error).message) } finally { setBusy(false) }
  }
  return (
    <div className="rounded-2xl border border-white bg-white/55 p-4">
      <p className="mb-2 text-sm font-medium text-ink">Files the fix changed</p>
      <ul className="mb-3 flex flex-col gap-1">
        {list.map(c => (
          <li key={c.file} className="text-xs text-ink-2 [overflow-wrap:anywhere]">
            {c.before === null ? 'new: ' : ''}{c.file}{c.changed_since && <span className="text-warn"> (edited again since)</span>}
          </li>
        ))}
      </ul>
      <button onClick={undo} disabled={busy} className={btn}>{busy ? 'Undoing' : 'Undo the fix'}</button>
      {err && <p role="alert" className="mt-2 text-sm text-bad">{err}</p>}
    </div>
  )
}

/** Once a day Claude reads the OS's logs and opens tickets for friction it finds; nothing changes without Approve. */
function Review() {
  const { data, reload } = usePoll(api.selfReview, 15000)
  const [err, setErr] = useState('')
  const run = async () => { setErr(''); try { await api.runSelfReview(); reload() } catch (x) { setErr((x as Error).message) } }
  const running = data?.running && data.last && Date.now() - new Date(data.last).getTime() < 1800000
  return (
    <div className="mb-5 flex flex-wrap items-center gap-3 text-sm text-ink-2">
      <span>Daily self-review: {running ? 'running now…' : data?.last
        ? `last ${timeAgo(data.last)}, ${data.problem ? `failed (${data.problem.slice(0, 120)})` : `${data.opened?.length ?? 0} ticket(s) opened`}`
        : 'not run yet'}</span>
      <button onClick={run} disabled={!!running} className={btn}>Review now</button>
      {err && <span role="alert" className="text-bad">{err}</span>}
    </div>
  )
}
