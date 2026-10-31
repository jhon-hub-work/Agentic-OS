import { memo, useEffect, useRef } from 'react'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { MeshSurfaceSampler } from 'three/examples/jsm/math/MeshSurfaceSampler.js'
import handUrl from './models/hand.glb?url'  // credits: models/CREDITS.md
import headUrl from './models/head.glb?url'

/** A shape Jharvis asks his dots to form: a ready one by name, or his own drawing (SVG paths in a 100 x 100 box). */
export type DotShape = { id: string; name?: string; paths?: string[]; fill?: boolean }

const PRESETS: Record<string, { paths: string[]; fill: boolean }> = {
  heart: { paths: ['M50 88 C20 66 6 48 6 32 C6 18 17 8 30 8 C40 8 47 14 50 22 C53 14 60 8 70 8 C83 8 94 18 94 32 C94 48 80 66 50 88 Z'], fill: true },
  check: { paths: ['M14 52 L40 78 L88 24'], fill: false },
  star: { paths: ['M50 6 L61 38 L95 38 L67 58 L78 92 L50 72 L22 92 L33 58 L5 38 L39 38 Z'], fill: true },
}

/** A 3D shape as dots on its surface: positions and outward normals, centred, about -1..1, y up.
 *  dense = a model (head, hand) that needs many small dots to read; jaw = how far each dot drops when he talks;
 *  size / lift = bigger / lower than the standard shape (the head stands with its shoulders in the wave). */
type Cloud = { p: Float32Array; n: Float32Array; count: number; dense: boolean; orb?: boolean; jaw?: Float32Array; size?: number; lift?: number; unit?: number }
const MAX = 6500  // most dots a shape may use

/** Keeps candidates (in random order) at least d apart, so dots sit evenly like the wave's. */
function thin(p: ArrayLike<number>, n: ArrayLike<number>, d: number, dense: boolean): Cloud {
  const cells = new Map<string, number[]>(), kp: number[] = [], kn: number[] = [], d2 = d * d
  for (let i = 0; i < p.length / 3 && kp.length / 3 < MAX; i++) {
    const x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2], cx = Math.floor(x / d), cy = Math.floor(y / d), cz = Math.floor(z / d)
    let ok = true
    for (let a = -1; a <= 1 && ok; a++) for (let b = -1; b <= 1 && ok; b++) for (let c = -1; c <= 1 && ok; c++)
      for (const j of cells.get(`${cx + a},${cy + b},${cz + c}`) ?? []) if ((kp[j] - x) ** 2 + (kp[j + 1] - y) ** 2 + (kp[j + 2] - z) ** 2 < d2) { ok = false; break }
    if (!ok) continue
    const key = `${cx},${cy},${cz}`
    if (!cells.has(key)) cells.set(key, [])
    cells.get(key)!.push(kp.length)
    kp.push(x, y, z); kn.push(n[i * 3], n[i * 3 + 1], n[i * 3 + 2])
  }
  return { p: Float32Array.from(kp), n: Float32Array.from(kn), count: kp.length / 3, dense }
}

/** Even dots on a sphere, d apart. */
function sphereCloud(d: number, r = 1): Cloud {
  const N = Math.round((4 * Math.PI * r * r) / (d * d)), p = new Float32Array(N * 3), n = new Float32Array(N * 3)
  for (let k = 0; k < N; k++) {
    const yy = 1 - (2 * (k + 0.5)) / N, rr = Math.sqrt(1 - yy * yy), th = k * 2.39996
    n.set([Math.cos(th) * rr, yy, Math.sin(th) * rr], k * 3)
    p.set([n[k * 3] * r, yy * r, n[k * 3 + 2] * r], k * 3)
  }
  return { p, n, count: N, dense: false, orb: true }
}

/** A flat drawing puffed up into 3D, like a cushion: rounded edges, about half as thick as it is wide, so the outline
 *  still reads from the front (a line becomes a tube). Dots d apart on its surface. Null if nothing to trace. */
function inflate(paths: string[], fill: boolean, d: number): Cloud | null {
  const R = 160, M = 10, sc = (R - 2 * M) / 100
  const cv = document.createElement('canvas')
  cv.width = cv.height = R
  const ctx = cv.getContext('2d', { willReadFrequently: true })!
  ctx.setTransform(sc, 0, 0, sc, M, M)
  ctx.lineWidth = 5; ctx.lineCap = 'round'; ctx.lineJoin = 'round'
  for (const s of paths) { const p = new Path2D(s); if (fill) ctx.fill(p); ctx.stroke(p) }  // stroke too, so thin parts of a fill survive
  const px = ctx.getImageData(0, 0, R, R).data, dist = new Float32Array(R * R)
  for (let y = 0; y < R; y++) for (let x = 0; x < R; x++) {
    const i = y * R + x
    dist[i] = x && y && x < R - 1 && y < R - 1 && px[i * 4 + 3] > 127 ? 1e9 : 0
  }
  // distance from each inked pixel to the outline (two-pass chamfer)
  for (let y = 1; y < R - 1; y++) for (let x = 1; x < R - 1; x++) {
    const i = y * R + x
    if (dist[i]) dist[i] = Math.min(dist[i], dist[i - 1] + 1, dist[i - R] + 1, dist[i - R - 1] + 1.414, dist[i - R + 1] + 1.414)
  }
  for (let y = R - 2; y > 0; y--) for (let x = R - 2; x > 0; x--) {
    const i = y * R + x
    if (dist[i]) dist[i] = Math.min(dist[i], dist[i + 1] + 1, dist[i + R] + 1, dist[i + R + 1] + 1.414, dist[i + R - 1] + 1.414)
  }
  let dmax = 0, x0 = R, x1 = 0, y0 = R, y1 = 0
  for (let i = 0; i < R * R; i++) if (dist[i]) {
    const x = i % R, y = (i / R) | 0
    dmax = Math.max(dmax, dist[i]); x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y)
  }
  if (!dmax) return null
  const h = (i: number) => dist[i] ? 0.6 * Math.sqrt(Math.max(0, dmax * dmax - (dmax - Math.min(dist[i], dmax)) ** 2)) : 0
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, unit = 2 / Math.max(x1 - x0, y1 - y0, 2 * dmax), P: number[] = [], N: number[] = []
  for (let tries = 0; tries < 300000; tries++) {
    const x = x0 + Math.random() * (x1 - x0 + 1), y = y0 + Math.random() * (y1 - y0 + 1), i = (y | 0) * R + (x | 0)
    if (!dist[i]) continue
    const hx = (h(i + 1) - h(i - 1)) / 2, hy = (h(i + R) - h(i - R)) / 2
    if (Math.random() * 4 > Math.sqrt(1 + hx * hx + hy * hy)) continue  // steep sides have more surface: keep more there
    const s = Math.random() < 0.5 ? 1 : -1, l = Math.hypot(hx, hy, 1)
    P.push((x - cx) * unit, -(y - cy) * unit, s * h(i) * unit)  // canvas y runs down, ours up
    N.push(-hx / l, hy / l, s / l)
  }
  return thin(P, N, d, true)
}

/** Dots spread evenly over a model's surface (about `count`), centred and scaled to about -1..1.
 *  cut drops everything below that height (in -1..1), e.g. the shoulders of a head scan, and re-centres the rest.
 *  unit = how much the model was scaled, so two clouds of one model can be compared. */
function modelCloud(root: THREE.Object3D, count: number, turn: THREE.Euler, cut = -Infinity): Cloud | null {
  root.updateMatrixWorld(true)
  let mesh: THREE.Mesh | undefined
  root.traverse(o => { if (!mesh && (o as THREE.Mesh).isMesh) mesh = o as THREE.Mesh })
  if (!mesh) return null
  const geo = mesh.geometry.clone()
  geo.applyMatrix4(mesh.matrixWorld)
  geo.applyMatrix4(new THREE.Matrix4().makeRotationFromEuler(turn))
  if (!geo.attributes.normal) geo.computeVertexNormals()
  geo.computeBoundingBox()
  const b = geo.boundingBox!, c = b.getCenter(new THREE.Vector3()), sz = b.getSize(new THREE.Vector3()), sc = 2 / Math.max(sz.x, sz.y, sz.z)
  geo.translate(-c.x, -c.y, -c.z)
  geo.scale(sc, sc, sc)
  const at = geo.attributes.position, ix = geo.index, va = new THREE.Vector3(), vb = new THREE.Vector3(), vc = new THREE.Vector3()
  let area = 0
  for (let f = 0; f < (ix ? ix.count : at.count) / 3; f++) {
    const [i0, i1, i2] = [0, 1, 2].map(j => ix ? ix.getX(f * 3 + j) : f * 3 + j)
    va.fromBufferAttribute(at, i0); vb.fromBufferAttribute(at, i1); vc.fromBufferAttribute(at, i2)
    area += vb.sub(va).cross(vc.sub(va)).length() / 2
  }
  const sampler = new MeshSurfaceSampler(new THREE.Mesh(geo)).build(), p = new THREE.Vector3(), nn = new THREE.Vector3()
  const P: number[] = [], N: number[] = [], lo = [9, 9, 9], hi = [-9, -9, -9]
  for (let k = 0; k < count * 12; k++) {
    sampler.sample(p, nn)
    if (p.y < cut) continue
    P.push(p.x, p.y, p.z); N.push(nn.x, nn.y, nn.z)
    ;[p.x, p.y, p.z].forEach((v, j) => { lo[j] = Math.min(lo[j], v); hi[j] = Math.max(hi[j], v) })
  }
  geo.dispose()
  const s2 = 2 / Math.max(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]), mid = lo.map((v, j) => (v + hi[j]) / 2)
  for (let i = 0; i < P.length; i++) P[i] = (P[i] - mid[i % 3]) * s2
  const kept = area * (P.length / 3 / (count * 12)) * s2 * s2  // the surface left after the cut, rescaled
  return { ...thin(P, N, Math.sqrt(kept / count) * 0.8, true), unit: sc * s2 }
}

/** The jaw of a head cloud: dots below the nose tip, at the front, drop (by up to u) when he talks. u = the head's size (1 = the face). */
function addJaw(c: Cloud, u: number) {
  let tip = 0
  for (let k = 1; k < c.count; k++) if (c.p[k * 3 + 2] > c.p[tip * 3 + 2]) tip = k
  const ny = c.p[tip * 3 + 1], nz = c.p[tip * 3 + 2], jaw = new Float32Array(c.count)
  for (let k = 0; k < c.count; k++) {
    const x = c.p[k * 3], y = c.p[k * 3 + 1], z = c.p[k * 3 + 2], below = ny - 0.2 * u - y
    if (below > 0 && below < 0.5 * u && z > nz - 0.55 * u && Math.abs(x) < 0.45 * u) jaw[k] = u * Math.min(1, below / (0.12 * u)) * (1 - Math.abs(x) / (0.45 * u))
  }
  c.jaw = jaw
}

/** Two clouds as one (a hand holding a ball). */
function joinClouds(a: Cloud, b: Cloud, at: [number, number, number]): Cloud {
  const p = new Float32Array((a.count + b.count) * 3), n = new Float32Array((a.count + b.count) * 3)
  p.set(a.p); n.set(a.n); n.set(b.n, a.count * 3)
  for (let k = 0; k < b.count; k++) for (let j = 0; j < 3; j++) p[(a.count + k) * 3 + j] = b.p[k * 3 + j] + at[j]
  return { p, n, count: Math.min(a.count + b.count, MAX), dense: true }
}

/** Each dot joined to its nearest neighbour going round the shape (sideways along the surface), so the links run in
 *  strokes like contour lines on a line drawing: pairs (a, b) and each pair's length. */
function linksOf(c: Cloud): { ab: Uint32Array; rest: Float32Array } {
  const at = (i: number) => [c.p[i * 3], c.p[i * 3 + 1], c.p[i * 3 + 2]]
  const d2 = (i: number, j: number) => (c.p[i * 3] - c.p[j * 3]) ** 2 + (c.p[i * 3 + 1] - c.p[j * 3 + 1]) ** 2 + (c.p[i * 3 + 2] - c.p[j * 3 + 2]) ** 2
  // the dots' spacing: median distance to the nearest one, from a sample
  const near: number[] = []
  for (let s = 0; s < 150; s++) {
    const i = Math.floor(Math.random() * c.count)
    let m = Infinity
    for (let j = 0; j < c.count; j++) if (j !== i) m = Math.min(m, d2(i, j))
    near.push(Math.sqrt(m))
  }
  const R = near.sort((a, b) => a - b)[near.length >> 1] * 1.8, cell = (v: number) => Math.floor(v / R), grid = new Map<string, number[]>()
  for (let i = 0; i < c.count; i++) {
    const key = at(i).map(cell).join()
    if (!grid.has(key)) grid.set(key, [])
    grid.get(key)!.push(i)
  }
  const ab: number[] = [], rest: number[] = [], up = new THREE.Vector3(0, 1, 0), tg = new THREE.Vector3(), nv = new THREE.Vector3(), v = new THREE.Vector3()
  for (let i = 0; i < c.count; i++) {
    // the way round: across the normal, level (straight across where the normal points up, e.g. the crown)
    tg.crossVectors(nv.fromArray(c.n, i * 3), up)
    if (tg.lengthSq() < 0.05) tg.set(1, 0, 0)
    tg.normalize()
    const [x, y, z] = at(i).map(cell)
    let best = -1, score = Infinity
    for (let a = -1; a <= 1; a++) for (let b = -1; b <= 1; b++) for (let e = -1; e <= 1; e++)
      for (const j of grid.get(`${x + a},${y + b},${z + e}`) ?? []) {
        v.fromArray(c.p, j * 3).sub(nv.fromArray(c.p, i * 3))
        const along = v.dot(tg), l = v.length()
        if (j === i || along <= 0.3 * l || l > R) continue
        const sc = l + 3 * Math.sqrt(Math.max(0, l * l - along * along))  // near, and straight along the way round
        if (sc < score) { score = sc; best = j }
      }
    if (best >= 0) { ab.push(i, best); rest.push(Math.sqrt(d2(i, best))) }
  }
  return { ab: Uint32Array.from(ab), rest: Float32Array.from(rest) }
}

/** Jharvis's "voice": a particle wave field (voice-recorder style), in Glacier colours.
 *  Follows the pointer; `pulse` changing sends a ring out (e.g. when the urgent task changes).
 *  While he speaks, dots rise out of the wave into a 3D orb (sometimes a human head whose jaw moves with his voice);
 *  `shape` makes them form what he wants to show. Drag a shape to turn it. When he's done the dots pour back in. */
function WaveField({ pulse = 0, energy, mood, speaking, shape, wave = true, className = '' }: {
  pulse?: number; energy?: { current: number }; mood?: { current: string }; wave?: boolean  // false = the shape alone (phone)
  speaking?: { current: boolean }; shape?: { current: DotShape | null }; className?: string
}) {
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
    points.visible = wave
    scene.add(points)
    const gx = new Float32Array(n), gz = new Float32Array(n)
    for (let i = 0; i < n; i++) { gx[i] = pos[i * 3]; gz[i] = pos[i * 3 + 2] }

    // ─── shapes: their own dots, each with a home dot in the middle of the wave it rises from and pours back into ───
    const STEP = W / (COLS - 1), CX = 0, CY = 1.9, CZ = 1.2, S = 1.4  // where shapes stand: clear above the wave crest
    if (!wave) { camera.position.set(CX, CY + 0.9, CZ + 4.6); camera.lookAt(CX, CY, CZ) }  // no wave: frame the shape alone
    const RING_N = 7, RING_DOTS = 26, SN = MAX + RING_N * RING_DOTS  // + the rings that rise from the wave into the shape
    const sPos = new Float32Array(SN * 3), sCol = new Float32Array(SN * 4), home = new Uint32Array(MAX), rate = new Float32Array(MAX)
    const middle: number[] = []
    for (let i = 0; i < n; i++) if (Math.abs(gx[i]) < 5 && gz[i] > -3) middle.push(i)
    for (let k = 0; k < MAX; k++) {
      home[k] = middle[Math.floor(Math.random() * middle.length)]
      rate[k] = 2.5 + Math.random() * 3  // per second: there in about a second, each at its own pace, like a flock
      sPos.set([gx[home[k]], 0, gz[home[k]]], k * 3)
    }
    const sGeo = new THREE.BufferGeometry()
    sGeo.setAttribute('position', new THREE.BufferAttribute(sPos, 3))
    sGeo.setAttribute('color', new THREE.BufferAttribute(sCol, 4))
    const sMat = new THREE.PointsMaterial({ size: 0.13, map: tex, vertexColors: true, transparent: true, depthWrite: false })
    const sPts = new THREE.Points(sGeo, sMat)
    sPts.frustumCulled = false
    scene.add(sPts)
    // night theme: the dots also read as glowing strokes (each joined to its nearest), with now and then a spark of
    // lightning off the silhouette, like a hologram
    const still = matchMedia('(prefers-reduced-motion: reduce)').matches
    const LN = MAX, BOLTS = 3, BSEG = 6  // one link per dot
    const lPos = new Float32Array((LN + BOLTS * BSEG) * 6), lCol = new Float32Array((LN + BOLTS * BSEG) * 8)
    const lGeo = new THREE.BufferGeometry()
    lGeo.setAttribute('position', new THREE.BufferAttribute(lPos, 3))
    lGeo.setAttribute('color', new THREE.BufferAttribute(lCol, 4))
    const lMat = new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending })
    const lines = new THREE.LineSegments(lGeo, lMat)
    lines.frustumCulled = false
    scene.add(lines)
    const linkCache = new WeakMap<Cloud, ReturnType<typeof linksOf>>(), bolts = Array.from({ length: BOLTS }, () => ({ until: 0, p: new Float32Array(BSEG * 6) }))
    let linked: Cloud | null = null
    // the side facing the camera is lit, the far side shows through faintly
    const view = new THREE.Vector3().subVectors(camera.position, new THREE.Vector3(CX, CY, CZ)).normalize()
    // models are lit from above, front left, so a nose, eyes and fingers cast shadows
    const key = view.clone().add(new THREE.Vector3(-0.45, 0.55, 0)).normalize()

    const unitStep = STEP / S  // the wave's spacing, in shape units
    const orbCloud = { ...sphereCloud(unitStep), size: 0.85 }
    let head: Cloud | null = null, bust: Cloud | null = null, hand: Cloud | null = null, handBall: Cloud | null = null
    const loader = new GLTFLoader()
    loader.load(headUrl, gl => {
      const h = modelCloud(gl.scene, 6000, new THREE.Euler(0, 0, 0), -0.45)  // head and neck, no shoulders
      const b = modelCloud(gl.scene, MAX, new THREE.Euler(0, 0, 0))  // the whole scan: head, neck and shoulders
      if (!h || !b) return
      h.size = 1.15; h.lift = -0.15; addJaw(h, 1)
      b.size = 1.2; b.lift = 0.05; addJaw(b, b.unit! / h.unit!)  // scaled to its shoulders, so its head is smaller in -1..1
      head = h; bust = b
    }, undefined, () => { /* no head: he talks as an orb */ })
    loader.load(handUrl, gl => {
      hand = modelCloud(gl.scene, 2400, new THREE.Euler(Math.PI + 0.5, 0, -Math.PI / 2))  // palm up and tipped toward you, fingers left, like an offering
      if (hand) handBall = joinClouds(hand, sphereCloud(0.04, 0.3), [0.25, 0.31, 0.17])  // the ball rests in the palm
    })

    /** The cloud for a shape Jharvis asked for; undefined = its model is still loading (try again next frame). */
    const cloudFor = (sp: DotShape): Cloud | null | undefined => {
      if (sp.paths?.length) return inflate(sp.paths, !!sp.fill, unitStep * 0.28)  // drawings need finer dots than the wave to read
      if (sp.name === 'orb' || sp.name === 'ball') return orbCloud
      if (sp.name === 'face') return head ?? undefined
      if (sp.name === 'bust') return bust ?? undefined
      if (sp.name === 'hand') return hand ?? undefined
      if (sp.name === 'hand_ball') return handBall ?? undefined
      const pre = sp.name ? PRESETS[sp.name] : undefined
      return pre ? inflate(pre.paths, pre.fill, unitStep * 0.28) : null
    }

    // turning: drag a shape with the mouse; let go and it keeps spinning, then drifts slowly
    let yaw = 0, pitch = 0, vyaw = 0, vpitch = 0, drag: { x: number; y: number } | null = null, lastDrag = -10, shapeOn = false
    const onDown = (e: PointerEvent) => {
      if (!shapeOn || e.button !== 0 || (e.target as Element).closest?.('button,a,input,textarea,select,label,section,[role="dialog"]')) return
      drag = { x: e.clientX, y: e.clientY }
      e.preventDefault()  // no text selection while turning
    }
    const onDrag = (e: PointerEvent) => {
      if (!drag) return
      vyaw = (e.clientX - drag.x) * 0.01; vpitch = (e.clientY - drag.y) * 0.008
      yaw += vyaw; pitch = THREE.MathUtils.clamp(pitch + vpitch, -0.9, 0.9)
      drag = { x: e.clientX, y: e.clientY }
      lastDrag = performance.now() / 1000
    }
    const onUp = () => { drag = null }
    addEventListener('pointerdown', onDown)
    addEventListener('pointermove', onDrag)
    addEventListener('pointerup', onUp)

    let talkForm: 'orb' | 'face' = 'orb', wasSpeaking = false, lastTalk = -10, formMix = 0, drawnId = '', drawn: Cloud | null = null, bottom = [CX, CY - S, CZ]
    // "look left / right / ahead" (a shape named look_*): the head shown, or the bust, turns there and holds a few seconds
    let shown: Cloud | null = null, lookYaw = 0, lookUntil = -10
    // becoming the head or bust: the dots first gather into one bright point (GATHER s), then grow out into the figure (GROW s)
    const GATHER = 0.4, GROW = 1, from = new Float32Array(MAX * 3), fromA = new Float32Array(MAX)
    let prevCloud: Cloud | null = null, gatherEnd = -10, burst = false

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
    let last = 0
    const frame = (t: number) => {
      const dt = Math.min(Math.max(t - last, 0), 0.1)  // ease by time, not frames: a slow page still forms in a second
      last = t
      mouse.x += (mouse.tx - mouse.x) * 0.05
      mouse.y += (mouse.ty - mouse.y) * 0.05
      const mx = mouse.x * W, since = t - ripple.current
      // voice: the owner's mic level or Jharvis speaking swells the waves (read from a ref, no re-render)
      smooth.current += ((energy?.current ?? 0) - smooth.current) * 0.15
      const e = smooth.current
      // what the dots hold: Jharvis's shape first, then the orb or head while he speaks, else the wave
      if (speaking?.current) lastTalk = t
      const talking = t - lastTalk < 1.5  // lingers a moment after his last word, so it doesn't vanish mid-breath
      if (talking && !wasSpeaking) talkForm = Math.random() < 0.3 ? 'face' : 'orb'
      wasSpeaking = talking
      const sp = shape?.current
      if (sp && sp.id !== drawnId) {
        const look = sp.name?.match(/^look_(left|right|ahead)$/)?.[1]
        const c = look ? (shown?.jaw ? shown : bust ?? undefined) : cloudFor(sp)
        if (c !== undefined) { drawnId = sp.id; drawn = c }
        if (look && c) { lookYaw = look === 'ahead' ? 0 : look === 'left' ? 0.75 : -0.75; lookUntil = t + 4; vyaw = vpitch = 0 }  // his own left, your right
      }
      const cloud = sp ? (sp.id === drawnId ? drawn : null) : talking ? (talkForm === 'face' && head ? head : orbCloud) : null
      shown = cloud
      if (cloud !== prevCloud && cloud?.jaw && !still) { gatherEnd = t + GATHER; burst = true; from.set(sPos.subarray(0, MAX * 3)); for (let k = 0; k < MAX; k++) fromA[k] = sCol[k * 4 + 3] }
      prevCloud = cloud
      const gathering = t < gatherEnd, pt = [CX, CY + (cloud?.lift ?? 0), CZ]
      const pull = THREE.MathUtils.smoothstep(1 - (gatherEnd - t) / GATHER, 0, 0.75)  // all there by 3/4 of the way, then a beat as a point
      const spread = 1 - (1 - THREE.MathUtils.clamp((t - gatherEnd) / GROW, 0, 1)) ** 3  // the figure's size, from the point out
      if (burst && !gathering) {  // the point bursts: every figure dot starts from it, already facing you (a point has no turn to jump from)
        for (let k = 0; k < (cloud?.count ?? 0); k++) sPos.set(pt, k * 3)
        burst = false
        if (!drag) { yaw = t < lookUntil ? lookYaw : Math.sin(t * 0.5) * 0.35; pitch = -0.2; vyaw = vpitch = 0 }
      }
      formMix += ((cloud ? 1 : 0) - formMix) * (1 - Math.exp(-dt * 2.4))
      // the wave calms and settles lower while a shape stands over it (so the shape reads), but keeps the voice in it
      const amp = (1 + e * 4.5) * (1 - formMix * 0.45), flat = 1 - formMix * 0.5, drop = formMix * 0.6
      const waveY = (x: number, z: number) => {
        let y = (Math.sin(x * 0.55 + t * 0.9) * 0.35 + Math.sin(x * 1.3 - t * 1.4 + z * 0.8) * 0.18 * amp + Math.cos(z * 1.1 + t * 0.6) * 0.12) * (0.75 + amp * 0.25) * 1.8
        y += Math.exp(-((x - mx) ** 2) / 6) * (0.6 + mouse.y * -0.6)
        const d = Math.hypot(x, z * 2)
        if (since < 3) y += Math.sin(d * 2 - since * 9) * Math.exp(-since * 1.4) * Math.exp(-Math.abs(d - since * 4.5) * 0.8) * 0.9
        return y * flat - drop
      }
      for (let i = 0; i < n; i++) { pos[i * 3 + 1] = waveY(gx[i], gz[i]) }
      geo.attributes.position.needsUpdate = true

      if (cloud || shapeOn) {
        const glow = gathering ? pull ** 3 : Math.max(0, 1 - (t - gatherEnd) / 0.2)  // peaks as the point bursts
        if (cloud) sMat.size = (cloud.dense ? 0.075 : 0.13) * (1 + glow * 2)
        const dark = document.documentElement.classList.contains('dark')
        sMat.blending = dark ? THREE.AdditiveBlending : THREE.NormalBlending  // a glow on the night theme
        tmp.set(palettes[target()][1])
        if (cloud?.dense && !dark) tmp.lerp(new THREE.Color(palettes[target()][2]), 0.45)  // deeper blue on the light theme, so a face reads
        // turning: the drag's spin fades out; after a few seconds untouched, shapes drift round and the head faces you
        if (!drag) {
          yaw += vyaw; pitch = THREE.MathUtils.clamp(pitch + vpitch, -0.9, 0.9)
          const f = Math.exp(-dt * 3)
          vyaw *= f; vpitch *= f
          if (t < lookUntil) {
            const k = 1 - Math.exp(-dt * 2.5)
            yaw += (lookYaw - yaw) * k; pitch += (-0.2 - pitch) * k
          } else if (t - lastDrag > 3) {
            const k = 1 - Math.exp(-dt * 1.2)
            if (cloud?.orb) { yaw += dt * 0.4; pitch += (0.15 - pitch) * k }  // a ball looks the same all round: spin it
            else {  // anything else sways so it never turns edge-on; the head lifts its chin (the camera looks down)
              yaw += (Math.sin(t * 0.5) * (cloud?.jaw ? 0.35 : 0.5) - yaw) * k
              pitch += ((cloud?.jaw ? -0.2 : 0.1) - pitch) * k
            }
          }
        }
        yaw = Math.atan2(Math.sin(yaw), Math.cos(yaw))
        const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), spn = Math.sin(pitch)
        const count = cloud ? cloud.count : 0, grow = cloud?.orb ? 1 + e * 0.3 : 1, Sc = S * (cloud?.size ?? 1), lift = cloud?.lift ?? 0
        let alive = false, by = Infinity
        for (let k = 0; k < MAX; k++) {
          const c = k * 3
          let tx: number, ty: number, tz: number, ta: number
          if (gathering && (k < count || sCol[k * 4 + 3] > 0.01)) {  // every dot on show, and the figure's from the wave, to the point
            sPos[c] = from[c] + (pt[0] - from[c]) * pull; sPos[c + 1] = from[c + 1] + (pt[1] - from[c + 1]) * pull; sPos[c + 2] = from[c + 2] + (pt[2] - from[c + 2]) * pull
            tx = sPos[c]; ty = sPos[c + 1]; tz = sPos[c + 2]
            ta = sCol[k * 4 + 3] = fromA[k] + (1 - fromA[k]) * pull ** 6  // dots from the wave light up only as they arrive (6500 faint dots still read as a band)
            if (ty < by) { by = ty; bottom = [tx, ty, tz] }
          } else if (k < count) {
            const wob = cloud!.orb ? 1 + Math.sin(t * 4 + k * 0.37) * 0.025 * (0.3 + e) : 1
            const px = cloud!.p[c] * grow * wob, py = cloud!.p[c + 1] * grow * wob - (cloud!.jaw ? cloud!.jaw[k] * e * 0.14 : 0), pz = cloud!.p[c + 2] * grow * wob
            const x1 = px * cy + pz * sy, z1 = -px * sy + pz * cy, y2 = py * cp - z1 * spn, z2 = py * spn + z1 * cp
            tx = CX + x1 * Sc * spread; ty = CY + lift + y2 * Sc * spread; tz = CZ + z2 * Sc * spread
            const nx = cloud!.n[c], ny = cloud!.n[c + 1], nz = cloud!.n[c + 2]
            const nx1 = nx * cy + nz * sy, nz1 = -nx * sy + nz * cy, ny2 = ny * cp - nz1 * spn, nz2 = ny * spn + nz1 * cp
            if (cloud!.dense) {  // models need deep shadows to show a nose, lips, fingers; the far side stays faint
              const l = nx1 * key.x + ny2 * key.y + nz2 * key.z, f = nx1 * view.x + ny2 * view.y + nz2 * view.z
              ta = f > 0 ? 0.05 + 0.95 * Math.max(0, l) ** 2.2 : 0.03
            } else {
              const f = nx1 * view.x + ny2 * view.y + nz2 * view.z
              ta = f > 0 ? 0.35 + 0.65 * f : 0.12
            }
            if (ty < by) { by = ty; bottom = [tx, ty, tz] }
          } else {
            const i = home[k]
            tx = gx[i]; ty = pos[i * 3 + 1]; tz = gz[i]; ta = 0
            if (sCol[k * 4 + 3] < 0.01) { sPos[c] = tx; sPos[c + 1] = ty; sPos[c + 2] = tz; sCol[k * 4 + 3] = 0; continue }  // home: ride the wave, unseen
          }
          const r = 1 - Math.exp(-dt * rate[k])
          sPos[c] += (tx - sPos[c]) * r; sPos[c + 1] += (ty - sPos[c + 1]) * r; sPos[c + 2] += (tz - sPos[c + 2]) * r
          sCol[k * 4] = tmp.r; sCol[k * 4 + 1] = tmp.g; sCol[k * 4 + 2] = tmp.b
          sCol[k * 4 + 3] += (ta - sCol[k * 4 + 3]) * (1 - Math.exp(-dt * 4))
          alive = true
        }
        // rings of dots rising from the wave into the shape's lowest point: one body, not a separate thing
        const base = waveY(bottom[0], bottom[2]), gap = bottom[1] - base
        for (let r = 0; r < RING_N; r++) {
          const ph = (t * 0.3 + r / RING_N) % 1, y = base + gap * ph, rad = (0.55 - 0.3 * ph) * (S / 1.4)
          const a = wave && gap > 0.2 ? formMix * Math.sin(Math.PI * ph) * 0.7 : 0  // no wave (phone): no rings (Jhon 31 Oct)
          for (let j = 0; j < RING_DOTS; j++) {
            const k = MAX + r * RING_DOTS + j, an = (j / RING_DOTS) * Math.PI * 2 + r * 0.4
            sPos.set([bottom[0] + Math.cos(an) * rad, y, bottom[2] + Math.sin(an) * rad], k * 3)
            sCol.set([tmp.r, tmp.g, tmp.b, a], k * 4)
          }
        }
        // glowing strokes along each dot's links, fading out where a link is stretched (dots still flying in or out)
        if (cloud) linked = cloud
        let ln = 0
        if (dark && linked) {
          if (!linkCache.has(linked)) linkCache.set(linked, linksOf(linked))
          const { ab, rest } = linkCache.get(linked)!, sc = S * (linked.size ?? 1)
          for (let l = 0; l < rest.length && l < LN; l++) {
            const a = ab[l * 2], b = ab[l * 2 + 1]
            const len = Math.hypot(sPos[a * 3] - sPos[b * 3], sPos[a * 3 + 1] - sPos[b * 3 + 1], sPos[a * 3 + 2] - sPos[b * 3 + 2])
            const al = Math.min(sCol[a * 4 + 3], sCol[b * 4 + 3]) * THREE.MathUtils.clamp(2.5 - 1.5 * len / (rest[l] * sc), 0, 1) * 0.8
            if (al < 0.01) continue
            lPos.set(sPos.subarray(a * 3, a * 3 + 3), ln * 6); lPos.set(sPos.subarray(b * 3, b * 3 + 3), ln * 6 + 3)
            lCol.set([tmp.r, tmp.g, tmp.b, al, tmp.r, tmp.g, tmp.b, al], ln * 8)
            ln++
          }
          // lightning: a short jagged spark out from a dot on the silhouette (edge-on to you), for a split second
          if (!still && cloud && formMix > 0.9 && Math.random() < dt * 0.8) {
            const bo = bolts.find(x => t > x.until)
            for (let tries = 0; bo && tries < 60; tries++) {
              const k = Math.floor(Math.random() * cloud.count), c = k * 3, nx = cloud.n[c], ny = cloud.n[c + 1], nz = cloud.n[c + 2]
              const n1 = new THREE.Vector3(nx * cy + nz * sy, 0, -nx * sy + nz * cy), nv = new THREE.Vector3(n1.x, ny * cp - n1.z * spn, ny * spn + n1.z * cp)
              const f = nv.dot(view)
              if (Math.abs(f) > 0.15 || sCol[k * 4 + 3] < 0.02) continue
              const out = nv.addScaledVector(view, -f).normalize(), side = new THREE.Vector3().crossVectors(out, view).normalize()
              const p = new THREE.Vector3().fromArray(sPos, c)
              for (let j = 0; j < BSEG; j++) {
                bo.p.set([p.x, p.y, p.z], j * 6)
                p.addScaledVector(out, 0.06 + Math.random() * 0.05).addScaledVector(side, (Math.random() - 0.5) * 0.12)
                bo.p.set([p.x, p.y, p.z], j * 6 + 3)
              }
              bo.until = t + 0.08 + Math.random() * 0.12
              break
            }
          }
          for (const bo of bolts) if (t < bo.until && Math.random() < 0.8) {  // flickers while it lasts
            lPos.set(bo.p, ln * 6)
            for (let j = 0; j < BSEG; j++, ln++) {
              const a = 1 - j / BSEG * 0.7
              lCol.set([0.75 + tmp.r * 0.25, 0.85 + tmp.g * 0.15, 1, a, 0.75 + tmp.r * 0.25, 0.85 + tmp.g * 0.15, 1, a * 0.8], ln * 8)
            }
          }
        }
        lGeo.setDrawRange(0, ln * 2)
        lGeo.attributes.position.needsUpdate = true
        lGeo.attributes.color.needsUpdate = true
        shapeOn = alive || formMix > 0.01
        sGeo.setDrawRange(0, shapeOn ? SN : 0)
        sGeo.attributes.position.needsUpdate = true
        sGeo.attributes.color.needsUpdate = true
      }
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
      points.rotation.y = sPts.rotation.y = lines.rotation.y = mouse.x * 0.25
      points.rotation.x = sPts.rotation.x = lines.rotation.x = mouse.y * 0.08
      renderer.render(scene, camera)
    }

    size()
    const ro = new ResizeObserver(size)
    ro.observe(canvas)
    let raf = 0, visible = true
    const io = new IntersectionObserver(([en]) => { visible = en.isIntersecting })
    io.observe(canvas)
    if (still) frame(1)
    else {
      const loop = (ms: number) => { if (visible && !document.hidden) frame(ms / 1000); raf = requestAnimationFrame(loop) }
      raf = requestAnimationFrame(loop)
    }
    return () => {
      cancelAnimationFrame(raf); ro.disconnect(); io.disconnect()
      removeEventListener('pointermove', onMove)
      removeEventListener('pointerdown', onDown); removeEventListener('pointermove', onDrag); removeEventListener('pointerup', onUp)
      geo.dispose(); mat.dispose(); sGeo.dispose(); sMat.dispose(); lGeo.dispose(); lMat.dispose(); tex.dispose(); renderer.dispose()
    }
  }, [])

  return <canvas ref={ref} aria-hidden="true" className={className} />
}

export default memo(WaveField)
