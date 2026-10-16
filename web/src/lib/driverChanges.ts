/**
 * Driver changes within a session (M27): the session's driver from its start,
 * each change's from its moment on (the tablet's `wall`, milliseconds). A lap
 * is the driver's whose change came before it ended, as a race's stint. Pure,
 * but for [saveDriverChanges].
 */
import type { Driver } from './events'

/** A change as the session's page has it. */
export interface DriverChangeDetail {
  from: number
  driver?: Driver | null
}

/** A change as it's sent: a driver's id, or null for "not set". */
export interface DriverChangeId {
  from: number
  driver: string | null
}

/** Who drove a lap ending at [end]: the last change before it, else the session's driver. */
export function driverAt(start: Driver | null | undefined, changes: readonly DriverChangeDetail[], end: number): Driver | null {
  let found: DriverChangeDetail | undefined
  for (const c of changes) if (c.from < end) found = c
  return found ? (found.driver ?? null) : (start ?? null)
}

/** The changes as ids, in order. */
export function idsOf(changes: readonly DriverChangeDetail[]): DriverChangeId[] {
  return changes.map((c) => ({ from: c.from, driver: c.driver?.id ?? null })).sort((a, b) => a.from - b.from)
}

/** A change to [driver] from [from], replacing one at that moment. */
export function withChange(changes: readonly DriverChangeId[], from: number, driver: string | null): DriverChangeId[] {
  return [...changes.filter((c) => c.from !== from), { from, driver }].sort((a, b) => a.from - b.from)
}

export function withoutChange(changes: readonly DriverChangeId[], from: number): DriverChangeId[] {
  return changes.filter((c) => c.from !== from)
}

/** "Sam, then Alex": who drove, in turn, each once in a row. */
export function driversText(start: Driver | null | undefined, changes: readonly DriverChangeDetail[]): string {
  const names = [start?.name ?? 'Nobody yet', ...changes.map((c) => c.driver?.name ?? 'nobody')]
  return names.filter((n, i) => i === 0 || n !== names[i - 1]).join(', then ')
}

/** Sends the whole list, through the admin's path or the crew's; the server's words if it's refused. */
export async function saveDriverChanges(who: 'admin' | 'crew', slug: string, id: string, changes: DriverChangeId[], fetcher: typeof fetch = fetch): Promise<void> {
  const path = who === 'admin' ? `/api/admin/sessions/${id}/driver-changes` : `/api/cars/${slug}/sessions/${id}/driver-changes`
  const r = await fetcher(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ changes }) })
  if (!r.ok) {
    const body = (await r.json().catch(() => ({}))) as { message?: string }
    throw new Error(body.message || `The server answered ${r.status}.`)
  }
}
