# M26 — A race in sessions: practice Friday, the race Saturday and Sunday

A race can be **run in sessions**: one race, stopped overnight and restarted
the next day. **Sam, 2026-10-16:** "practice friday, race saturday/sunday",
"it's one race with 2 race sessions", and:

1. the result: **most laps across both** ("although it'll likely just be one
   car running");
2. Sunday: **a rolling start**, but a **fresh session and driver**;
3. overnight: the car is **off track**.

> **Planned 2026-10-16**, against the code as it stands (M25 deployed as
> `00043`). Each step is validated again before it's built.

---

## What exists, and what it means for this (checked 2026-10-16)

- **A part is already a window of real time** (`events/.../Event.kt`,
  `Part(start, end)`, `Instant`s), so a part on any day works today. The
  event's `date` is one day, a label (`EventRules`: parsed only to check it).
- **An event has at most one race** (`EventRules.eventProblems`: "an event
  has at most one race"; `Event.race` is the first `RACE` part), and a part
  is **at most 30 hours** (`MAX_PART`).
- **The race is one timeline per car** (`timing/.../Race.kt`, `Race.car`):
  laps numbered through, stops, stints, the green and chequered flags'
  laps, classified by **most laps, then least time** (`Race.classify`:
  `seconds`, first lap's start to last lap's end). Between runs of the app,
  **a gap becomes one lap** (`source = "restart"`, `Race.kt:105`), timed on
  the tablet's clock, so a mid-race restart loses nothing.
- **Overnight, as one race part today**, that gives a 16-hour "lap" (and
  one more lap each), the night inside `seconds`; Saturday's flag and
  Sunday's green have nowhere to go (one `green` and `flag` per part); and
  Saturday 9:00 to Sunday 17:00 is over 30 hours, refused.
- **Who uses the race part:**
  - `EventResults.kt` (`RaceResults`: one `part`, `green`, `flag`,
    `edited`, `tabletOffset`; built from `event.race`);
  - `RaceRoutes.kt` (flags and stints, the admin's and a car's crew, on
    `event.race`);
  - `CarTimings.standing` (the live race lap count for the tablet,
    `RaceNowInput`: only the sessions of the part the car is in now, so
    Sunday would start again at lap 1);
  - `EventRules` (one race; flags and stints on the race only);
  - `FirestoreEventStore` (each part's `green`, `flag`, `stints`: already
    per part);
  - the site: `RaceSection.svelte` (one race: its flags, stints, laps),
    `EventPage.svelte`, `EventEditor.svelte` ("Add the race" only while
    there is none), `lib/race.ts`, `lib/eventResults.ts`, and the driver
    page's stints (`EventResults.kt:338`).

## Decided here

- **A race session is a race part.** An event can have **several race
  parts**; together, in time order, they are **the race**. Each keeps its
  own window, its own green and chequered flag, and its own stints, all
  of which the store already keeps per part. Nothing new in Firestore.
- **The race as one timeline across its sessions:**
  - laps numbered through all of them;
  - **no lap across the break** between two sessions (a restart *within*
    one is still bridged, as now);
  - **time** is the sum of each session's (first lap's start to last
    lap's end, per session), so the night never counts;
  - **classified as now:** most laps, then least time (Sam: "most laps
    across both");
  - **a stint never crosses a session**: each session's stints are its
    own (default: split at every stop, and at the session's start), numbered
    through the race. Sunday's new driver is Sunday's first stint.
  - **Sunday's rolling start** is its green flag: its laps before the
    green count, as every lap does today ("Every lap counts, before the
    green flag and after the flag too").
- **The tablet's race lap count carries on** on Sunday: the live race
  input holds every race session up to now.
- **The event's dates:** `date` stays its first day; an optional
  **`endDate`** (the last day, on or after `date`), shown "4–6 Oct".
  The editor's "sessions not in a part" covers the whole span.
- **A new race part** after the first is "Race — session 2" (renamable),
  starting at 9:00 the day after the last part ends, if that's later.
- **A part stays at most 30 hours**: a session is a day's racing.

## The steps

### M26.1 — The race over several sessions (pure, tested) ✅

`Race.car` takes the race's sessions in order: per session, its session ids,
green, flag and edited stints. Laps numbered through; the restart lap only
within a session; stops per session; stints per session, numbered through;
`seconds` summed; `greenLap` / `flagLap` per session.
Tests: one session as today (every existing `RaceTest` unchanged); two
sessions, one car (no lap across the night, time without the night, laps
numbered on, Sunday's first stint its own); two cars classified by laps
across both, then time; a restart within a session still bridged; edited
stints in one session only.

**Validated and built, 2026-10-16:** `Race.car` is pure, and only the
results and the live standing call it. Built as `Race.sessions`: each
session timed by `Race.car` as it stands, then joined (laps, stops and
stints renumbered, `seconds` summed), with `CarRace.sessions`
(`RaceSessionSpan`: each session's first lap, laps, time and flag laps).
The race's own `greenLap` is its first session's and `flagLap` its last's.
One session is exactly `Race.car` (a test says so); all 8 earlier tests
unchanged, 5 new.

### M26.2 — Events with several race parts ✅

`EventRules`: several race parts allowed, flags and stints on any race
part; `Event.races` (time order) in place of `Event.race` where it's the
whole race; `endDate` (optional, on or after `date`). The results
(`RaceResults` with each session's part, flags and edited stints), the race
routes (flags and stints **per race part**: `…/race/{part}/flags`, the old
paths kept for a lone race part), the live race input (all race sessions so
far), the driver page's stints. `HiddenTest`'s route list gains the new
paths (none public).
Tests: rules; results for a two-session race (Firestore round trip of two
race parts); the routes, the admin's and a crew's; the live lap count on
Sunday continuing from Saturday's.

**Validated and built, 2026-10-16.** Different from the sketch:
- **No new paths:** a flag or stint edit names its session in its body
  (`part`), as the store already keeps both per part; without one, a lone
  race part is meant, and a race in sessions answers `400` ("say which").
  `HiddenTest`'s route list is unchanged. The log names the session only
  for a race in sessions, so its lines read as before otherwise.
- `Event.race` stays (the race's first session), beside `Event.races`.
- `RaceResults` is `sessions` (each one's part, flags and edited stints),
  `cars` and `tabletOffset`; it no longer has one `part`, `green`, `flag`
  and `edited`.
- `endDate` in Firestore only when set; `admin.sh import-event` reads it,
  and `admin.sh events` shows "date to endDate".
- **Found on the dev server:** before Sunday's first crossing, the live
  standing described Saturday's stint going on ("587 s since the stop").
  Now the tablet is told nothing of the race until the session it's in has
  a lap, as at a race's very start (tested).

### M26.3 — The site ✅

The editor: "Add a race session" (always), the end date; the event's date
range in lists and on its page. The race section: the combined result,
then per session its flags (and editing them) and stints; laps in one
table, a row marking each session's start.
Tests: `race.ts` and `eventResults.ts` for two sessions; `newPart`'s next
day.

**Built, 2026-10-16:** "The race" heads a race in sessions, with each
session's flags; the flags editor a row per session; a Session column in the
stints; "Edit … stints" per session (a session's stints only; a race in one
keeps every stint, one with no laps yet too); a row in the laps where each
session begins, every session's flags marked. The editor: "Last day, if
it's over several", "Add a race session" (named "Race, session N", 9:00 the
next day), and a note. `dateText` on the lists, the event's page and a
driver's.

### M26.4 — Proven, then deployed (proven ✅, not deployed)

On the dev server: an event with practice Friday and a race Saturday and
Sunday, `dev-car` replayed into each (the drive split in two, a day apart
on its clock), the race page showing one race, no night lap, Sunday's
stint its own, the live lap count carrying on. Then deployed with a live
stream open.

**Proven on the dev server, 2026-10-16:** the box course, an event over
three days (practice, "Race, Saturday" ending now, "Race, Sunday" from now),
two 6-minute synthetic drives (Sunday's tablet clock 10 minutes on):
Saturday's uploaded, Sunday's streamed live with the timing lane. The race:
8 laps numbered through, none across the break, 560 s (280 + 280), a stint
each. The tablet: nothing of the race until Sunday's first crossing, then
"race lap 5, 70 s since the stop, stint 70 s". The page signed in: a flags
row per session (Sunday's green saved from it, to Sunday only), the Session
column, an "Edit … stints" per session; the editor's last day and "Add a race
session".
