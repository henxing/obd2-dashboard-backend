import { isState, type State } from './state'

export interface CarSummary {
  slug: string
  name: string
  state: State
  /** Said only to someone signed in (M25). */
  hidden?: boolean
}

/** The landing page's list. Unknown states read as offline rather than breaking the page. */
export async function fetchCars(fetcher: typeof fetch = fetch): Promise<CarSummary[]> {
  const response = await fetcher('/api/cars')
  if (!response.ok) throw new Error(`GET /api/cars: ${response.status}`)
  const cars = (await response.json()) as Array<{ slug: string; name: string; state: string; hidden?: boolean }>
  return cars.map((c) => ({ slug: c.slug, name: c.name, state: isState(c.state) ? c.state : 'offline', hidden: c.hidden === true }))
}
