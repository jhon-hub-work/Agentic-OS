import { Plug, Plus } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { AGENT_META, api, loadAgents, type AgentName, type AgentProvider, type CatalogAgent, type ModelChoice, type Plugin } from '../api'
import { AgentLogo, AgentPicker, ChromeButton, Field, ModelPick, PageHead, Panel, Skeleton, inputCls, useModelInfo } from '../ui'
import { PairPhone } from '../Phone'

const MCP_AGENTS = ['claude', 'opencode', 'hermes', 'agy']  // added agents have no MCP command

export default function Agents() {
  const [conns, setConns] = useState<Awaited<ReturnType<typeof api.connections>> | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const load = () => { setConns(null); api.connections().then(setConns).catch(e => setLoadError(e.message)) }
  useEffect(load, [])

  return (
    <>
      <PageHead eyebrow="Agents & tools" title="Models and connections" sub="Choose each agent's model, and connect MCP servers (tools like Notion, GitHub, a database) to the agents that should use them." />
      <Panel className="mb-5"><Keys /></Panel>
      <Panel className="mb-5"><Relogin /></Panel>
      <Panel className="mb-5"><MyAgents /></Panel>
      <Panel className="mb-5"><Models /></Panel>
      <Panel className="mb-5"><Plugins /></Panel>
      <Panel className="mb-5"><AddConnection onAdded={load} /></Panel>
      {/* the QR carries the token: only on the PC's own screen (the server refuses anyone else too) */}
      {['localhost', '127.0.0.1', '[::1]'].includes(location.hostname) && <Panel className="mb-5"><PairPhone /></Panel>}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {MCP_AGENTS.map(a => (
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

/** Each agent's default model + effort (data/settings.json); Chats and Sessions can change them for one chat or session. */
function Models() {
  const [models, setModels] = useState<Record<string, string> | null>(null)
  const [efforts, setEfforts] = useState<Record<string, string>>({})
  const [edits, setEdits] = useState(0)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  useEffect(() => {
    api.settings().then(s => { setModels(s.models ?? {}); setEfforts(s.efforts ?? {}) }).catch(e => setMsg({ ok: false, text: e.message }))
  }, [])
  useEffect(() => {
    if (!edits || !models) return
    const t = setTimeout(() => {  // a typed model name saves once typing stops, not per letter
      const set = (r: Record<string, string>) => Object.fromEntries(Object.entries(r).filter(([, v]) => v))
      api.saveModels(set(models), set(efforts)).then(() => setMsg({ ok: true, text: 'Saved. Used from the next message.' }))
        .catch(e => setMsg({ ok: false, text: e.message }))
    }, 700)
    return () => clearTimeout(t)
  }, [edits])  // eslint-disable-line react-hooks/exhaustive-deps
  function change(agent: string, v: ModelChoice) {
    setModels(m => ({ ...m, [agent]: v.model ?? '' })); setEfforts(e => ({ ...e, [agent]: v.effort ?? '' }))
    setEdits(n => n + 1); setMsg(null)
  }
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="text-sm font-medium text-ink-2">Models</h2>
        <p className="text-xs text-ink-3">Each agent's default model and effort. Default = what the agent uses on its own. Chats and Sessions can change them for one chat or session.</p>
      </div>
      {!models ? <Skeleton className="h-24" /> : (
        <ul className="flex flex-col gap-2">
          {Object.keys(AGENT_META).map(a => <ModelRow key={a} agent={a} value={{ model: models[a] ?? '', effort: efforts[a] ?? '' }} onChange={v => change(a, v)} />)}
        </ul>
      )}
      <p aria-live="polite" className={`text-xs ${msg && !msg.ok ? 'text-bad' : 'text-ink-3'}`}>{msg?.text ?? 'An added agent gets a choice here when it has model or effort flags.'}</p>
    </div>
  )
}

function ModelRow({ agent, value, onChange }: { agent: string; value: ModelChoice; onChange: (v: ModelChoice) => void }) {
  const info = useModelInfo(agent)
  if (info && info.models === null && !info.efforts.length) return null  // an added agent without model/effort flags
  return (
    <li className="flex flex-wrap items-center gap-3 rounded-2xl border border-white bg-white/55 p-3">
      <AgentLogo name={agent} size={30} />
      <span className="w-28 text-sm font-medium text-ink">{AGENT_META[agent].label}</span>
      <ModelPick agent={agent} info={info} value={value} onChange={onChange} base={{ model: info?.default ?? '', effort: '' }} />
    </li>
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
      <AgentPicker multi legend="Add it to" value={agents} onChange={setAgents} only={MCP_AGENTS} />
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
// API keys the OS's own code uses (Zernio posts for designated agents). Write-only: a saved key is never shown again.
function Keys() {
  const [data, setData] = useState<{ keys: Record<string, string>; set: string[] } | null>(null)
  const [vals, setVals] = useState<Record<string, string>>({})
  const [msg, setMsg] = useState<string | null>(null)
  const load = () => api.secrets().then(setData).catch(e => setMsg((e as Error).message))
  useEffect(() => { load() }, [])
  async function save(name: string) {
    try { await api.setSecret(name, vals[name] ?? ''); setVals({ ...vals, [name]: '' }); setMsg(`${name} saved.`); load() }
    catch (e) { setMsg((e as Error).message) }
  }
  return (
    <div className="flex flex-col gap-3">
      <h2 className="text-lg font-medium tracking-tight text-ink">Keys</h2>
      <p className="text-sm text-ink-3">Saved on the PC only (config/secrets.json, never in git). Once saved, a key isn't shown again; paste a new one to replace it.</p>
      {data && Object.entries(data.keys).map(([name, label]) => (
        <div key={name} className="flex flex-wrap items-center gap-2">
          <label htmlFor={`k-${name}`} className="w-64 text-sm text-ink">{label}{data.set.includes(name) && <span className="ml-2 text-xs text-good">saved</span>}</label>
          <input id={`k-${name}`} type="password" autoComplete="off" value={vals[name] ?? ''} onChange={e => setVals({ ...vals, [name]: e.target.value })}
            placeholder={data.set.includes(name) ? 'Paste a new key to replace' : 'Paste the key'} className={`${inputCls} max-w-md flex-1`} />
          <button type="button" disabled={!(vals[name] ?? '').trim()} onClick={() => save(name)}
            className="inline-flex min-h-11 cursor-pointer items-center rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:opacity-50">Save</button>
        </div>
      ))}
      {msg && <p role="status" className="text-sm text-ink-2">{msg}</p>}
    </div>
  )
}

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
              <span className={`text-xs ${a.overdue ? 'text-bad' : 'text-ink-3'}`}>{a.overdue ? `Due since ${day(a.due)}` : `Next: ${day(a.due)}`}, last {day(a.last)}</span>
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

/** One switch per Claude plugin: on = Claude loads it, the other agents get its skills and tool servers. */
function Plugins() {
  const [list, setList] = useState<Plugin[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [msg, setMsg] = useState('')
  useEffect(() => { api.plugins().then(r => setList(r.plugins)).catch(e => setMsg(e.message)) }, [])
  async function flip(p: Plugin) {
    setBusy(p.id); setMsg('')
    try {
      const r = await api.togglePlugin(p.id, !p.enabled)
      const bad = Object.entries(r.results).filter(([, v]) => !v.ok).map(([k, v]) => `${k}: ${v.text}`)
      setList(l => l!.map(x => x.id === p.id ? { ...x, enabled: !p.enabled } : x))
      setMsg(`${p.name} ${p.enabled ? 'off' : 'on'} for every agent.${bad.length ? ' Problems: ' + bad.join('; ') : ''}`)
    } catch (e) { setMsg((e as Error).message) } finally { setBusy(null) }
  }
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="text-sm font-medium text-ink-2">Plugins</h2>
        <p className="text-xs text-ink-3">On = Claude Code loads it; OpenCode, Hermes and agy get its skills and tool servers. Hooks stay Claude-only. New Claude chats pick it up.</p>
      </div>
      {!list ? <Skeleton className="h-24" /> : (
        <ul className="grid grid-cols-1 gap-2 md:grid-cols-2">
          {list.map(p => (
            <li key={p.id} className="flex items-start gap-3 rounded-2xl border border-white bg-white/55 p-3">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-ink">{p.name}</p>
                <p className="line-clamp-2 text-xs text-ink-3">{p.description || p.id}</p>
                <p className="mt-1 text-[11px] text-ink-3">{[p.skills && 'skills', Object.keys(p.mcp).length > 0 && 'tool server'].filter(Boolean).join(', ') || 'hooks/commands only'}</p>
              </div>
              <button role="switch" aria-checked={p.enabled} aria-label={`${p.name} for every agent`} disabled={busy !== null} onClick={() => flip(p)}
                className={`relative h-7 w-12 shrink-0 cursor-pointer rounded-full border transition-colors disabled:opacity-50 ${p.enabled ? 'border-navy bg-navy' : 'border-ink-3/40 bg-ink-3/25'}`}>
                <span className={`absolute top-0.5 h-5.5 w-5.5 rounded-full left-0.5 bg-white shadow transition-transform ${p.enabled ? 'translate-x-[1.275rem]' : ''}`} />
              </button>
            </li>
          ))}
        </ul>
      )}
      {busy && <p className="text-xs text-ink-3">Switching… (adding tool servers to each agent can take a minute)</p>}
      {msg && <p role="status" className="text-xs text-ink-2">{msg}</p>}
    </div>
  )
}

// Known CLIs, filled in with their read-only switch. The prompt goes in on stdin unless the command has {prompt}.
const PRESETS = [
  { id: 'codex', label: 'Codex', role: 'Second opinion on code and plans (OpenAI)', command: 'codex exec --sandbox read-only --skip-git-repo-check --color never -', relogin: 'codex logout && codex login',
    model_args: '-m {model}', effort_args: '-c model_reasoning_effort={effort}' },
]

/** One-tap sign-in apps: the server knows each one's read-only command, so nothing is typed. */
function SignInTiles({ onAdded }: { onAdded: (text: string) => void }) {
  const [list, setList] = useState<CatalogAgent[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const load = () => api.agentCatalog().then(r => setList(r.catalog)).catch(e => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [])
  async function tap(c: CatalogAgent) {
    setBusy(c.id); setMsg({ ok: true, text: `Checking ${c.label} (up to a few minutes)…` })
    try {
      const r = await api.addCatalogAgent(c.id)
      if (r.status === 'added') { setMsg(null); onAdded(`Added ${c.label}. Test answer: "${r.test_answer}"`) }
      else setMsg({ ok: true, text: r.text })
      load()
    } catch (x) { setMsg({ ok: false, text: (x as Error).message }) } finally { setBusy(null) }
  }
  if (!list) return <Skeleton className="h-16" />
  return (
    <>
      <div className="flex flex-wrap gap-2">
        {list.map(c => (
          <button key={c.id} type="button" disabled={busy !== null || c.added} onClick={() => tap(c)} title={c.note}
            className="flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:cursor-default disabled:opacity-60">
            {c.label}{c.added ? ' ✓' : busy === c.id ? '…' : !c.installed ? ' (installs)' : ''}
          </button>
        ))}
      </div>
      {msg && <p role={msg.ok ? 'status' : 'alert'} className={`text-sm ${msg.ok ? 'text-ink-2' : 'text-bad'}`}>{msg.text}</p>}
    </>
  )
}

/** Add with an API key: pick the company, paste the key, pick a model. No command, so it works from the phone too. */
function ApiKeyAdd({ onAdded }: { onAdded: (text: string) => void }) {
  const [providers, setProviders] = useState<AgentProvider[]>([])
  const [f, setF] = useState({ provider: 'openrouter', key: '', model: '', label: '', role: '' })
  const [models, setModels] = useState<string[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  useEffect(() => { api.agentProviders().then(r => setProviders(r.providers)).catch(() => {}) }, [])
  const p = providers.find(x => x.id === f.provider)
  async function loadModels() {
    setBusy(true); setMsg(null)
    try { const r = await api.providerModels(f.provider, f.key); setModels(r.models); if (!r.models.length) setMsg({ ok: false, text: 'No models came back: type the model name.' }) }
    catch (x) { setModels(null); setMsg({ ok: false, text: (x as Error).message }) } finally { setBusy(false) }
  }
  async function add(e: React.FormEvent) {
    e.preventDefault()
    if (!f.key && !p?.has_key) { setMsg({ ok: false, text: 'Paste the key first.' }); return }
    if (!f.model.trim()) { setMsg({ ok: false, text: 'Pick a model.' }); return }
    setBusy(true); setMsg({ ok: true, text: 'Testing it…' })
    try {
      const r = await api.addApiAgent(f)
      setF({ ...f, key: '', model: '', label: '', role: '' }); setModels(null); setMsg(null)
      api.agentProviders().then(x => setProviders(x.providers)).catch(() => {})
      onAdded(`Added ${r.label}. Test answer: "${r.test_answer}"`)
    } catch (x) { setMsg({ ok: false, text: (x as Error).message }) } finally { setBusy(false) }
  }
  return (
    <form onSubmit={add} className="flex flex-col gap-3">
      <Field id="api-provider" label="Company">
        <select id="api-provider" value={f.provider} onChange={e => { setF({ ...f, provider: e.target.value, model: '' }); setModels(null) }} className={inputCls}>
          {providers.map(x => <option key={x.id} value={x.id}>{x.label}{x.has_key ? ' (key saved)' : ''}</option>)}
        </select>
      </Field>
      <Field id="api-key" label="API key" help={p ? <>{p.has_key ? 'Saved already: leave empty to use it. ' : ''}<a href={p.keys_url} target="_blank" rel="noreferrer" className="underline">Get a {p.label} key</a></> : undefined}>
        <input id="api-key" type="password" autoComplete="off" value={f.key} onChange={e => setF({ ...f, key: e.target.value })} placeholder={p?.has_key ? 'Using the saved key' : 'Paste it here'} className={`${inputCls} font-mono text-sm`} />
      </Field>
      <div className="flex items-end gap-2">
        <div className="min-w-0 flex-1"><Field id="api-model" label="Model">
          <input id="api-model" list="api-models" value={f.model} onChange={e => setF({ ...f, model: e.target.value })} placeholder={models ? `Search ${models.length} models` : 'Load the list, or type a name'} className={`${inputCls} font-mono text-sm`} />
          <datalist id="api-models">{models?.map(m => <option key={m} value={m} />)}</datalist>
        </Field></div>
        <button type="button" onClick={loadModels} disabled={busy} className="min-h-11 cursor-pointer rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white disabled:opacity-50">Load models</button>
      </div>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <Field id="api-label" label="Name (optional)"><input id="api-label" value={f.label} onChange={e => setF({ ...f, label: e.target.value })} placeholder={f.model.split('/').pop() || 'The model name'} className={inputCls} /></Field>
        <Field id="api-role" label="What it's for (optional)"><input id="api-role" value={f.role} onChange={e => setF({ ...f, role: e.target.value })} placeholder="General answers" className={inputCls} /></Field>
      </div>
      <p className="text-xs text-ink-3">It only answers: it can't open your files or search the web.</p>
      {msg && <p role={msg.ok ? 'status' : 'alert'} className={`text-sm ${msg.ok ? 'text-ink-2' : 'text-bad'}`}>{msg.text}</p>}
      <ChromeButton type="submit" disabled={busy} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{busy ? 'Working' : 'Test and add'}</ChromeButton>
    </form>
  )
}

/** Jhon's own command-line agents: they join chats, sessions, teams and schedules like the built-in four. */
function MyAgents() {
  const [list, setList] = useState(() => Object.entries(AGENT_META).filter(([, m]) => m.custom))
  const [f, setF] = useState({ id: '', label: '', role: '', command: '', timeout: 600, relogin: '', model_args: '', effort_args: '' })
  const [logo, setLogo] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const refresh = async () => { await loadAgents(); setList(Object.entries(AGENT_META).filter(([, m]) => m.custom)) }
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: k === 'timeout' ? +e.target.value : e.target.value })
  async function add(e: React.FormEvent) {
    e.preventDefault()
    if (!/^[a-z][a-z0-9-]{1,23}$/.test(f.id)) { setMsg({ ok: false, text: 'Short id: lowercase letters, digits and dashes, e.g. codex.' }); return }
    if (!f.label.trim() || !f.role.trim() || !f.command.trim()) { setMsg({ ok: false, text: 'Fill in name, role and command.' }); return }
    setBusy(true); setMsg({ ok: true, text: 'Testing it: asking it to say hello (up to a few minutes)…' })
    try {
      const r = await api.addAgent(f)
      if (logo) await api.uploadAgentLogo(r.id, logo)
      await refresh()
      setMsg({ ok: true, text: `Added ${r.label}. Test answer: "${r.test_answer}"${logo ? '' : r.logo ? ' Logo found automatically.' : ' No logo found online: upload one if you like.'}` })
      setF({ id: '', label: '', role: '', command: '', timeout: 600, relogin: '', model_args: '', effort_args: '' }); setLogo(null)
    } catch (x) { setMsg({ ok: false, text: (x as Error).message }) } finally { setBusy(false) }
  }
  async function findLogo(id: string) {
    try { await api.findAgentLogo(id); await refresh(); setMsg({ ok: true, text: `Logo found for ${AGENT_META[id].label}.` }) }
    catch (x) { setMsg({ ok: false, text: (x as Error).message }) }
  }
  async function remove(id: string) {
    if (!confirm(`Remove ${AGENT_META[id].label} from the OS? (The program itself stays installed.)`)) return
    await api.removeAgent(id); await refresh()
  }
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="text-sm font-medium text-ink-2">Your agents</h2>
        <p className="text-xs text-ink-3">Add any command-line AI agent. It joins Chats, Sessions, Team runs and Schedules. Use its read-only switch: workers here only plan.</p>
      </div>
      {list.length > 0 && <ul className="flex flex-col gap-2">
        {list.map(([id, m]) => (
          <li key={id} className="flex items-center gap-3 rounded-2xl border border-white bg-white/55 p-3">
            <AgentLogo name={id} size={36} />
            <div className="min-w-0 flex-1"><p className="text-sm font-medium text-ink">{m.label}</p><p className="truncate font-mono text-xs text-ink-3">{m.command}</p></div>
            {!m.logo && <button onClick={() => findLogo(id)} className="min-h-11 cursor-pointer rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white">Find logo</button>}
            <button onClick={() => remove(id)} className="min-h-11 cursor-pointer rounded-full border border-white bg-white/70 px-4 text-sm text-ink hover:bg-white">Remove</button>
          </li>
        ))}
      </ul>}
      <h3 className="text-sm font-medium text-ink">Add an agent</h3>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-3 rounded-2xl border border-white bg-white/55 p-4">
          <p className="text-sm font-medium text-ink">Sign in</p>
          <p className="text-xs text-ink-3">For apps with a login. Tap one: it installs and opens the sign-in on the PC if needed. Tap again once you're signed in. These can read files and search the web.</p>
          <SignInTiles onAdded={async text => { await refresh(); setMsg({ ok: true, text }) }} />
        </div>
        <div className="flex flex-col gap-3 rounded-2xl border border-white bg-white/55 p-4">
          <p className="text-sm font-medium text-ink">API key</p>
          <ApiKeyAdd onAdded={async text => { await refresh(); setMsg({ ok: true, text }) }} />
        </div>
      </div>
      {msg && <p role={msg.ok ? 'status' : 'alert'} className={`text-sm ${msg.ok ? 'text-ink-2' : 'text-bad'}`}>{msg.text}</p>}
      <details className="rounded-2xl border border-white bg-white/40 p-4">
        <summary className="cursor-pointer text-sm text-ink-2">Advanced: add any program by its command (PC only)</summary>
      <form onSubmit={add} className="mt-4 flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-2 text-sm text-ink-2">Quick fill:
          {PRESETS.map(p => <button type="button" key={p.id} onClick={() => setF({ ...f, ...p })}
            className="min-h-11 cursor-pointer rounded-full border border-white bg-white/70 px-4 text-ink hover:bg-white">{p.label}</button>)}
        </div>
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <Field id="ag-id" label="Id"><input id="ag-id" value={f.id} onChange={set('id')} placeholder="codex" className={inputCls} /></Field>
          <Field id="ag-label" label="Name"><input id="ag-label" value={f.label} onChange={set('label')} placeholder="Codex" className={inputCls} /></Field>
          <Field id="ag-role" label="Role (one line)"><input id="ag-role" value={f.role} onChange={set('role')} placeholder="Second opinion on code" className={inputCls} /></Field>
        </div>
        <Field id="ag-cmd" label="Command" help="The program and its flags. Put {prompt} where the prompt goes, or leave it out to send the prompt on stdin.">
          <input id="ag-cmd" value={f.command} onChange={set('command')} placeholder="codex exec --sandbox read-only -" className={`${inputCls} font-mono text-sm`} />
        </Field>
        <Field id="ag-relogin" label="Re-login command (if it needs a login)" help="Logs it out and back in. It then joins the weekly re-login card above.">
          <input id="ag-relogin" value={f.relogin} onChange={set('relogin')} placeholder="codex logout && codex login" className={`${inputCls} font-mono text-sm`} />
        </Field>
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <Field id="ag-model" label="Model flag (optional)" help="How it picks a model, with {model}. Empty = no model choice for this agent.">
            <input id="ag-model" value={f.model_args} onChange={set('model_args')} placeholder="-m {model}" className={`${inputCls} font-mono text-sm`} />
          </Field>
          <Field id="ag-effort" label="Effort flag (optional)" help="How it sets reasoning effort, with {effort} (low to xhigh).">
            <input id="ag-effort" value={f.effort_args} onChange={set('effort_args')} placeholder="-c model_reasoning_effort={effort}" className={`${inputCls} font-mono text-sm`} />
          </Field>
        </div>
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <Field id="ag-timeout" label="Time limit (seconds)"><input id="ag-timeout" type="number" min={30} max={1800} value={f.timeout} onChange={set('timeout')} className={inputCls} /></Field>
          <Field id="ag-logo" label="Logo (optional)" help="SVG, PNG, WebP or JPG, up to 300 KB.">
            <input id="ag-logo" type="file" accept=".svg,.png,.webp,.jpg" onChange={e => setLogo(e.target.files?.[0] ?? null)} className="text-sm text-ink-2" />
          </Field>
        </div>
        <ChromeButton type="submit" disabled={busy} icon={<Plus size={16} weight="bold" aria-hidden="true" />}>{busy ? 'Testing' : 'Test and add'}</ChromeButton>
      </form>
      </details>
    </div>
  )
}
