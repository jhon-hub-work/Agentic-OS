import { X } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'

// Jharvis's panels can leave the dashboard: each opens alone at /?panel=<id>, in a small frameless desktop-app
// window (or a browser popup), on any monitor. While open it beats a heartbeat so Home hides that panel.
type Panel = { id: string; title: string; lines: string[] }
type Need = { title: string; detail?: string }
const clean = (t: string) => t.split('\n')[0].replace(/[^\x20-\x7E‘’“”–—]+/g, ' ').replace(/\s+/g, ' ').trim()
/** One "Waiting on you" item as a single line: Home pops the card out with these, its window keeps them live. */
export const needLine = (n: Need) => clean(n.title) + (n.detail ? ` — ${clean(n.detail)}` : '')
type Pos = { x: number; y: number }
type Win = {
  label: string; close(): Promise<void>; show(): Promise<void>; setPosition(p: unknown): Promise<void>; startDragging(): Promise<void>
  innerPosition(): Promise<Pos>; outerPosition(): Promise<Pos>; scaleFactor(): Promise<number>
  once(e: string, f: () => void): Promise<unknown>
}
const tauri = (window as unknown as { __TAURI__?: {
  window: { getCurrentWindow(): Win }
  webviewWindow: { WebviewWindow: new (label: string, o: Record<string, unknown>) => Win }
  dpi: { PhysicalPosition: new (x: number, y: number) => unknown }
} }).__TAURI__

const PLACE = 'aos-panel-place'  // where the last popped-out panel was left: physical px in the app, screen px in a browser
const poppedKey = (id: string) => `aos-popped:${id}`
const store = { get: (k: string) => { try { return localStorage.getItem(k) } catch { return null } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v) } catch { /* private window */ } },
  del: (k: string) => { try { localStorage.removeItem(k) } catch { /* private window */ } } }

/** True while that panel's own window is open (its heartbeat is under 6 s old). */
export const isPopped = (id: string) => Date.now() - Number(store.get(poppedKey(id)) ?? 0) < 6000

/** Opens the panel in its own window: at the pointer when it was dragged out, else where the last one was left. */
export async function popOut(p: Panel, at?: { clientX: number; clientY: number; screenX: number; screenY: number }) {
  store.set(poppedKey(p.id), String(Date.now()))
  const url = `${location.origin}/?panel=${encodeURIComponent(p.id)}`
  const width = 360, height = Math.min(640, 130 + p.lines.length * 30)
  let pos: Pos | null = (() => { try { return JSON.parse(store.get(PLACE) ?? 'null') } catch { return null } })()
  if (pos && !at) {  // others already open at the remembered spot: cascade so they don't stack exactly on top
    const keys = (() => { try { return Object.keys(localStorage) } catch { return [] } })()
    const open = keys.filter(k => k.startsWith('aos-popped:') && k !== poppedKey(p.id) && isPopped(k.slice(11))).length
    pos = { x: pos.x + open * 32, y: pos.y + open * 32 }
  }
  if (!tauri) {
    if (at) pos = { x: at.screenX - 40, y: at.screenY - 20 }  // the pointer lands on the header
    window.open(url, `panel-${p.id}`, `popup,width=${width},height=${height}${pos ? `,left=${pos.x},top=${pos.y}` : ''}`)
    return
  }
  const main = tauri.window.getCurrentWindow()
  if (at) {  // webview screenX is unreliable across monitors: window corner + pointer, in physical px
    const [o, s] = await Promise.all([main.innerPosition(), main.scaleFactor()])
    pos = { x: Math.round(o.x + (at.clientX - 40) * s), y: Math.round(o.y + (at.clientY - 20) * s) }
  }
  const win = new tauri.webviewWindow.WebviewWindow(`panel-${p.id.replace(/[^\w-]/g, '_')}`, {
    url, title: p.title, width, height, minWidth: 240, minHeight: 140, decorations: false, resizable: true, visible: !pos, center: !pos })
  // physical position after creation, so a monitor with another scale lands where it was left
  if (pos) { const at2 = pos; win.once('tauri://created', async () => {
    await win.setPosition(new tauri.dpi.PhysicalPosition(at2.x, at2.y)); await win.show()
    if (at) await win.startDragging().catch(() => {})  // dragged out: the mouse is still down, so the new window keeps following it
  }) }
}

/** /?panel=<id>: just that panel, live, in its own window. */
export default function PanelWindow({ id }: { id: string }) {
  const [panel, setPanel] = useState<Panel | null>(null)
  const [gone, setGone] = useState(false)
  const win = tauri?.window.getCurrentWindow()
  const close = () => { store.del(poppedKey(id)); if (win) win.close(); else window.close() }

  useEffect(() => {
    document.title = 'Jharvis panel'
    const theme = (e: StorageEvent) => { if (e.key === 'aos-theme') document.documentElement.classList.toggle('dark', e.newValue === 'dark') }
    addEventListener('storage', theme)
    let alive = true, last = ''
    const tick = async () => {
      try {
        let p: Panel | undefined
        if (id === 'needs') {  // the "Waiting on you" card: built from the OS's own list, not a Jharvis panel
          const r = await fetch('/api/jharvis/home')
          if (!r.ok || !alive) return
          const h = await r.json(), needs = [h.urgent, ...h.next].filter(Boolean)
          if (needs.length && !h.closed) p = { id, title: 'Waiting on you', lines: needs.map(needLine) }
        } else {
          const r = await fetch('/api/jharvis/panels')
          if (!r.ok || !alive) return
          p = ((await r.json()).panels as Panel[]).find(x => x.id === id)
        }
        if (!p) { alive = false; setGone(true); store.del(poppedKey(id)); setTimeout(close, 2500); return }
        setPanel(p)
        store.set(poppedKey(id), String(Date.now()))
        const at = win ? await win.outerPosition() : { x: screenX, y: screenY }
        const place = JSON.stringify({ x: at.x, y: at.y })
        if (place !== last) { last = place; store.set(PLACE, place) }  // only a moved window counts as "where it was left"
      } catch { /* the OS restarting */ }
    }
    tick()
    const t = setInterval(tick, 2000)
    addEventListener('pagehide', () => store.del(poppedKey(id)))
    return () => { alive = false; clearInterval(t); removeEventListener('storage', theme) }
  }, [id])  // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="h-[100dvh] p-2">
      <div className="frost-mesh" aria-hidden="true" />
      <section aria-label={panel?.title ?? 'Jharvis panel'} className="liquid-glass flex h-full flex-col rounded-[1.75rem]">
        <header data-tauri-drag-region className="relative flex shrink-0 cursor-grab items-start gap-2 px-5 pb-3 pt-5 active:cursor-grabbing">
          <h1 data-tauri-drag-region className="flex-1 pr-8 text-[15px] font-medium tracking-tight text-ink">{gone ? 'Panel closed' : panel?.title ?? 'Loading…'}</h1>
          <button type="button" aria-label="Close window" onClick={close}
            className="absolute right-3 top-3 flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-ink-3 transition-colors hover:bg-white/60 hover:text-ink">
            <X size={12} weight="bold" aria-hidden="true" />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-auto px-5 pb-5">
          {gone ? <p className="text-sm text-ink-2">Jharvis closed this panel. This window closes now.</p> : (
            <ul className="flex flex-col gap-2">
              {panel?.lines.map((l, k) => <li key={k} className="text-sm leading-snug text-ink-2">{l}</li>)}
            </ul>
          )}
        </div>
      </section>
    </div>
  )
}
