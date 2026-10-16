import { describe, expect, it } from 'vitest'
import { driverAt, driversText, idsOf, saveDriverChanges, withChange, withoutChange } from './driverChanges'

const sam = { id: 'd-sam', name: 'Sam', code: 'SAM' }
const alex = { id: 'd-alex', name: 'Alex', code: 'ALE' }

describe('driver changes within a session (M27)', () => {
  it('a lap is the driver’s whose change came before it ended', () => {
    const changes = [{ from: 10_000, driver: alex }, { from: 20_000, driver: null }]
    expect(driverAt(sam, [], 5_000)).toEqual(sam)
    expect(driverAt(sam, changes, 10_000)).toEqual(sam) // ending at the change: the driver before
    expect(driverAt(sam, changes, 10_001)).toEqual(alex)
    expect(driverAt(sam, changes, 20_001)).toBeNull() // a change to nobody
    expect(driverAt(null, [], 1)).toBeNull()
  })

  it('changes made and taken out by moment, kept in order', () => {
    const ids = idsOf([{ from: 20_000, driver: sam }, { from: 10_000, driver: alex }])
    expect(ids).toEqual([{ from: 10_000, driver: 'd-alex' }, { from: 20_000, driver: 'd-sam' }])
    expect(withChange(ids, 15_000, null).map((c) => c.from)).toEqual([10_000, 15_000, 20_000])
    expect(withChange(ids, 10_000, 'd-sam')[0]).toEqual({ from: 10_000, driver: 'd-sam' }) // replaced, not doubled
    expect(withoutChange(ids, 10_000)).toEqual([{ from: 20_000, driver: 'd-sam' }])
  })

  it('who drove, in words', () => {
    expect(driversText(sam, [])).toBe('Sam')
    expect(driversText(sam, [{ from: 1, driver: alex }, { from: 2, driver: alex }, { from: 3, driver: sam }])).toBe('Sam, then Alex, then Sam')
    expect(driversText(null, [{ from: 1, driver: sam }])).toBe('Nobody yet, then Sam')
  })

  it('sends the whole list by the right path, and says why a refusal was', async () => {
    const sent: string[] = []
    const ok = (async (path: string, init: RequestInit) => { sent.push(`${path} ${init.body}`); return new Response('{}') }) as unknown as typeof fetch
    await saveDriverChanges('crew', 'outback', 's1', [{ from: 1, driver: 'd-sam' }], ok)
    await saveDriverChanges('admin', 'outback', 's1', [], ok)
    expect(sent).toEqual([
      '/api/cars/outback/sessions/s1/driver-changes {"changes":[{"from":1,"driver":"d-sam"}]}',
      '/api/admin/sessions/s1/driver-changes {"changes":[]}',
    ])
    const refused = (async () => new Response('{"message":"No such driver."}', { status: 400 })) as unknown as typeof fetch
    await expect(saveDriverChanges('crew', 'outback', 's1', [], refused)).rejects.toThrow('No such driver.')
  })
})
