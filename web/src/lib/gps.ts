/**
 * "GPS lost" (M24): the car is moving, by its own speed, which needs no GPS,
 * but the tablet has sent no position for a while. Laps are timed from the
 * tablet's GPS (decision 31), so then there are no laps and no route.
 */
import type { Point } from './live'
import type { Series } from './sessionPage'

/** Moving: the car's own speed at least this, km/h. */
export const MOVING_KMH = 15
/** Lost: no position for this long while moving. */
export const LOST_MS = 30_000
/** The speed reading must be this fresh to say the car is moving now. */
const SPEED_FRESH_MS = 10_000

const POSITION = 'gps.position'
const SPEED = 'vehicle.speed'

/**
 * Whether GPS is lost now, from the live page's history (five minutes, the
 * server's time): how long since the last position, and `atLeast` when there's
 * none in the whole window, so it's been longer. Null while all is well.
 */
export function gpsLostLive(history: readonly Point[], serverNow: number): { seconds: number; atLeast: boolean } | null {
  let lastFix: number | null = null
  let speed: { t: number; v: number } | null = null
  for (const p of history) {
    if (p.rec.signal === POSITION && typeof p.rec.lat === 'number' && typeof p.rec.lon === 'number') lastFix = p.t
    else if (p.rec.signal === SPEED && typeof p.rec.value === 'number') speed = { t: p.t, v: p.rec.value }
  }
  if (!speed || speed.v < MOVING_KMH || serverNow - speed.t > SPEED_FRESH_MS) return null
  const since = lastFix ?? history[0]?.t ?? serverNow
  const quiet = serverNow - since
  if (quiet <= LOST_MS) return null
  return { seconds: Math.round(quiet / 1000), atLeast: lastFix === null }
}

/**
 * Every stretch of a session, ms from its start, of [LOST_MS] or more with no
 * position while the car was moving: between two positions, or before the
 * first or after the last.
 */
export function gpsGaps(series: Pick<Series, 'positions' | 'numbers'>): { from: number; to: number }[] {
  const speed = series.numbers[SPEED]
  if (!speed) return []
  const moving = speed.t.filter((_, i) => (speed.v[i] ?? 0) >= MOVING_KMH)
  if (moving.length === 0) return []
  const fixes = series.positions.t
  const end = Math.max(speed.t[speed.t.length - 1] ?? 0, fixes[fixes.length - 1] ?? 0)
  // Each gap between fixes, and the stretches before the first and after the last.
  const edges = [0, ...fixes, end]
  const gaps: { from: number; to: number }[] = []
  let m = 0
  for (let i = 0; i + 1 < edges.length; i++) {
    const from = edges[i]!
    const to = edges[i + 1]!
    while (m < moving.length && moving[m]! <= from) m++
    const last = i + 2 === edges.length // after the last fix: up to and including the session's end
    if (to - from >= LOST_MS && m < moving.length && (moving[m]! < to || (last && moving[m]! <= to))) gaps.push({ from, to })
  }
  return gaps
}

/** "52 min", "1 min 20 s", "45 s". */
export function lengthText(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  const rest = s % 60
  return m >= 10 || rest === 0 ? `${m} min` : `${m} min ${rest} s`
}

/** "0:48", "53:00", "1:02:05": a time from the session's start. */
export function clockText(ms: number): string {
  const s = Math.round(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}
