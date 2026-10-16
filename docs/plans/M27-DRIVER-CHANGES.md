# M27 — Driver changes within a session

A session can be **split where the driver changed**: one stored session,
each part with its own driver. **Sam, 2026-10-16:** "we stop/started a
stream, but it just picked up the session", "plan the milestone so i can
split a session", and, for what the split is for: **a driver change** (not
separate sessions, nor parts landing in different parts of an event).

> **Planned 2026-10-16**, against the code as it stands (M26 deployed as
> `00044`; the sign-in cookie's move, built, not deployed). Each step is
> validated again before it's built.

---

## What exists, and what it means for this (checked 2026-10-16)

- **The tablet decides where a session starts** (the contract's §22.8): a
  session ends when the car stops answering (the adapter drops, or the car
  is switched off), and the next starts ten seconds later. Switching STREAM
  off and on doesn't end one (§21's note on STREAM). Nothing here can make
  the tablet start one; and the app isn't changed from here.
- **A session has one driver** (`SessionRecord.driver`, a driver's id,
  `archive/.../Stores.kt`; Firestore's `driver` field), set from the
  session's page (`PUT /api/admin/sessions/{id}/driver`, or the crew's
  `/api/cars/{slug}/sessions/{id}/driver`), and by the crew from the car's
  live page (`widgets/DriverPicker.svelte`, the live session's driver).
- **Who reads a session's driver:**
  - **practice results** (`EventResults.kt`, `Results.driverBests`,
    `sectorRows`, `consistency`): grouped by each `SessionResult`'s driver;
    and so **a driver's page** (`/api/drivers/{id}`: practice bests, best per
    course);
  - **the race's default stints** (`Race.car`'s `drivers`, by session: a
    stint from each stop is driven by its first lap's session's driver);
    edited stints override them (M15.3), and a race's stints already split
    a session at any lap by hand;
  - **the live standing** (`Standings.of`: who's driving now, and the best
    lap's driver), and so the tablet's timing lane and the car page;
  - **the sessions list and a session's page** (`SessionBrief.driver`,
    `SessionDetail.driver`).
- **Laps carry their moments** on the tablet's clock (`startAt`/`endAt`, and
  `wallOffsets` per session to the tablet's `wall`), as race stints do
  (`StintMark.start`, `wall` milliseconds, "a lap is the stint's that began
  before it ended").

## Decided here

- **A driver change** is a moment in the session (`wall` milliseconds, as a
  stint's start) and who drove from then: `SessionRecord.driverChanges`.
  The session's `driver` stays who drove **from its start**. No change: as
  now, everywhere.
- **Made by lap, as a race's stints are split:** "Driver change at lap N"
  on the session's page puts the change at lap N's start; a lap is driven by
  whoever's change came before it ended. Removed again by hand. Any number.
- **Who may:** whoever may set the session's driver now (the car's editors,
  a master admin, the car's crew), through the same two routes' kin.
- **Every reader of a session's driver reads it per lap:**
  - practice results and a driver's page: a session's laps counted under
    each lap's driver (a session with a change is a row per driver in the
    part's sessions, "laps 1–8", "laps 9–15");
  - the race's default stints: a change is a stint mark too, so a stint
    starts at it (edited stints still override);
  - the live standing: who's driving now is the last change's driver; the
    best lap's driver its lap's;
  - the session's page: each lap's driver in its laps table; the list: "Sam,
    then Alex".
- **The live picker** (the crew's "Who's driving", car page): unchanged
  while the session has no laps yet (it sets who drove from the start);
  **once it has laps, choosing another driver is a driver change from
  now**. *To confirm with Sam: that's the race-day case (the car isn't
  switched off for a change) and it's what the picker would then mean.*
- **The tablet and the contract are untouched**: the change is the server's
  record of who drove, as the driver is now.

## The steps

### M27.1 — The record (pure and stores, tested) ✅

`SessionRecord.driverChanges: List<DriverChange(from, driver)>` (`from` on
`wall`, sorted, unique), in memory and in Firestore (a list of maps, read as
none when missing); `DriverChanges.driverAt(record, wall)`, pure: the
session's driver before the first change, then each change's.
Tests: none (the session's driver throughout), one, two, a change at the
first lap, the store round trip, a record from before (no field).

### M27.2 — Setting them ✅

`PUT /api/admin/sessions/{id}/driver-changes` and the crew's
`/api/cars/{slug}/sessions/{id}/driver-changes`: the whole list, each
driver known, `from` inside the session (its first and last lap, on
`wall`); logged; the tablets told (as a driver set is, M17.4). The live
picker's "from now" on the crew's route. `HiddenTest`'s route list gains
the crew's path (a hidden car's: `404`, as its others).
Tests: the admin, the crew, nobody else; a driver who doesn't exist; a
change outside the session; a hidden car's.

### M27.3 — Every reader per lap ✅

Practice results (a row per driver within a session), a driver's page, the
race's default stints (`Race.car` given each session's changes as marks),
the live standing (now, and the best lap's driver), the sessions list and a
session's detail.
Tests: `EventResultsTest` (a practice session with a change: two drivers'
bests, two rows), `RaceTest` (a change makes a default stint; edited stints
still win), `StandingsTest` (now and best), the driver page.

### M27.4 — The site ✅

The session's page: the laps table's Driver column; "Driver change at lap
N" with the driver, listed with Remove; the list's "Sam, then Alex". An
event's practice: the rows per driver. The live picker's note once it means
"from now".
Tests: the helpers (`driverAt` on the page's laps, the list's words).

### M27.5 — Proven, then deployed (proven ✅, not deployed)

On the dev server: a replay with laps, a change at lap 4 from the session's
page, the event's practice showing two drivers, a race session's default
stints split there, the live picker's change mid-stream seen on the tablet's
timing lane. Then deployed with a live stream open.

## Build notes (2026-10-16)

- **M27.1:** `SessionRecord.driverChanges` and `driverAt(wall)` (a change to
  "not set" is that, not the session's driver); Firestore's `driverChanges`,
  only when there are some.
- **M27.2:** the whole list in, sorted, checked (no two at one moment, every
  driver known, a moment on the clock, at most 50, never test data); the
  session's detail carries them. Not checked against the session's span:
  a live session has none yet. `HiddenTest` asks the crew's path for a
  hidden car too.
- **M27.3:** practice a row per driver's stretch (`Results.stretches`,
  `SessionResult.stretch`); `Race.car`'s `changes` (a default stint at each,
  its laps whoever's change came before they ended; edited stints win); the
  live standing (who now, and since when; the best lap's driver its lap's);
  **and a driver who drove only after a change can't be removed**, as one
  who drove a session can't (not in the sketch).
- **The sessions list never showed drivers**: nothing to change there.
- **The live picker** sends the change at the tablet's own "now", its
  newest record's `wall`, read on the car page: nothing in the server
  guesses the tablet's clock.
- **Found on the dev server:** an event's results answered `500` once its
  race session was complete: `stretches` read "the last stretch's driver is
  this lap's" as true with no stretches yet when the first lap's driver was
  "not set" (`null == null`). Fixed and tested.
- **Found while building:** `SessionReaderTest`'s real-log check had been
  failing since the app's two 1-second logs arrived (2026-10-07): Gradle
  doesn't count the app's folder as an input, so it hadn't rerun. Now a log
  has over 10 lines, not 1000.

**Proven on the dev server, 2026-10-16:** a practice drive, Sam from its
start; "Driver change at lap 3" to Alex from the session's page (signed in):
the event's practice two rows, Sam laps 1–2 and Alex 3–4, each his own
best, and each driver's page his. A race drive streamed live, the crew
logged in, "Who's driving" Alex mid-session: stored at the tablet's clock,
the tablet's timing lane "driver ALE" from then. A race's default stints:
laps 1–3 not set, 4–7 Alex, at a change.

## Also in this batch (2026-10-16): the live map froze

Sam: "the dot is frozen, it will update when the page is refreshed". Seen on
production (`tha-bearmobile`, app 1.0.1): the page threw Svelte's
`each_key_duplicate` hundreds of times, and each aborted update took the
map's redraw with it; the gauges kept going. The tablet counts laps from 1
again when STREAM is switched back on (app M58/M59), and the session now
goes on (v1.0.1), so one session had laps 1–6 then 1–10. The car page's laps
panel and the session page's laps table were keyed by lap number, `lapRows`
sorted by it, and the live merge dropped a lane's lap whose number it had.
Now keyed and ordered by when each lap ended (a lane's lap within 2 s of a
held one is the same lap), and the server's live laps sorted by time.
Proven on the dev server with a drive numbered 1, 2, 1, 2: the map follows,
no errors.
