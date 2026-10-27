export type AgentName = 'claude' | 'opencode' | 'hermes' | 'agy'
export type Agent = { name: AgentName; status: 'online' | 'offline' }
export type CostEntry = { timestamp: string; agent: string; tokens: number; cost: number; model: string }
export type AuditEntry = { id?: string; action: string; timestamp: string; agent?: string; skill?: string; msg_preview?: string; output_preview?: string; thread?: string; hops?: number }
export type Skill = { name: string; description: string; has_learnings: boolean; scores: unknown[] }
export type TeamStep = { agent: AgentName; task: string; output: string; handoff_to: AgentName | null; timestamp: string }
export type Thread = { id: string; message: string; start_agent: AgentName; status: 'running' | 'done'; active: AgentName | null; steps: TeamStep[]; created: string }

export type ChatMsg = { id: string; role: 'user' | 'assistant'; agent: AgentName; content: string; timestamp: string }
export type SessionMsg = { role: 'user' | 'agent'; agent: AgentName | null; round: number; content: string; timestamp: string }
export type Session = { id: string; title: string; agents: AgentName[]; rounds: number; round: number; status: 'running' | 'idle'; working: AgentName[]; messages: SessionMsg[]; created: string }
export type Job = { id: string; name: string; kind: 'agent' | 'team' | 'skill'; agent: AgentName; prompt: string; skill?: string | null; cron: string; enabled: boolean; last_run: string | null; description?: string }
export type JobEvent = { type: string; job_id?: string; name?: string; output?: string; trigger?: string; timestamp: string }

export type JobPost = { id: string; title: string; company: string; url: string; source: string; pay?: string; score?: number; reason?: string; lane: string; status: 'new' | 'queued' | 'drafted' | 'draft_blocked' | 'needs_you' | 'approved' | 'applying' | 'handoff' | 'closed' | 'skipped' | 'applied' | 'replied'; draft: string; applied_at?: string; follow_up?: 'due' | 'done'; followup_draft?: string; followup_problem?: string; problem?: string; apply_result?: string; needs_why?: string; found: string }
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
  chat: (agent: AgentName, message: string) => req<{ response: ChatMsg }>('/api/chat', { method: 'POST', body: JSON.stringify({ agent, message }) }),
  sessions: () => req<{ sessions: Session[] }>('/api/sessions'),
  startSession: (message: string, agents: AgentName[], rounds: number) =>
    req<Session>('/api/sessions', { method: 'POST', body: JSON.stringify({ message, agents, rounds }) }),
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
  models: (agent: 'claude' | 'opencode') => req<{ models: string[]; current: string }>(`/api/models/${agent}`),
  settings: () => req<{ models?: Record<string, string> }>('/api/settings'),
  saveModels: (models: Record<string, string>) => req<unknown>('/api/settings', { method: 'PUT', body: JSON.stringify({ settings: { models } }) }),
  jobsBoard: () => req<{ jobs: JobPost[]; runs: JobRun[]; running: boolean }>('/api/jobs'),
  runJobSearch: () => req<unknown>('/api/jobs/run', { method: 'POST' }),
  addIndeedJob: (url: string) => req<JobPost>('/api/jobs/add', { method: 'POST', body: JSON.stringify({ url }) }),
  decideJob: (id: string, decision: 'approve' | 'skip' | 'applied' | 'replied' | 'followed_up') =>
    req<JobPost>(`/api/jobs/${id}/decision`, { method: 'POST', body: JSON.stringify({ decision }) }),
  threads: () => req<{ threads: Thread[] }>('/api/team'),
  thread: (id: string) => req<Thread>(`/api/team/${id}`),
  startTeam: (message: string, agent: AgentName) => req<Thread>('/api/team', { method: 'POST', body: JSON.stringify({ message, agent }) }),
}

export const AGENT_META: Record<AgentName, { label: string; role: string }> = {
  claude: { label: 'Claude Code', role: 'Writing and hard reasoning' },
  opencode: { label: 'OpenCode', role: 'Code, checks, bulk work' },
  hermes: { label: 'Hermes', role: 'Schedules and your phone' },
  agy: { label: 'agy', role: 'Web research' },
}

export const timeAgo = (iso: string) => {
  const s = (Date.now() - new Date(iso).getTime()) / 1000
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}
