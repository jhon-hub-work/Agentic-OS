export type AgentName = string  // the 4 built in + any added on Agents & tools (AGENT_META, filled by loadAgents)
export type Agent = { name: AgentName; status: 'online' | 'offline' }
export type CostEntry = { timestamp: string; agent: string; tokens: number; cost: number; model: string }
export type AuditEntry = { id?: string; action: string; timestamp: string; agent?: string; skill?: string; msg_preview?: string; output_preview?: string; thread?: string; hops?: number }
export type Skill = { name: string; description: string; has_learnings: boolean; scores: unknown[] }
export type TeamStep = { agent: AgentName; task: string; output: string; handoff_to: AgentName | null; timestamp: string }
export type Thread = { id: string; message: string; start_agent: AgentName; status: 'running' | 'done'; active: AgentName | null; steps: TeamStep[]; created: string }

export type ChatMsg = { id: string; role: 'user' | 'assistant'; agent: AgentName; content: string; timestamp: string }
export type ToolStep = { tool: string; toolCallId: string; label?: string; emoji?: string; status: 'running' | 'completed' | string }
export type ChatContext = { used: number; window: number | null }
export type StreamEvent = ({ type: 'context' } & ChatContext) | { type: 'text'; text: string } | ({ type: 'tool' } & ToolStep) | { type: 'error'; error: string } | { type: 'cleared' } | { type: 'done'; message: ChatMsg }
export type SessionMsg = { role: 'user' | 'agent' | 'verdict'; agent: AgentName | null; round: number; content: string; timestamp: string; prompt_tokens?: number }
export type Session = { id: string; title: string; agents: AgentName[]; rounds: number; round: number; status: 'running' | 'idle'; working: AgentName[]; judging?: boolean; messages: SessionMsg[]; created: string; pinned?: boolean; read?: boolean; section?: string; archived?: boolean; overrides?: Record<AgentName, ModelChoice> }
/** One agent's model + effort; "" or missing = its default. */
export type ModelChoice = { model?: string; effort?: string }
/** models: its list ([] = type a name, null = no model choice); default: what "Default" runs when nothing is set. */
export type ModelInfo = { models: string[] | null; efforts: string[]; current: string; current_effort: string; default: string }
/** A session as the list sends it (?light=1): no messages, just how many and the last one. */
export type SessionItem = Omit<Session, 'messages'> & { message_count: number; last: SessionMsg | null }
export type Job ={ id: string; name: string; kind: 'agent' | 'team' | 'skill'; agent: AgentName; prompt: string; skill?: string | null; cron: string; enabled: boolean; last_run: string | null; description?: string }
export type JobEvent = { type: string; job_id?: string; name?: string; output?: string; trigger?: string; timestamp: string }

export type JobPost = { id: string; title: string; company: string; url: string; source: string; pay?: string; score?: number; reason?: string; lane: string; status: 'new' | 'queued' | 'drafted' | 'draft_blocked' | 'needs_you' | 'approved' | 'applying' | 'handoff' | 'closed' | 'skipped' | 'applied' | 'replied'; draft: string; applied_at?: string; follow_up?: 'due' | 'done'; followup_draft?: string; followup_problem?: string; problem?: string; apply_result?: string; needs_why?: string; found: string }
export type Plugin = { id: string; name: string; description: string; enabled: boolean; skills: boolean; mcp: Record<string, unknown> }
export type LabRun = { id: string; request: string; rounds: number; status: 'running' | 'passed' | 'failed' | 'blocked' | 'error'; name: string; source: string; scores: number[]; created: string; installed_to?: string; blocked_by?: string; override?: boolean; messages: { at: string; who: 'you' | 'lab'; text: string }[] }
export type TicketSeverity = 'P1' | 'P2' | 'P3' | 'P4'
export type TicketCategory = 'incident' | 'bug' | 'request' | 'change'
export type TicketStatus = 'new' | 'planning' | 'awaiting_approval' | 'approved' | 'in_progress' | 'waiting_on_you' | 'resolved' | 'closed'
export type Ticket = { id: string; title: string; description: string; category: TicketCategory; severity: TicketSeverity; status: TicketStatus; project: string; reporter: string; assignee: string; created: string; updated: string; pickup_by: string; due: string; plan: string; result: string; files?: string[]; log: { at: string; who: string; text: string }[] }
export type JobRun = { started: string; finished?: string; fetched: number; new: number; kept: number; scored: number; drafted: number; needs_you: number; errors: string[] }

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init })
  if (!r.ok) {
    const e = await r.json().catch(() => ({}))
    throw new Error(e.detail || `Request failed (${r.status})`)
  }
  return r.json()
}

export const api = {
  status: () => req<{ agents: Agent[]; skills_count: number }>('/api/status'),
  cost: () => req<{ entries: CostEntry[] }>('/api/cost'),
  audit: (limit = 60) => req<{ entries: AuditEntry[] }>(`/api/audit?limit=${limit}`),
  skills: () => req<Skill[]>('/api/skills'),
  runSkill: (name: string, input: string, agent = 'auto') =>
    req<{ output: string; agent: string }>(`/api/skills/${encodeURIComponent(name)}/run`, { method: 'POST', body: JSON.stringify({ input, agent }) }),
  chatHistory: (agent: AgentName) => req<{ messages: ChatMsg[] }>(`/api/chat/history?agent=${agent}&limit=100`),
  chat: (agent: AgentName, message: string, pick: ModelChoice = {}) => req<{ response: ChatMsg }>('/api/chat', { method: 'POST', body: JSON.stringify({ agent, message, ...pick }) }),
  chatMeta: (agent: AgentName) => req<{ context: ChatContext | null; commands: string[]; live: boolean }>(`/api/chat/meta?agent=${agent}`),
  // Live reply: words and tool steps arrive as server-sent events (Hermes and Claude Code)
  chatStream: async (agent: AgentName, message: string, on: (e: StreamEvent) => void, pick: ModelChoice = {}) => {
    const r = await fetch('/api/chat/stream', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ agent, message, ...pick }) })
    if (!r.ok || !r.body) throw new Error((await r.json().catch(() => ({}))).detail || `Request failed (${r.status})`)
    const reader = r.body.pipeThrough(new TextDecoderStream()).getReader()
    let buf = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) return
      buf += value
      let i
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const chunk = buf.slice(0, i); buf = buf.slice(i + 2)
        if (chunk.startsWith('data: ')) on(JSON.parse(chunk.slice(6)))
      }
    }
  },
  sessions: () => req<{ sessions: SessionItem[] }>('/api/sessions?light=1&limit=200'),
  session: (id: string) => req<Session>(`/api/sessions/${id}`),
  startSession: (message: string, agents: AgentName[], rounds: number, overrides: Record<AgentName, ModelChoice> = {}) =>
    req<Session>('/api/sessions', { method: 'POST', body: JSON.stringify({ message, agents, rounds, overrides }) }),
  patchSession: (id: string, p: Partial<Pick<Session, 'title' | 'pinned' | 'read' | 'section' | 'archived' | 'overrides'>>) =>
    req<Session>(`/api/sessions/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
  forkSession: (id: string) => req<Session>(`/api/sessions/${id}/fork`, { method: 'POST' }),
  deleteSession: (id: string) => req<{ deleted: string }>(`/api/sessions/${id}`, { method: 'DELETE' }),
  sessionMessage: (id: string, message: string) => req<Session>(`/api/sessions/${id}/message`, { method: 'POST', body: JSON.stringify({ message }) }),
  jobs: () => req<Job[]>('/api/scheduler/jobs'),
  createJob: (job: Partial<Job>) => req<Job>('/api/scheduler/jobs', { method: 'POST', body: JSON.stringify(job) }),
  toggleJob: (id: string, enabled: boolean) => req<Job>(`/api/scheduler/jobs/${id}`, { method: 'PATCH', body: JSON.stringify({ enabled }) }),
  deleteJob: (id: string) => req<unknown>(`/api/scheduler/jobs/${id}`, { method: 'DELETE' }),
  runJob: (id: string) => req<unknown>(`/api/scheduler/trigger/${id}`, { method: 'POST' }),
  jobEvents: () => req<{ events: JobEvent[] }>('/api/scheduler/events?limit=200'),
  addSkill: (s: { name: string; description: string; instructions: string; agent: AgentName }) =>
    req<unknown>('/api/skills/generate', { method: 'POST', body: JSON.stringify(s) }),
  connections: () => req<Record<AgentName, { ok: boolean; text: string }>>('/api/connections'),
  addConnection: (c: { name: string; url: string; command: string; agents: AgentName[] }) =>
    req<Record<string, { ok: boolean; text: string }>>('/api/connections', { method: 'POST', body: JSON.stringify(c) }),
  models: (agent: AgentName) => req<ModelInfo>(`/api/models/${agent}`),
  settings: () => req<{ models?: Record<string, string>; efforts?: Record<string, string> }>('/api/settings'),
  saveModels: (models: Record<string, string>, efforts: Record<string, string>) =>
    req<unknown>('/api/settings', { method: 'PUT', body: JSON.stringify({ settings: { models, efforts } }) }),
  jobPeople: () => req<{ id: string; name: string; summary?: string }[]>('/api/jobs/people'),
  jobsBoard: (who = '') => req<{ jobs: JobPost[]; runs: JobRun[]; running: boolean }>(`/api/jobs?who=${who}`),
  runJobSearch: (who = '') => req<unknown>(`/api/jobs/run?who=${who}`, { method: 'POST' }),
  addIndeedJob: (url: string) => req<JobPost>('/api/jobs/add', { method: 'POST', body: JSON.stringify({ url }) }),
  decideJob: (id: string, decision: 'approve' | 'skip' | 'applied' | 'replied' | 'followed_up', who = '') =>
    req<JobPost>(`/api/jobs/${id}/decision?who=${who}`, { method: 'POST', body: JSON.stringify({ decision }) }),
  plugins: () => req<{ plugins: Plugin[] }>('/api/claude-plugins'),
  togglePlugin: (id: string, enabled: boolean) =>
    req<{ results: Record<string, { ok: boolean; text: string }>; skills_synced: string[] }>(`/api/claude-plugins/${encodeURIComponent(id)}`, { method: 'POST', body: JSON.stringify({ enabled }) }),
  skillLab: () => req<{ runs: LabRun[]; pass: number }>('/api/skill-lab'),
  startSkillLab: (message: string, rounds: number) => req<LabRun>('/api/skill-lab', { method: 'POST', body: JSON.stringify({ message, rounds }) }),
  installAnyway: (id: string) => req<{ started: boolean }>(`/api/skill-lab/${id}/install-anyway`, { method: 'POST' }),
  addAgent: (a: { id: string; label: string; role: string; command: string; timeout: number; relogin: string; model_args: string; effort_args: string }) =>
    req<CustomAgent & { test_answer: string }>('/api/agents', { method: 'POST', body: JSON.stringify(a) }),
  findAgentLogo: (id: string) => req<{ logo: string }>(`/api/agents/${id}/logo/find`, { method: 'POST' }),
  removeAgent: (id: string) => req<unknown>(`/api/agents/${id}`, { method: 'DELETE' }),
  uploadAgentLogo: async (id: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    const r = await fetch(`/api/agents/${id}/logo`, { method: 'POST', body: form })
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).detail || `Upload failed (${r.status})`)
  },
  tickets: () => req<{ tickets: Ticket[]; sla: Record<TicketSeverity, [number, number]> }>('/api/tickets'),
  createTicket: (t: Pick<Ticket, 'title' | 'description' | 'category' | 'severity' | 'project'>) =>
    req<Ticket>('/api/tickets', { method: 'POST', body: JSON.stringify(t) }),
  patchTicket: (id: string, p: Partial<Pick<Ticket, 'title' | 'severity' | 'status' | 'due'>>) =>
    req<Ticket>(`/api/tickets/${id}`, { method: 'PATCH', body: JSON.stringify(p) }),
  commentTicket: (id: string, text: string) => req<Ticket>(`/api/tickets/${id}/comment`, { method: 'POST', body: JSON.stringify({ text }) }),
  attachTicketFile: async (id: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    const r = await fetch(`/api/tickets/${id}/files`, { method: 'POST', body: form })  // no JSON header: the browser sets the multipart one
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).detail || `Upload failed (${r.status})`)
    return r.json() as Promise<Ticket>
  },
  ticketChanges: (id: string) => req<{ changes: { file: string; before: string | null; changed_since: boolean }[] }>(`/api/tickets/${id}/changes`),
  undoTicket: (id: string) => req<Ticket>(`/api/tickets/${id}/undo`, { method: 'POST' }),
  selfReview: () => req<{ last?: string; running?: boolean; opened?: string[]; problem?: string | null }>('/api/self-review'),
  runSelfReview: () => req<unknown>('/api/self-review/run', { method: 'POST' }),
  approveTicket: (id: string) => req<Ticket>(`/api/tickets/${id}/approve`, { method: 'POST' }),
  rejectTicket: (id: string, text: string) => req<Ticket>(`/api/tickets/${id}/reject`, { method: 'POST', body: JSON.stringify({ text }) }),
  threads: () => req<{ threads: Thread[] }>('/api/team'),
  thread: (id: string) => req<Thread>(`/api/team/${id}`),
  startTeam: (message: string, agent: AgentName) => req<Thread>('/api/team', { method: 'POST', body: JSON.stringify({ message, agent }) }),
}

export const AGENT_META: Record<AgentName, { label: string; role: string; custom?: boolean; logo?: string | null; command?: string }> = {
  claude: { label: 'Claude Code', role: 'Writing and hard reasoning' },
  opencode: { label: 'OpenCode', role: 'Code, checks, bulk work' },
  hermes: { label: 'Hermes', role: 'Schedules and your phone' },
  agy: { label: 'agy', role: 'Web research' },
}

export type CustomAgent = { id: string; label: string; role: string; builtin: boolean; command?: string; logo?: string | null; timeout?: number }

/** Adds Jhon's own agents to AGENT_META. Called once before the app renders, and again after an add or remove. */
export async function loadAgents() {
  const { agents } = await req<{ agents: CustomAgent[] }>('/api/agents')
  for (const k of Object.keys(AGENT_META)) if (AGENT_META[k].custom) delete AGENT_META[k]
  for (const a of agents) if (!a.builtin) AGENT_META[a.id] = { label: a.label, role: a.role, custom: true, logo: a.logo, command: a.command }
}

export const fmtTokens = (n: number) => n >= 1e6 ? `${+(n / 1e6).toFixed(1)}M` : n >= 1000 ? `${Math.round(n / 1000)}k` : `${n}`

export const timeAgo = (iso: string) => {
  const s = (Date.now() - new Date(iso).getTime()) / 1000
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}
