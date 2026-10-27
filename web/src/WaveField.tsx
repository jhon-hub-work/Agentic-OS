import { memo, useEffect, useRef } from 'react'
import * as THREE from 'three'

/** Jharvis's "voice": a particle wave field (voice-recorder style), in Glacier colours.
 *  Follows the pointer; `pulse` changing sends a ring out (e.g. when the urgent task changes). */
function WaveField({ pulse = 0, energy, mood, className = '' }: { pulse?: number; energy?: { current: number }; mood?: { current: string }; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  const smooth = useRef(0)
  const ripple = useRef(-10)

  useEffect(() => { ripple.current = performance.now() / 1000 }, [pulse])

  useEffect(() => {
    const canvas = ref.current!
    const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true })
    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100)
    camera.position.set(0, 3.2, 9.5)
    camera.lookAt(0, -0.4, 0)

    const COLS = 140, ROWS = 64, W = 30, D = 13, n = COLS * ROWS
    const pos = new Float32Array(n * 3), col = new Float32Array(n * 4)  // rgba: alpha fades the edges
    // palettes (edge, middle, edge): blue = normal, green = good news, red = urgent; then the same three for dark mode
    const palettes = [['#5B6F86', '#3FB6EC', '#1F2D44'], ['#4E7A64', '#34C77B', '#17503A'], ['#86545B', '#EC4F57', '#5A1622'],
      ['#5E7EA0', '#4FC3F7', '#9FDDF7'], ['#5E9C80', '#4CD98F', '#A8F0C8'], ['#A0606A', '#FF6B72', '#FFB3B7']]
    const target = () => (mood?.current === 'good' ? 1 : mood?.current === 'urgent' ? 2 : 0) + (document.documentElement.classList.contains('dark') ? 3 : 0)
    const cols = palettes.map(() => new Float32Array(n * 3)), tmp = new THREE.Color()
    for (let r = 0, i = 0; r < ROWS; r++) for (let c = 0; c < COLS; c++, i++) {
      pos[i * 3] = (c / (COLS - 1) - 0.5) * W
      pos[i * 3 + 2] = (r / (ROWS - 1) - 0.5) * D
      const u = c / (COLS - 1), v = r / (ROWS - 1)
      // soft edges baked into each dot (a CSS mask over the canvas cost a repaint every frame)
      col[i * 4 + 3] = THREE.MathUtils.smoothstep(Math.min(u, 1 - u), 0, 0.3) * THREE.MathUtils.smoothstep(Math.min(v, 1 - v), 0, 0.22)
      palettes.forEach(([a, b, d], k) => {
        tmp.set(u < 0.5 ? a : b).lerp(new THREE.Color(u < 0.5 ? b : d), u < 0.5 ? u * 2 : (u - 0.5) * 2)
        cols[k].set([tmp.r, tmp.g, tmp.b], i * 3)
      })
    }
    const start = target()
    for (let i = 0; i < n; i++) for (let ch = 0; ch < 3; ch++) col[i * 4 + ch] = cols[start][i * 3 + ch]
    const mix = palettes.map((_, k) => k === start ? 1 : 0)
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    geo.setAttribute('color', new THREE.BufferAttribute(col, 4))
    // soft round sprite so points read as glowing dots, not squares
    const sprite = document.createElement('canvas')
    sprite.width = sprite.height = 64
    const g = sprite.getContext('2d')!, grad = g.createRadialGradient(32, 32, 0, 32, 32, 32)
    grad.addColorStop(0, 'rgba(255,255,255,1)'); grad.addColorStop(0.4, 'rgba(255,255,255,.7)'); grad.addColorStop(1, 'rgba(255,255,255,0)')
    g.fillStyle = grad; g.fillRect(0, 0, 64, 64)
    const tex = new THREE.CanvasTexture(sprite)
    const mat = new THREE.PointsMaterial({ size: 0.15, map: tex, vertexColors: true, transparent: true, depthWrite: false })
    const points = new THREE.Points(geo, mat)
    scene.add(points)

    const mouse = { x: 0, y: 0, tx: 0, ty: 0 }
    const onMove = (e: PointerEvent) => { mouse.tx = e.clientX / innerWidth - 0.5; mouse.ty = e.clientY / innerHeight - 0.5 }
    addEventListener('pointermove', onMove)

    const size = () => {
      const w = canvas.clientWidth, h = canvas.clientHeight
      if (!w || !h) return
      renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5))  // full-screen canvas: 2x stuttered
      renderer.setSize(w, h, false)
      camera.aspect = w / h
      camera.updateProjectionMatrix()
    }
    const frame = (t: number) => {
      mouse.x += (mouse.tx - mouse.x) * 0.05
      mouse.y += (mouse.ty - mouse.y) * 0.05
      const mx = mouse.x * W, since = t - ripple.current
      // voice: the owner's mic level or Jharvis speaking swells the waves (read from a ref, no re-render)
      smooth.current += ((energy?.current ?? 0) - smooth.current) * 0.15
      const amp = 1 + smooth.current * 4.5
      for (let i = 0; i < n; i++) {
        const x = pos[i * 3], z = pos[i * 3 + 2]
        let y = (Math.sin(x * 0.55 + t * 0.9) * 0.35 + Math.sin(x * 1.3 - t * 1.4 + z * 0.8) * 0.18 * amp + Math.cos(z * 1.1 + t * 0.6) * 0.12) * (0.75 + amp * 0.25) * 1.8
        y += Math.exp(-((x - mx) ** 2) / 6) * (0.6 + mouse.y * -0.6)
        const d = Math.hypot(x, z * 2)
        if (since < 3) y += Math.sin(d * 2 - since * 9) * Math.exp(-since * 1.4) * Math.exp(-Math.abs(d - since * 4.5) * 0.8) * 0.9
        pos[i * 3 + 1] = y
      }
      geo.attributes.position.needsUpdate = true
      // ease toward the mood's palette (about a second)
      const want = target()
      let moved = false
      for (let k = 0; k < palettes.length; k++) {
        const d = (k === want ? 1 : 0) - mix[k]
        if (Math.abs(d) > 0.002) { mix[k] += d * 0.05; moved = true }
      }
      if (moved) {
        for (let i = 0; i < n; i++) for (let ch = 0; ch < 3; ch++) {
          const j = i * 3 + ch
          let v = 0
          for (let k = 0; k < cols.length; k++) if (mix[k]) v += cols[k][j] * mix[k]
          col[i * 4 + ch] = v
        }
        geo.attributes.color.needsUpdate = true
      }
      points.rotation.y = mouse.x * 0.25
      points.rotation.x = mouse.y * 0.08
      renderer.render(scene, camera)
    }

    size()
    const ro = new ResizeObserver(size)
    ro.observe(canvas)
    let raf = 0, visible = true
    const io = new IntersectionObserver(([e]) => { visible = e.isIntersecting })
    io.observe(canvas)
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) frame(1)
    else {
      const loop = (ms: number) => { if (visible && !document.hidden) frame(ms / 1000); raf = requestAnimationFrame(loop) }
      raf = requestAnimationFrame(loop)
    }
    return () => {
      cancelAnimationFrame(raf); ro.disconnect(); io.disconnect()
      removeEventListener('pointermove', onMove)
      geo.dispose(); mat.dispose(); tex.dispose(); renderer.dispose()
    }
  }, [])

  return <canvas ref={ref} aria-hidden="true" className={className} />
}

export default memo(WaveField)
