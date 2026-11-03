import { useState, type ChangeEvent } from 'react'
import { api } from '../api'
import { Empty, ErrorLine, PageHead, Panel, Skeleton, inputCls, usePoll } from '../ui'

// Jhon, 3 Nov ("Ask + dashboard page"): every tool call Jharvis made, newest first, from his own audit log
const DAYS = [['today', 'Today'], ['yesterday', 'Yesterday'], ['', 'Last 90 days']] as const
const STATUSES = [['', 'Any result'], ['ran', 'Ran'], ['denied', 'Denied'], ['failed', 'Failed'], ['stopped', 'Stopped']] as const
const TONE: Record<string, string> = { ran: 'text-ink-2', denied: 'text-warn', failed: 'text-warn', stopped: 'text-ink-3' }

export default function JharvisLog() {
  const [day, setDay] = useState('today')
  const [status, setStatus] = useState('')
  const { data, error, reload } = usePoll(() => api.jharvisAudit(day, status), 8000)
  const pick = (set: (v: string) => void) => (e: ChangeEvent<HTMLSelectElement>) => { set(e.target.value); reload() }
  return (
    <>
      <PageHead eyebrow="Jharvis log" title="What Jharvis ran"
        sub="Every tool Jharvis used, how it was allowed, and how it ended. Messages, emails and anything about the clinic are never kept here." />
      <div className="mb-4 flex flex-wrap gap-2">
        <select aria-label="Day" value={day} onChange={pick(setDay)} className={`${inputCls} w-auto`}>
          {DAYS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
        <select aria-label="Result" value={status} onChange={pick(setStatus)} className={`${inputCls} w-auto`}>
          {STATUSES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
      </div>
      {error && <ErrorLine msg={error} />}
      <Panel>
        {!data ? <Skeleton className="h-64" /> : data.off ? <Empty title="Jharvis is off" hint="His log shows here while he's running." />
          : data.rows.length === 0 ? <Empty title="Nothing here" hint="Try another day or result." /> : (
          <ul className="divide-y divide-white/80">
            {data.rows.map((r, i) => (
              <li key={`${r.at}-${i}`} className="flex flex-col gap-1 py-3 sm:flex-row sm:items-baseline sm:justify-between sm:gap-6">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-ink">
                    {r.tool.replace(/_/g, ' ')} <span className={TONE[r.status] ?? 'text-ink-2'}>{r.status}</span>
                    <span className="font-normal text-ink-3">, {r.how}</span>
                  </p>
                  {r.args && <p className="truncate font-mono text-xs text-ink-3">{r.args}</p>}
                  {r.result && <p className="truncate text-sm text-ink-3">{r.result}</p>}
                </div>
                <span className="shrink-0 text-xs text-ink-3">
                  {r.surface}{r.who && r.who !== 'Jhon' ? ` · ${r.who}` : ''} · {new Date(r.at * 1000).toLocaleString([], { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </>
  )
}
