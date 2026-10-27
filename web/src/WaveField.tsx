import { memo, useEffect, useRef } from 'react'
import * as THREE from 'three'

/** Jharvis's "voice": a particle wave field (voice-recorder style), in Glacier colours.
 *  Follows the pointer; `pulse` changing sends a ring out (e.g. when the urgent task changes). */
function WaveField({ pulse = 0, energy, className = '' }: { pulse?: number; energy?: { current: number }; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  const smooth = useRef(0)
  const ripple = useRef(-10)

  useEffect(() => { ripple.current = performance.now() / 1000 }, [pulse])

  useEffect(() => {
    const canvas = ref.current!
    const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true })
    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100)
    camera.position.set(0, 2.4, 7)
    camera.lookAt(0, -0.6, 0)

    const COLS = 140, ROWS = 40, W = 22, D = 7, n = COLS * ROWS
    const pos = new Float32Array(n * 3), col = new Float32Array(n * 3)
    const navy = new THREE.Color('#1F2D44'), sky = new THREE.Color('#3FB6EC'), steel = new THREE.Color('#5B6F86'), tmp = new THREE.Color()
    for (let r = 0, i = 0; r < ROWS; r++) for (let c = 0; c < COLS; c++, i++) {
      pos[i * 3] = (c / (COLS - 1) - 0.5) * W
      pos[i * 3 + 2] = (r / (ROWS - 1) - 0.5) * D
      const u = c / (COLS - 1)
      tmp.copy(u < 0.5 ? steel : sky).lerp(u < 0.5 ? sky : navy, u < 0.5 ? u * 2 : (u - 0.5) * 2)
      col.set([tmp.r, tmp.g, tmp.b], i * 3)
    }
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3))
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
      renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
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
      const amp = 1 + smooth.current * 2.6
      for (let i = 0; i < n; i++) {
        const x = pos[i * 3], z = pos[i * 3 + 2]
        let y = (Math.sin(x * 0.55 + t * 0.9) * 0.35 + Math.sin(x * 1.3 - t * 1.4 + z * 0.8) * 0.18 * amp + Math.cos(z * 1.1 + t * 0.6) * 0.12) * (0.75 + amp * 0.25)
        y += Math.exp(-((x - mx) ** 2) / 6) * (0.6 + mouse.y * -0.6)
        const d = Math.hypot(x, z * 2)
        if (since < 3) y += Math.sin(d * 2 - since * 9) * Math.exp(-since * 1.4) * Math.exp(-Math.abs(d - since * 4.5) * 0.8) * 0.9
        pos[i * 3 + 1] = y
      }
      geo.attributes.position.needsUpdate = true
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
