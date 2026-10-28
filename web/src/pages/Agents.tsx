import { Plug, Plus } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { AGENT_META, api, type AgentName } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Field, PageHead, Panel, Skeleton, inputCls } from '../ui'

const AGENTS = Object.keys(AGENT_META) as AgentName[]

export default function Agents() {
  const [conns, setConns] = useState<Awaited<ReturnType<typeof api.connections>> | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const load = () => { setConns(null); api.connections().then(setConns).catch(e => setLoadError(e.message)) }
  useEffect(load, [])

  return (
    <>
      <PageHead eyebrow="Agents & tools" title="Models and connections" sub="Choose each agent's model, and connect MCP servers (tools like Notion, GitHub, a database) to the agents that should use them." />
      <Panel className="mb-5"><Relogin /></Panel>
      <Panel className="mb-5"><Models /></Panel>
      <Panel className="mb-5"><AddConnection onAdded={load} /></Panel>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {AGENTS.map(a => (
          <Panel key={a}>
            <div className="mb-3 flex items-center gap-3">
              <AgentLogo name={a} size={36} />
              <div><p className="text-[15px] font-medium text-ink">{AGENT_META[a].label}</p><p className="text-sm text-ink-3">MCP servers</p></div>
            </div>
            {loadError ? <p className="text-sm text-bad">{loadError}</p> : !conns ? <Skeleton className="h-24" /> : (
              <pre className="max-h-56 overflow-auto whitespace-pre-wrap rounded-2xl border border-white bg-white/55 p-3 font-mono text-xs leading-relaxed text-ink-2">{conns[a]?.text || 'None connected'}</pre>
            )}
          </Panel>
        ))}
      </div>
    </>
  )
}

function Models() {
  const [opts, setOpts] = useState<Record<string, string[]>>({})
  const [models, setModels] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    api.settings().then(s => setModels(s.models ?? {}))
    ;(['claude', 'opencode'] as const).forEach(a => api.models(a).then(r => {
      setOpts(o => ({ ...o, [a]: r.models }))
      setModels(m => ({ ...m, [a]: m[a] ?? r.current }))
    }))
  }, [])
  async function save(agent: string, model: string) {
    const next = { ...models, [agent]: model }
    setModels(next); setSaved(false)
    await api.saveModels(next); setSaved(true)
  }
  return (
    <div className="flex flex-col gap-4">
      <h2 className="text-sm font-medium text-ink-2">Models</h2>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {(['claude', 'opencode'] as const).map(a => (
          <Field key={a} id={`m-${a}`} label={a === 'claude' ? 'Claude Code (your subscription)' : 'OpenCode (Zen models)'}>
            {!opts[a] ? <Skeleton className="h-12" /> : (
              <select id={`m-${a}`} value={models[a] ?? ''} onChange={e => save(a, e.target.value)} className={inputCls}>
                {opts[a].map(m => <option key={m} value={m}>{m.replace('opencode/', '')}</option>)}
              </select>
            )}
          </Field>
        ))}
      </div>
      <p className="text-xs text-ink-3" aria-live="polite">{saved ? 'Saved. Used from the next message.' : 'Hermes and agy use the model set in their own apps.'}</p>
    </div>
  )
}

function AddConnection({ onAdded }: { onAdded: () => void }) {
  const [name, setName] = useState('')
  const [target, setTarget] = useState('')
  const [agents, setAgents] = useState<AgentName[]>(['claude'])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [results, setResults] = useState<Record<string, { ok: boolean; text: string }> | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim() || !target.trim()) { setError('Give a name and a URL or command.'); return }
    if (agents.length === 0) { setError('Pick at least one agent.'); return }
    const isUrl = /^https?:\/\//.test(target.trim())
    setBusy(true); setError(null); setResults(null)
    try {
      setResults(await api.addConnection({ name: name.trim(), url: isUrl ? target.trim() : '', command: isUrl ? '' : target.trim(), agents }))
      onAdded()
    } catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <h2 className="flex items-center gap-2 text-sm font-medium text-ink-2"><Plug size={18} weight="light" aria-hidden="true" /> Connect an MCP server</h2>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-[1fr_2fr]">
        <Field id="c-name" label="Name"><input id="c-name" value={name} onChange={e => setName(e.target.value)} className={inputCls} placeholder="github" /></Field>
        <Field id="c-target" label="URL or command" help="A URL (https://...) for a remote server, or a command such as npx -y @modelcontextprotocol/server-filesystem C:\Projects">
          <input id="c-target" value={target} onChange={e => setTarget(e.target.value)} className={`${inputCls} font-mono text-sm`} placeholder="https://mcp.example.com/mcp" />
        </Field>
      </div>
      <AgentPicker multi legend="Add it to" value={agents} onChange={setAgents} />
      {error && <p role="alert" className="text-sm text-bad">{error}</p>}
      {results && (
        <ul className="flex flex-col gap-1 text-sm">
          {Object.entries(results).map(([a, r]) => <li key={a} className={r.ok ? 'text-ok' : 'text-bad'}>{AGENT_META[a as AgentName]?.label}: {r.ok ? 'added' : `failed: ${r.text}`}</li>)}
        </ul>
      )}
      <div><ChromeButton type="submit" disabled={busy} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{busy ? 'Connecting' : 'Connect'}</ChromeButton></div>
    </form>
  )
}

type Login = { id: string; label: string; last: string; due: string; overdue: boolean }

// Weekly re-login for security: a button opens a terminal on the PC that logs the account out and starts its login.
function Relogin() {
  const [accounts, setAccounts] = useState<Login[] | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const post = (path: string) => fetch(path, { method: 'POST' }).then(r => r.json())
  useEffect(() => { fetch('/api/reauth').then(r => r.json()).then(d => setAccounts(d.accounts)).catch(() => {}) }, [])
  const day = (iso: string) => new Date(iso).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
  return (
    <div>
      <h2 className="mb-1 text-lg font-medium tracking-tight text-ink">Weekly re-login</h2>
      <p className="mb-4 text-sm text-ink-3">For security, log out and back in once a week. Tasks using that account pause until you finish logging in.</p>
      {!accounts ? <Skeleton className="h-16" /> : (
        <ul className="flex flex-col gap-3">
          {accounts.map(a => (
            <li key={a.id} className="flex flex-wrap items-center gap-3 rounded-2xl border border-white bg-white/50 px-4 py-3">
              <span className="font-medium text-ink">{a.label}</span>
              <span className={`text-xs ${a.overdue ? 'text-bad' : 'text-ink-3'}`}>{a.overdue ? `Due since ${day(a.due)}` : `Next: ${day(a.due)}`} · last {day(a.last)}</span>
              <span className="ml-auto flex gap-2">
                <button type="button" onClick={() => post(`/api/reauth/${a.id}/start`).then(() => setMsg(`A window opened on the PC for ${a.label}. Log in there, then tap Done.`))}
                  className="chrome min-h-10 cursor-pointer rounded-full px-4 text-sm font-medium">Log out &amp; in</button>
                <button type="button" onClick={() => post(`/api/reauth/${a.id}/done`).then(d => { setAccounts(d.accounts); setMsg(null) })}
                  className="min-h-10 cursor-pointer rounded-full border border-white bg-white/60 px-4 text-sm text-ink hover:bg-white">Done</button>
              </span>
            </li>
          ))}
        </ul>
      )}
      {msg && <p role="status" className="mt-3 text-sm text-ink-2">{msg}</p>}
    </div>
  )
}
