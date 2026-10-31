import { useRef } from 'react'
import whooshIn from './sfx/whoosh-in.mp3'
import whooshOut from './sfx/whoosh-out.mp3'

/** Light/dark switch: a black hole. To dark, the light page is vacuumed into it (bends, gathers into cloth
 *  folds, slips in), then the dark interface opens out of it. To light, it blows the page back out.
 *  Approved design: design/theme-switch-demo.html. */

const STEPS = 32, FOLDS = 14
const reduce = () => matchMedia('(prefers-reduced-motion: reduce)').matches
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))

function setTheme(dark: boolean) {
  document.documentElement.classList.toggle('dark', dark)
  try { localStorage.setItem('aos-theme', dark ? 'dark' : 'light') } catch { /* private mode */ }
}

// Pull field: each pixel samples the page further from the hole, so everything streams in, gathering into
// curved folds. The browser doesn't redraw a filter when its numbers change, so STEPS strengths are made up
// front and each frame switches which one the page uses. y0 = scroll offset (the filter sits on <body>).
function buildField(svg: SVGSVGElement, hx: number, hy: number, y0: number) {
  const W = innerWidth, H = innerHeight, k = 4, w = Math.ceil(W / k), h = Math.ceil(H / k)
  const cv = document.createElement('canvas'); cv.width = w; cv.height = h
  const g = cv.getContext('2d')!, img = g.createImageData(w, h), far = Math.hypot(Math.max(hx, W - hx), Math.max(hy, H - hy))
  const cs = Math.cos(0.45), sn = Math.sin(0.45)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const dx = x * k - hx, dy = y * k - hy, d = Math.hypot(dx, dy) || 1
    const f = Math.pow(1 - Math.min(1, d / far), 1.6) * 0.9 + 0.1                 // pull strength
    const ux = (dx * cs - dy * sn) / d, uy = (dx * sn + dy * cs) / d               // away from the hole, twisted a bit
    const fold = Math.sin(Math.atan2(dy, dx) * FOLDS + d / far * 5), gather = 0.35 * fold * Math.min(1, f * 1.6)
    const vx = ux * f - uy * gather, vy = uy * f + ux * gather, i = (y * w + x) * 4
    img.data[i] = 128 + Math.max(-1, Math.min(1, vx)) * 127; img.data[i + 1] = 128 + Math.max(-1, Math.min(1, vy)) * 127
    img.data[i + 2] = 128 + fold * Math.min(1, f * 1.6) * 127; img.data[i + 3] = 255  // blue = fold height, for shading
  }
  g.putImageData(img, 0, 0)
  const url = cv.toDataURL(), pull = Math.max(W, H) * 1.3
  svg.innerHTML = Array.from({ length: STEPS }, (_, s) => {
    const p = s / (STEPS - 1), scale = (pull * p ** 1.3).toFixed(1)
    return `<filter id="suck${s}" filterUnits="userSpaceOnUse" primitiveUnits="userSpaceOnUse" x="0" y="${y0}" width="${W}" height="${H}" color-interpolation-filters="sRGB">` +
      `<feImage href="${url}" x="0" y="${y0}" width="${W}" height="${H}" preserveAspectRatio="none" result="f"/>` +
      `<feDisplacementMap in="SourceGraphic" in2="f" scale="${scale}" xChannelSelector="R" yChannelSelector="G" result="bent"/>` +
      `<feColorMatrix in="f" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 1 0 0" result="h"/>` +
      `<feDisplacementMap in="h" in2="f" scale="${scale}" xChannelSelector="R" yChannelSelector="G" result="hb"/>` +
      `<feDiffuseLighting in="hb" surfaceScale="${(9 * p).toFixed(2)}" lighting-color="#fff" result="shade"><feDistantLight azimuth="235" elevation="38"/></feDiffuseLighting>` +
      `<feComposite in="bent" in2="shade" operator="arithmetic" k1="${(1.6 * p).toFixed(3)}" k2="${(1 - p).toFixed(3)}" k3="0" k4="0" result="lit"/>` +
      `<feComposite in="lit" in2="bent" operator="in"/></filter>`
  }).join('')
  return far
}

// the moment the hole eats (or is about to spit): a bright flare and a ring wave across the page
function swallowFx(hole: HTMLElement) {
  const r = hole.getBoundingClientRect(), ring = document.createElement('div')
  ring.style.cssText = `position:fixed;left:${r.left + r.width / 2 - 20}px;top:${r.top + r.height / 2 - 20}px;width:40px;height:40px;border-radius:50%;pointer-events:none;z-index:60;border:2px solid rgba(159,221,247,.9);box-shadow:0 0 24px rgba(159,221,247,.6), inset 0 0 18px rgba(255,230,180,.5)`
  document.body.append(ring)
  ring.animate([{ transform: 'scale(.3)', opacity: 1 }, { transform: `scale(${Math.hypot(innerWidth, innerHeight) / 30})`, opacity: 0 }],
    { duration: 900, easing: 'cubic-bezier(.16,1,.3,1)' }).finished.then(() => ring.remove())
  hole.querySelector('.bh-core')?.animate([{ boxShadow: '0 0 0 0 rgba(255,236,190,0)' }, { boxShadow: '0 0 46px 22px rgba(255,236,190,.95)', offset: .25 }, { boxShadow: '0 0 0 0 rgba(255,236,190,0)' }],
    { duration: 650, easing: 'ease-out' })
}

// a small whoosh on tap (Jhon 30 Oct): swells into the swallow going dark, blows outward going light (earshot
// whoosh_fast/sleeve, checked clean). Quiet; a blocked play (no sound allowed) is ignored.
function whoosh(dark: boolean) {
  const a = new Audio(dark ? whooshIn : whooshOut)
  a.volume = 0.35
  a.play().catch(() => {})
}

export default function ThemeSwitch({ className = '' }: { className?: string }) {
  const btn = useRef<HTMLButtonElement>(null)
  const svg = useRef<SVGSVGElement>(null)
  const busy = useRef(false)

  async function toggle() {
    const hole = btn.current!, root = document.documentElement, body = document.body
    if (busy.current) return
    const dark = !root.classList.contains('dark')
    whoosh(dark)
    if (!document.startViewTransition || reduce()) { setTheme(dark); return }
    busy.current = true
    hole.classList.add('bh-busy')
    const r = hole.getBoundingClientRect(), sy = scrollY, hx = r.left + r.width / 2, hy = r.top + r.height / 2
    const far = buildField(svg.current!, hx, hy, sy)
    const at = `${hx}px ${hy + sy}px`  // <body> coordinates
    // A filter on <body> makes fixed elements scroll with it; nudge them back so nothing jumps.
    const fixed = [...body.querySelectorAll<HTMLElement>('*')].filter(e => getComputedStyle(e).position === 'fixed')
    const pin = (on: boolean) => fixed.forEach(e => { e.style.translate = on && sy ? `0 ${sy}px` : '' })
    const frames: Keyframe[] = [
      { transform: 'scale(1)', clipPath: `circle(${far}px at ${at})`, opacity: 1, transformOrigin: at, easing: 'linear' },
      { transform: 'scale(.92)', clipPath: `circle(${far}px at ${at})`, opacity: 1, transformOrigin: at, offset: .35, easing: 'linear' },
      { transform: 'scale(.45)', clipPath: `circle(${far * .45}px at ${at})`, opacity: 1, transformOrigin: at, offset: .8, easing: 'linear' },
      { transform: 'scale(0)', clipPath: `circle(0px at ${at})`, opacity: .6, transformOrigin: at },
    ]
    const warp = (p: number) => p * p * (3 - 2 * p)  // smoothstep: eases in and out of full suction
    const use = (w: number) => root.style.setProperty('--suck', w > 0 ? `url(#suck${Math.round(w * (STEPS - 1))})` : 'none')
    const follow = (anim: Animation, forward: boolean) => new Promise<void>(done => {
      const tick = () => {
        const p = Math.min(1, anim.effect?.getComputedTiming().progress ?? 1)
        use(warp(forward ? p : 1 - p))
        if (p < 1 && anim.playState === 'running') requestAnimationFrame(tick); else done()
      }
      requestAnimationFrame(tick)
    })
    try {
      if (dark) {
        // the real light page is vacuumed into the hole over darkness...
        const dur = 900
        root.style.background = 'var(--color-night)'
        pin(true); body.classList.add('sucking')
        const anim = body.animate(frames, { duration: dur, fill: 'forwards', easing: 'cubic-bezier(.55,.05,.85,.35)' })
        hole.animate([{ transform: 'scale(1)' }, { transform: 'scale(1.2)', offset: .5 }, { transform: 'scale(1.45)', offset: .9 }, { transform: 'scale(1)' }], { duration: dur, easing: 'ease-in-out' })
        setTimeout(() => swallowFx(hole), dur * .88)
        await follow(anim, true)
        // ...then the dark interface opens out of the hole
        const t = document.startViewTransition(() => { anim.cancel(); body.classList.remove('sucking'); pin(false); use(0); root.style.background = ''; setTheme(true) })
        await t.ready
        root.animate([{ clipPath: `circle(0px at ${hx}px ${hy}px)` }, { clipPath: `circle(${far}px at ${hx}px ${hy}px)` }],
          { duration: 320, easing: 'cubic-bezier(.16,1,.3,1)', pseudoElement: '::view-transition-new(root)' })
        await t.finished
      } else {
        // the hole gathers itself, then blows the light page back out, unfolding as it spreads
        swallowFx(hole); await sleep(140)
        const dur = 850
        const back = frames.slice().reverse().map((f, i, a) => ({ ...f, transformOrigin: `${hx}px ${hy}px`,
          clipPath: String(f.clipPath).replace(at, `${hx}px ${hy}px`),
          offset: i === 0 ? 0 : i === a.length - 1 ? 1 : 1 - (frames[a.length - 1 - i].offset as number), easing: 'linear' }))
        // warp only the new light page: switched on inside the update, after the dark page was captured
        const t = document.startViewTransition(() => { use(1); pin(true); body.classList.add('sucking'); setTheme(false) })
        await t.ready
        const anim = root.animate(back, { duration: dur, fill: 'forwards', easing: 'cubic-bezier(.15,.65,.45,1)', pseudoElement: '::view-transition-new(root)' })
        hole.animate([{ transform: 'scale(1.4)' }, { transform: 'scale(.9)', offset: .2 }, { transform: 'scale(1)' }], { duration: dur, easing: 'ease-in-out' })
        await follow(anim, false)
        await t.finished
        anim.cancel()
      }
    } finally {
      // whatever happened, leave the page clean and in the chosen theme
      body.getAnimations().forEach(a => a.cancel())
      body.classList.remove('sucking'); pin(false); use(0); root.style.background = ''
      if (root.classList.contains('dark') !== dark) setTheme(dark)
      hole.classList.remove('bh-busy'); busy.current = false
    }
  }

  return (
    <>
      <svg ref={svg} width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true" />
      <button ref={btn} onClick={toggle} className={`black-hole ${className}`}
        aria-label="Switch between light and dark mode" title="Light / dark">
        <span className="bh-back" /><span className="bh-arc" /><span className="bh-core" /><span className="bh-front" />
      </button>
    </>
  )
}
