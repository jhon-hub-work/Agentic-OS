import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import PanelWindow from './PanelWindow'
import { loadAgents } from './api'
import { Unpaired, pairFromHash } from './Phone'

// /?panel=<id> is one of Jharvis's panels popped out into its own window
const panel = new URLSearchParams(location.search).get('panel')
let unpaired = false  // a phone without the aos_token cookie
// the OS may be down: render anyway with the built-in four
pairFromHash().then(loadAgents).catch(e => { unpaired = /\(401\)/.test(String(e?.message)) }).finally(() => createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {panel ? <PanelWindow id={panel} /> : unpaired ? <Unpaired /> : <App />}
  </StrictMode>,
))
addEventListener('hashchange', () => { if (location.hash.startsWith('#pair=')) location.reload() })  // QR opened over an already-open tab
// lets Android offer "Install"; caches nothing (server.py SERVICE_WORKER_JS)
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {})
