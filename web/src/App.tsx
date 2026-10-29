import { Briefcase, CheckSquareOffset, List, X, CalendarDots, Waveform, ChatsCircle, Coins, DotsThreeOutline, Lightning, ListBullets, Plug, SquaresFour, Ticket, UsersFour, UsersThree, type Icon } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import Activity from './pages/Activity'
import Agents from './pages/Agents'
import Approvals from './pages/Approvals'
import Chats from './pages/Chats'
import Home from './pages/Home'
import Jobs from './pages/Jobs'
import Schedules from './pages/Schedules'
import Sessions from './pages/Sessions'
import Cost, { GuardMini } from './pages/Cost'
import Overview from './pages/Overview'
import Skills from './pages/Skills'
import Team from './pages/Team'
import Tickets from './pages/Tickets'
import ThemeSwitch from './ThemeSwitch'
import TitleBar, { inApp } from './TitleBar'
import { api } from './api'
import { PhoneHome, usePhone } from './Phone'
import { spring, usePoll } from './ui'

const NAV: { id: string; label: string; icon: Icon }[] = [
  { id: 'home', label: 'Jharvis', icon: Waveform },
  { id: 'approvals', label: 'Approvals', icon: CheckSquareOffset },
  { id: 'jobs', label: 'Jobs', icon: Briefcase },
  { id: 'tickets', label: 'Tickets', icon: Ticket },
  { id: 'chats', label: 'Chats', icon: ChatsCircle },
  { id: 'sessions', label: 'Sessions', icon: UsersFour },
  { id: 'schedules', label: 'Schedules', icon: CalendarDots },
  { id: 'team', label: 'Team', icon: UsersThree },
  { id: 'skills', label: 'Skills', icon: Lightning },
  { id: 'agents', label: 'Agents & tools', icon: Plug },
  { id: 'overview', label: 'Overview', icon: SquaresFour },
  { id: 'cost', label: 'Billing', icon: Coins },
  { id: 'activity', label: 'Activity', icon: ListBullets },
]
const PHONE_TABS = 4  // the rest sit under "More"

const current = () => NAV.find(n => `#/${n.id}` === location.hash)?.id ?? 'home'

export default function App() {
  const [page, setPage] = useState(current)
  const [more, setMore] = useState(false)
  const [rail, setRail] = useState(false)  // Jharvis's page hides the rail; the menu button brings it back
  const focus = page === 'home'
  const phone = usePhone()
  const waiting = usePoll(api.approvals, 10000).data?.count ?? 0  // the count badge on Approvals
  useEffect(() => {
    const on = () => { setPage(current()); setMore(false); setRail(false) }
    addEventListener('hashchange', on)
    return () => removeEventListener('hashchange', on)
  }, [])
  useEffect(() => {
    if (!inApp) return
    // The desktop webview drops new-tab links and downloads: the OS opens them in the default browser instead.
    const on = (e: MouseEvent) => {
      const a = (e.target as Element).closest?.('a[href]') as HTMLAnchorElement | null
      if (!a || !(a.target === '_blank' || a.hasAttribute('download'))) return
      e.preventDefault()
      fetch('/api/open', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: a.href }) })
    }
    document.addEventListener('click', on, true)
    return () => document.removeEventListener('click', on, true)
  }, [])
  const go = (id: string) => { location.hash = `/${id}` }

  return (
    <div className="min-h-[100dvh]">
      <div className="frost-mesh" aria-hidden="true" />
      <TitleBar />
      {/* in the desktop app the window buttons sit in the top-right corner, so the hole moves down */}
      <ThemeSwitch className={`fixed right-4 z-30 ${inApp ? 'top-11' : 'top-4'}`} />
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-full focus:bg-white focus:px-4 focus:py-2">Skip to content</a>

      {/* Desktop: floating glass rail */}
      {focus && (
        <button onClick={() => setRail(r => !r)} aria-expanded={rail} aria-label={rail ? 'Hide menu' : 'Show menu'}
          className="glass-bar fixed left-4 top-4 z-30 hidden h-11 w-11 cursor-pointer items-center justify-center rounded-full text-ink-2 hover:text-ink md:flex">
          {rail ? <X size={18} /> : <List size={18} />}
        </button>
      )}
      <aside className={`glass-bar fixed bottom-4 left-4 z-20 hidden w-60 flex-col rounded-[1.75rem] p-4 ${focus ? (rail ? 'top-[4.25rem] md:flex' : '') : 'top-4 md:flex'}`}>
        <div className="mb-4 flex items-center gap-3 px-2 pt-1">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl border border-white bg-white/80 shadow-[0_6px_16px_-8px_rgba(29,43,58,0.4)]" aria-hidden="true"><img src="/logo.svg" alt="" className="h-7 w-7" /></span>
          <div>
            <p className="text-[15px] font-medium leading-tight text-ink">Agentic OS</p>
            <p className="text-xs text-ink-3">Command centre</p>
          </div>
        </div>
        <GuardMini />
        <nav aria-label="Main" className="flex flex-col gap-1">
          {NAV.map(n => {
            const active = page === n.id
            return (
              <a key={n.id} href={`#/${n.id}`} aria-current={active ? 'page' : undefined}
                className={`relative flex min-h-11 items-center gap-3 rounded-2xl px-3 text-sm transition-colors duration-200 ${active ? 'text-ink' : 'text-ink-2 hover:text-ink'}`}>
                {active && <motion.span layoutId="nav-pill" transition={spring} className="absolute inset-0 rounded-2xl border border-white bg-white/75 shadow-[0_8px_20px_-14px_rgba(29,43,58,0.4)]" />}
                <n.icon size={19} weight={active ? 'regular' : 'light'} className="relative" aria-hidden="true" />
                <span className="relative font-medium">{n.label}</span>
                {n.id === 'approvals' && waiting > 0 && <Badge n={waiting} className="relative ml-auto" />}
              </a>
            )
          })}
        </nav>
      </aside>

      <main id="main" className={focus ? 'px-4 pb-32 pt-8 md:pb-8 md:pt-8' : `mx-auto ${page === 'sessions' || page === 'chats' ? 'max-w-[1760px]' : 'max-w-[1200px]'} px-4 pb-32 pt-8 md:pb-12 md:pl-72 md:pr-8 md:pt-12`}>
        <AnimatePresence mode="wait">
          <motion.div key={page} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6, transition: { duration: 0.15 } }} transition={spring}>
            {page === 'home' && (phone ? <PhoneHome /> : <Home />)}
            {page === 'overview' && <Overview go={go} />}
            {page === 'jobs' && <Jobs />}
            {page === 'approvals' && <Approvals />}
            {page === 'tickets' && <Tickets />}
            {page === 'chats' && <Chats />}
            {page === 'sessions' && <Sessions />}
            {page === 'schedules' && <Schedules />}
            {page === 'team' && <Team />}
            {page === 'agents' && <Agents />}
            {page === 'cost' && <Cost />}
            {page === 'skills' && <Skills />}
            {page === 'activity' && <Activity />}
          </motion.div>
        </AnimatePresence>
      </main>

      {/* Phone: bottom tab bar, overflow under More */}
      {more && (
        <motion.div initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={spring}
          className="glass-bar sheet fixed inset-x-3 bottom-[5.25rem] z-20 grid grid-cols-2 gap-1 rounded-[1.5rem] p-2 md:hidden">
          {NAV.slice(PHONE_TABS).map(n => (
            <a key={n.id} href={`#/${n.id}`} aria-current={page === n.id ? 'page' : undefined}
              className={`flex min-h-12 items-center gap-2 rounded-2xl px-3 text-sm ${page === n.id ? 'bg-white/80 text-ink' : 'text-ink-2'}`}>
              <n.icon size={19} weight="light" aria-hidden="true" />{n.label}
            </a>
          ))}
        </motion.div>
      )}
      <nav aria-label="Main" className="glass-bar fixed inset-x-3 bottom-3 z-20 flex justify-around rounded-[1.5rem] px-1 py-1.5 pb-[max(0.375rem,env(safe-area-inset-bottom))] md:hidden">
        {NAV.slice(0, PHONE_TABS).map(n => {
          const active = page === n.id
          return (
            <a key={n.id} href={`#/${n.id}`} aria-current={active ? 'page' : undefined}
              className={`relative flex min-h-12 min-w-12 flex-col items-center justify-center gap-0.5 rounded-2xl px-2 text-[11px] ${active ? 'text-ink' : 'text-ink-3'}`}>
              {active && <motion.span layoutId="tab-pill" transition={spring} className="absolute inset-0 rounded-2xl bg-white/80" />}
              <n.icon size={20} weight={active ? 'regular' : 'light'} className="relative" aria-hidden="true" />
              {n.id === 'approvals' && waiting > 0 && <Badge n={waiting} className="absolute right-1 top-0.5" />}
              <span className="relative font-medium">{n.label}</span>
            </a>
          )
        })}
        <button onClick={() => setMore(m => !m)} aria-expanded={more}
          className={`relative flex min-h-12 min-w-12 cursor-pointer flex-col items-center justify-center gap-0.5 rounded-2xl px-2 text-[11px] ${NAV.slice(PHONE_TABS).some(n => n.id === page) || more ? 'text-ink' : 'text-ink-3'}`}>
          <DotsThreeOutline size={20} weight="light" aria-hidden="true" />
          <span className="font-medium">More</span>
        </button>
      </nav>
    </div>
  )
}

function Badge({ n, className = '' }: { n: number; className?: string }) {
  return <span aria-label={`${n} waiting`} className={`${className} min-w-5 rounded-full bg-navy px-1.5 text-center font-mono text-[11px] leading-5 text-white`}>{n > 99 ? '99+' : n}</span>
}
