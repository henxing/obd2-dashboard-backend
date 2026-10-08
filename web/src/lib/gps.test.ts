import { describe, expect, it } from 'vitest'
import { clockText, gpsGaps, gpsLostLive, lengthText } from './gps'
import type { Point } from './live'

const fix = (t: number): Point => ({ t, rec: { type: 'sample', signal: 'gps.position', lat: 42.23, lon: -72.24 } as Point['rec'] })
const speed = (t: number, v: number): Point => ({ t, rec: { type: 'sample', signal: 'vehicle.speed', value: v } as Point['rec'] })
const MIN = 60_000

describe('GPS lost, live (M24)', () => {
  it('is lost when the car is moving and no position has come for over 30 s', () => {
    const history = [fix(0), speed(1_000, 120), speed(40_000, 140)]
    expect(gpsLostLive(history, 41_000)).toEqual({ seconds: 41, atLeast: false })
  })

  it('is fine while positions keep coming, or for the first 30 s', () => {
    expect(gpsLostLive([fix(0), speed(20_000, 140), fix(25_000)], 30_000)).toBeNull()
    expect(gpsLostLive([fix(0), speed(29_000, 140)], 30_000)).toBeNull()
  })

  it('is fine for a parked car, or when the speed reading itself is stale', () => {
    expect(gpsLostLive([speed(0, 0), speed(120_000, 3)], 121_000)).toBeNull()
    expect(gpsLostLive([fix(0), speed(1_000, 140)], 60_000)).toBeNull() // speed 59 s old
  })

  it('says "at least" when there’s no position in the whole window', () => {
    const history = [speed(0, 100), speed(5 * MIN - 1_000, 150)]
    expect(gpsLostLive(history, 5 * MIN)).toEqual({ seconds: 300, atLeast: true })
  })
})

describe('GPS gaps in a session (M24)', () => {
  // The GTI, 2 October: positions to 0:48, none until 53:00, driving 6:12 to 51:24.
  const speeds = { t: [] as number[], v: [] as number[] }
  for (let t = 0; t <= 70 * MIN; t += 5_000) { speeds.t.push(t); speeds.v.push(t >= 6.2 * MIN && t <= 51.4 * MIN ? 150 : 0) }
  const fixesAt = (...ts: number[]) => ({ t: ts, lat: ts.map(() => 42.23), lon: ts.map(() => -72.24) })

  it('finds the 2 October gap', () => {
    const positions = fixesAt(0, 24_000, 48_000, 53 * MIN, 60 * MIN, 69.5 * MIN)
    expect(gpsGaps({ positions, numbers: { 'vehicle.speed': speeds } })).toEqual([{ from: 48_000, to: 53 * MIN }])
  })

  it('leaves a gap with no movement in it, and a session with fixes every second (as thinned)', () => {
    const parked = { t: speeds.t, v: speeds.v.map(() => 0) }
    expect(gpsGaps({ positions: fixesAt(0, 30 * MIN), numbers: { 'vehicle.speed': parked } })).toEqual([])
    const everySecond = fixesAt(...Array.from({ length: 70 * 60 + 1 }, (_, i) => i * 1000))
    expect(gpsGaps({ positions: everySecond, numbers: { 'vehicle.speed': speeds } })).toEqual([])
  })

  it('finds gaps at the start and the end, and a session with no positions at all', () => {
    const moving = { t: [0, 10_000, 20 * MIN], v: [100, 100, 100] }
    expect(gpsGaps({ positions: fixesAt(5 * MIN), numbers: { 'vehicle.speed': moving } })).toEqual([{ from: 0, to: 5 * MIN }, { from: 5 * MIN, to: 20 * MIN }])
    expect(gpsGaps({ positions: fixesAt(), numbers: { 'vehicle.speed': moving } })).toEqual([{ from: 0, to: 20 * MIN }])
    expect(gpsGaps({ positions: fixesAt(), numbers: {} })).toEqual([])
  })

  it('says lengths and clock times plainly', () => {
    expect(lengthText(52.2 * MIN)).toBe('52 min')
    expect(lengthText(80_000)).toBe('1 min 20 s')
    expect(lengthText(45_000)).toBe('45 s')
    expect(clockText(48_000)).toBe('0:48')
    expect(clockText(53 * MIN)).toBe('53:00')
    expect(clockText(62 * MIN + 5_000)).toBe('1:02:05')
  })
})
