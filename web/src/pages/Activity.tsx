import { api, timeAgo } from '../api'
import { Empty, ErrorLine, PageHead, Panel, Skeleton, usePoll } from '../ui'

export default function Activity() {
  const { data, error } = usePoll(() => api.audit(150), 6000)
  const rows = [...(data?.entries ?? [])].reverse()
  return (
    <>
      <PageHead eyebrow="Activity" title="Everything that happened" sub="The audit log: every chat, skill run, team run and scheduled job." />
      {error && <ErrorLine msg={error} />}
      <Panel>
        {!data ? <Skeleton className="h-64" /> : rows.length === 0 ? <Empty title="Nothing yet" hint="Activity appears as soon as an agent does something." /> : (
          <ul className="divide-y divide-white/80">
            {rows.map((e, i) => (
              <li key={e.id ?? i} className="flex flex-col gap-1 py-3 sm:flex-row sm:items-baseline sm:justify-between sm:gap-6">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-ink">{e.action.replace(/_/g, ' ')}{e.agent ? ` · ${e.agent}` : ''}{e.skill ? ` · ${e.skill}` : ''}</p>
                  {(e.msg_preview || e.output_preview) && <p className="truncate text-sm text-ink-3">{e.msg_preview || e.output_preview}</p>}
                </div>
                <span className="shrink-0 font-mono text-xs text-ink-3">{timeAgo(e.timestamp)}</span>
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </>
  )
}
