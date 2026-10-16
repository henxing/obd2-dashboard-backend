<script lang="ts">
  import { onMount } from 'svelte'
  import { idsOf, saveDriverChanges, withChange, type DriverChangeDetail } from '../lib/driverChanges'

  // Who's driving, set by the crew from the car page (M17.5): the live session's driver. Once it has laps, a
  // driver change from now (M27): the car isn't switched off for one, so the session goes on.
  let { slug, session, current, hasLaps = false, nowWall = () => null }: {
    slug: string
    session: string
    current?: string
    /** Whether the session has laps yet: then a choice is a change from now, not who drove from its start. */
    hasLaps?: boolean
    /** The tablet's clock now: its newest record's `wall`. */
    nowWall?: () => number | null
  } = $props()

  let drivers: { id: string; name: string; code: string }[] = $state.raw([])
  let busy = $state(false)
  let message: string | null = $state(null)

  onMount(async () => {
    drivers = await fetch('/api/drivers').then((r) => (r.ok ? r.json() : [])).catch(() => [])
  })

  async function set(id: string) {
    busy = true
    message = null
    try {
      const now = nowWall()
      if (hasLaps && now !== null) {
        const detail = (await fetch(`/api/sessions/${session}`).then((r) => (r.ok ? r.json() : null))) as { driverChanges?: DriverChangeDetail[] } | null
        if (!detail) { message = 'Not saved: the session wasn’t found.'; return }
        await saveDriverChanges('crew', slug, session, withChange(idsOf(detail.driverChanges ?? []), now, id || null))
        return
      }
      const r = await fetch(`/api/cars/${slug}/sessions/${session}/driver`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ driver: id || null }),
      })
      if (!r.ok) message = ((await r.json().catch(() => null)) as { message?: string } | null)?.message ?? `Not saved (${r.status}).`
    } catch (e) {
      // A refusal says why (M27's changes); a request that never arrived, no connection.
      message = e instanceof TypeError || !(e instanceof Error) ? 'Not saved: no connection.' : e.message
    } finally {
      busy = false
    }
  }
</script>

<p class="picker">
  <label>Who's driving
    <select disabled={busy || drivers.length === 0} value={drivers.find((d) => d.code === current)?.id ?? ''} onchange={(e) => set(e.currentTarget.value)}>
      <option value="">Not set</option>
      {#each drivers as d (d.id)}<option value={d.id}>{d.name} ({d.code})</option>{/each}
    </select>
  </label>
  {#if hasLaps}<span class="muted small">A change is from now on.</span>{/if}
  {#if message}<span class="error">{message}</span>{/if}
</p>

<style>
  .picker { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 0; }
  .error { color: var(--critical); }
</style>
