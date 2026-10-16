import { describe, expect, it } from 'vitest'
import { averageSpeed, historySamples, lapSpeed, lapSpeeds, seriesSamples, type LapSpeed } from './lapSpeeds'
import type { LapRow } from './sessionPage'

const lap = (start: number, end: number): LapRow => ({ lap: 1, time: (end - start) / 1000, pitIn: false, pitOut: false, start, end, best: false })

/** A fix a second due north at [mps] metres a second, from [t0] for [n] seconds. */
function north(t0: number, n: number, mps: number) {
  const t: number[] = []
  const lat: number[] = []
  const lon: number[] = []
  for (let i = 0; i <= n; i++) {
    t.push(t0 + i * 1000)
    lat.push(43 + (i * mps) / 111_195)
    lon.push(-71.5)
  }
  return { t, lat, lon }
}

describe('lapSpeed', () => {
  const speed = { t: [1_000, 3_000, 5_000, 7_000, 9_000, 11_000], v: [50, 120, 80, null, 30, 200] }

  it('is the top and lowest sample within the lap, a gap passed over', () => {
    const s = lapSpeed(lap(0, 10_000), speed, north(0, 10, 25))!
    expect(s.max).toBe(120)
    expect(s.min).toBe(30)
  })

  it('is null unless the samples reach near both ends', () => {
    expect(lapSpeed(lap(0, 30_000), speed, north(0, 30, 25))).toBeNull() // the last sample 19 s before the end
    expect(lapSpeed(lap(-10_000, 10_000), speed, north(-10_000, 20, 25))).toBeNull() // the first 11 s after the start
  })

  it('is null with no number in the lap', () => {
    expect(lapSpeed(lap(6_000, 8_000), { t: [6_500, 7_500], v: [null, null] }, north(6_000, 2, 25))).toBeNull()
  })
})

describe('averageSpeed', () => {
  it('is the distance over the time the fixes span, in km/h', () => {
    expect(averageSpeed(lap(0, 60_000), north(0, 60, 25))).toBeCloseTo(90, 1) // 25 m/s
  })

  it('is null where GPS was lost mid-lap', () => {
    const f = north(0, 60, 25)
    const keep = f.t.map((t) => t < 20_000 || t > 40_000)
    const gap = { t: f.t.filter((_, i) => keep[i]), lat: f.lat.filter((_, i) => keep[i]), lon: f.lon.filter((_, i) => keep[i]) }
    expect(averageSpeed(lap(0, 60_000), gap)).toBeNull()
  })

  it('is null with no fixes', () => {
    expect(averageSpeed(lap(0, 60_000), { t: [], lat: [], lon: [] })).toBeNull()
  })
})

describe('lapSpeeds', () => {
  const fixes = north(0, 120, 20)
  const speed = { t: fixes.t, v: fixes.t.map((t) => (t < 60_000 ? 70 : 140)) }
  const laps = [lap(0, 60_000), lap(60_000, 120_000), lap(120_000, 180_000)]

  it('gives each lap by when it ended, null where nothing covers it', () => {
    const m = lapSpeeds(laps, [{ speed, fixes }])
    expect(m.get(60_000)?.max).toBe(140) // the sample at the line belongs to both
    expect(m.get(60_000)?.min).toBe(70)
    expect(m.get(120_000)?.min).toBe(140)
    expect(m.get(180_000)).toBeNull()
  })

  it('asks the next source where the first does not cover a lap, and keeps what it found', () => {
    const kept = new Map<number, LapSpeed>()
    const later = { speed: { t: [120_000, 150_000, 180_000], v: [100, 110, 90] }, fixes: north(120_000, 60, 30) }
    const m = lapSpeeds(laps, [{ speed, fixes }, later], kept)
    expect(m.get(180_000)?.max).toBe(110)
    expect(m.get(180_000)?.avg).toBeCloseTo(108, 1)
    // The history moved on: what was found stands.
    expect(lapSpeeds(laps, [{ speed: null, fixes: { t: [], lat: [], lon: [] } }], kept).get(180_000)?.max).toBe(110)
  })
})

describe('the samples', () => {
  it('takes a series onto the wall clock, GPS speed first', () => {
    const s = seriesSamples({
      t0: 1_000_000,
      numbers: { 'vehicle.speed': { t: [0], v: [10] }, 'gps.speed': { t: [5], v: [12] } },
      positions: { t: [5], lat: [43], lon: [-71] },
    })
    expect(s.speed).toEqual({ t: [1_000_005], v: [12] })
    expect(s.fixes.t).toEqual([1_000_005])
  })

  it("uses the car's speed where a series has no GPS speed", () => {
    const s = seriesSamples({ t0: 0, numbers: { 'vehicle.speed': { t: [1], v: [10] } }, positions: { t: [], lat: [], lon: [] } })
    expect(s.speed?.v).toEqual([10])
  })

  it('reads the live history by its wall clock', () => {
    const s = historySamples([
      { rec: { type: 'sample', signal: 'vehicle.speed', value: 60, wall: 1 } },
      { rec: { type: 'sample', signal: 'gps.position', lat: 43, lon: -71, wall: 2 } },
      { rec: { type: 'sample', signal: 'gps.speed', value: 61, wall: 3 } },
      { rec: { type: 'sample', signal: 'gps.speed', value: 62 } },
    ])
    expect(s.speed).toEqual({ t: [3], v: [61] })
    expect(s.fixes).toEqual({ t: [2], lat: [43], lon: [-71] })
    expect(historySamples([{ rec: { signal: 'vehicle.speed', value: 60, wall: 1 } }]).speed?.v).toEqual([60])
  })
})
