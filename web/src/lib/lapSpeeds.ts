/**
 * Each lap's top, lowest and average speed (Sam, 2026-10-16), from samples the
 * page already holds. Pure. Speeds are km/h, as the tablet sends both speed
 * signals (§4.1); the page converts for display.
 *
 * **Top and lowest** are the speed samples within the lap. A thinned series
 * keeps each 5 s slice's minimum and maximum (M19.4), so they survive it.
 * **The average** is the distance between the lap's positions over the time
 * they span, not a mean of samples: a thinned series keeps only extremes,
 * which would skew a mean. A lap the samples don't cover end to end has none
 * of the three, rather than figures from part of it.
 */
import type { LapRow } from './sessionPage'

export interface LapSpeed {
  max: number
  min: number
  /** Null where the positions don't cover the lap (no GPS for part of it). */
  avg: number | null
}

/** Samples on the wall clock (epoch ms), in time order. */
export interface Samples {
  t: readonly number[]
  v: readonly (number | null)[]
}

export interface Fixes {
  t: readonly number[]
  lat: readonly number[]
  lon: readonly number[]
}

/** How near a lap's start and end its first and last sample must be: a thinned series' slice, and a little. */
export const COVER_MS = 6_000
/** Metres a degree of latitude, as the lap rule's frame has it. */
const METRES_PER_DEGREE = 111_195

/** The first index in [t] (ascending) at or after [x]. */
function from(t: readonly number[], x: number): number {
  let lo = 0
  let hi = t.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (t[mid]! < x) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** The indexes of [t] within [start, end], or null unless they reach within [COVER_MS] of both. */
function covering(t: readonly number[], start: number, end: number): [number, number] | null {
  const a = from(t, start)
  const b = from(t, end + 1) - 1
  if (a > b || t[a]! - start > COVER_MS || end - t[b]! > COVER_MS) return null
  return [a, b]
}

/** Metres between two fixes, in a flat frame (a lap is small enough). */
function metres(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dy = (lat2 - lat1) * METRES_PER_DEGREE
  const dx = (lon2 - lon1) * METRES_PER_DEGREE * Math.cos(((lat1 + lat2) / 2) * (Math.PI / 180))
  return Math.hypot(dx, dy)
}

/** [lap]'s speeds, or null where the speed samples don't cover it. */
export function lapSpeed(lap: Pick<LapRow, 'start' | 'end'>, speed: Samples, fixes: Fixes): LapSpeed | null {
  const span = covering(speed.t, lap.start, lap.end)
  if (!span) return null
  let max = -Infinity
  let min = Infinity
  for (let i = span[0]; i <= span[1]; i++) {
    const v = speed.v[i]
    if (typeof v !== 'number' || !Number.isFinite(v)) continue
    max = Math.max(max, v)
    min = Math.min(min, v)
  }
  if (max === -Infinity) return null
  return { max, min, avg: averageSpeed(lap, fixes) }
}

/** km/h: the distance between [lap]'s fixes over the time they span; null unless they cover it. */
export function averageSpeed(lap: Pick<LapRow, 'start' | 'end'>, fixes: Fixes): number | null {
  const span = covering(fixes.t, lap.start, lap.end)
  if (!span) return null
  const [a, b] = span
  const seconds = (fixes.t[b]! - fixes.t[a]!) / 1000
  if (seconds <= 0) return null
  let m = 0
  for (let i = a; i < b; i++) {
    // A gap longer than the cover allows is GPS lost mid-lap: no average, not a straight line across it.
    if (fixes.t[i + 1]! - fixes.t[i]! > COVER_MS) return null
    m += metres(fixes.lat[i]!, fixes.lon[i]!, fixes.lat[i + 1]!, fixes.lon[i + 1]!)
  }
  return (m / seconds) * 3.6
}

export interface Source {
  speed: Samples | null
  fixes: Fixes
}

/**
 * Each of [laps]' speeds by when it ended, from the first of [sources] that
 * covers it; null where none does. [kept], if given, remembers each found and
 * is asked first: the car page's live history forgets after five minutes, and
 * a lap's speeds don't change once it has ended.
 */
export function lapSpeeds(laps: readonly LapRow[], sources: readonly Source[], kept?: Map<number, LapSpeed>): Map<number, LapSpeed | null> {
  return new Map(laps.map((l) => {
    let found: LapSpeed | null = kept?.get(l.end) ?? null
    for (const s of sources) {
      if (found) break
      found = s.speed ? lapSpeed(l, s.speed, s.fixes) : null
    }
    if (found) kept?.set(l.end, found)
    return [l.end, found]
  }))
}

/** A series' speed samples and positions on the wall clock: GPS speed, else the car's (as the map's colours). */
export function seriesSamples(series: {
  t0: number
  numbers: Record<string, { t: number[]; v: (number | null)[] }>
  positions: { t: number[]; lat: number[]; lon: number[] }
}): Source {
  const name = ['gps.speed', 'vehicle.speed'].find((n) => (series.numbers[n]?.t.length ?? 0) > 0)
  const s = name ? series.numbers[name]! : null
  return {
    speed: s ? { t: s.t.map((t) => series.t0 + t), v: s.v } : null,
    fixes: { t: series.positions.t.map((t) => series.t0 + t), lat: series.positions.lat, lon: series.positions.lon },
  }
}

/** The live history's speed samples and positions on the wall clock (the car page): GPS speed, else the car's. */
export function historySamples(history: readonly { rec: Record<string, unknown> }[]): Source {
  const gps: Samples = { t: [], v: [] }
  const car: Samples = { t: [], v: [] }
  const fixes = { t: [] as number[], lat: [] as number[], lon: [] as number[] }
  for (const { rec } of history) {
    const wall = rec.wall
    if (typeof wall !== 'number') continue
    if (rec.signal === 'gps.position' && typeof rec.lat === 'number' && typeof rec.lon === 'number') {
      fixes.t.push(wall)
      fixes.lat.push(rec.lat)
      fixes.lon.push(rec.lon)
    } else if ((rec.signal === 'gps.speed' || rec.signal === 'vehicle.speed') && typeof rec.value === 'number') {
      const into = rec.signal === 'gps.speed' ? gps : car
      ;(into.t as number[]).push(wall)
      ;(into.v as (number | null)[]).push(rec.value)
    }
  }
  return { speed: gps.t.length > 0 ? gps : car.t.length > 0 ? car : null, fixes }
}
