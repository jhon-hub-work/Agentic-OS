import { Minus, Square, X } from '@phosphor-icons/react'

type TauriWindow = { minimize(): Promise<void>; toggleMaximize(): Promise<void>; close(): Promise<void> }
const tauri = (window as unknown as { __TAURI__?: { window: { getCurrentWindow(): TauriWindow } } }).__TAURI__
export const inApp = !!tauri

/** The desktop app has no Windows title bar: an invisible strip to drag (double-click maximizes) and
 *  small window buttons that fade in when the pointer is near the top-right. Nothing shows in a browser. */
export default function TitleBar() {
  if (!tauri) return null
  const win = tauri.window.getCurrentWindow()
  const btn = 'flex h-8 w-11 cursor-pointer items-center justify-center text-ink-2 transition-colors hover:bg-white/60 hover:text-ink'
  return (
    <>
      <div data-tauri-drag-region className="fixed inset-x-0 top-0 z-20 h-8" />
      <div className="group fixed right-0 top-0 z-40 flex h-8 w-40 justify-end">
        <div className="flex opacity-0 transition-opacity duration-200 group-hover:opacity-100 focus-within:opacity-100">
          <button onClick={() => win.minimize()} aria-label="Minimize" className={btn}><Minus size={14} /></button>
          <button onClick={() => win.toggleMaximize()} aria-label="Maximize" className={btn}><Square size={12} /></button>
          <button onClick={() => win.close()} aria-label="Close" className={`${btn} hover:!bg-win-close hover:!text-white`}><X size={14} /></button>
        </div>
      </div>
    </>
  )
}
