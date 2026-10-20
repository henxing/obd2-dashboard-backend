# M29 — Download an event's data: each part's raw log, merged, with who drove when

One button on an event's page gives a zip of everything recorded in it, **a
folder per part** (the practice, race session 1, race session 2), and in each
**one raw log per car**, its sessions in that part merged into one file, with
the drivers, laps, stints, flags and the rest of what the site knows beside it.
**Sam, 2026-10-20:** "the 'download race data' button that should give you the
race data, separated by practice, race session 1, and race session 2. that
exported data should merge the sessions, but keep the driver data and all
other metadata intact (including vin). it should have all the raw data
available."

> **Planned 2026-10-20**, checked against the code. Three choices below wait
> on Sam (**To confirm**); the plan is written with the recommended answer.

---

## What exists, and what it means for this (checked 2026-10-20)

- **A session's raw download** is `GET /api/admin/sessions/{id}/download`
  (`AdminRoutes.kt:313`): signed in, and `may(EDIT, car)`; logged by
  `adminLog`. It streams the stored `.jsonl.gz` as is (`downloadSession`,
  `SessionRoutes.kt:281`). The VIN is in it, in the session's header line.
- **The privacy page already promises** the VIN only to "the car's maker, the
  editors they choose, and the site's master admins" (`PrivacyPage.svelte`).
  So the raw logs in this download follow the same rule, car by car.
- **`ArchiveService.read(record)`** streams a session's lines, uncompressed,
  in order, from the one finished object or from its pieces while it's still
  uploading (M19.3), and never holds the whole session. That's what the merge
  reads.
- **Which sessions are in which part** is `EventRules.sessionsIn(event, …)`:
  by when the server heard them, plus the ones added or removed by hand; never
  fake data (§21). The event page uses the same. Each part's ids come back
  sorted by when they were first heard.
- **Who drove when** is kept as times, never as a session's place in a list:
  - a session's `driver`, and its `driverChanges` with their moments (M27,
    decision 44), on `SessionRecord`;
  - a race part's edited `stints` by car (`Stint(start, driver)`, on the
    tablet's clock), and its `green` and `flag` (`Part`);
  - the race as one timeline, `CarRace`: each `RaceLap` with its session,
    stint and times, each `RaceStint` with its driver (decision 43).

  So merging the sessions' files loses none of it: it all goes beside the
  merged log as it is.
- **The event's results** are built by `eventResults(event)`, a function
  local to `publicEventRoutes` (`EventResults.kt`). To reuse it, the download
  needs it moved out to a class both routes share. Nothing it computes changes.
- **Hidden things** (M25): `seen(event, hidden)` leaves out hidden cars,
  together with their sessions and stints.
- **Per-lap speeds** (top, low, average) are worked out only in the browser
  today (`web/src/lib/lapSpeeds.ts`), from a session's series.
- **Deploy settings** (decision 20): one instance, concurrency 1000, timeout
  3600 s. A download runs on the same instance as the cars streaming live, so
  it must stream its output and not starve them.

## Decided here

### The zip

The file is `{event}-data.zip`, laid out like this:

```
README.txt                    what each file is, which cars were left out and why
event.json                    the event as stored: parts with their windows,
                              green and flag, edited stints, sessions added or
                              removed by hand; the drivers it names (id, name, code)
results.json                  the event's results as its page shows them
1-practice/                   one folder per part, in time order, named after the part
  tha-bearmobile/
    raw.jsonl                 the car's sessions in this part, merged (below)
    sessions.json             each session: id, start and end on the tablet's clock and
                              when the server heard it, its driver and driver changes,
                              its name, complete or still live, clock offset,
                              line count and SHA-256
    laps.csv                  every lap: session, lap number, start and end (tablet's
                              wall and time of day), time, sectors, in/out lap,
                              tablet or re-timed, driver (code and name)
2-race-session-1/
  tha-bearmobile/
    raw.jsonl, sessions.json, laps.csv
    stints.csv                race parts only: each stint's driver, start, first and
                              last lap, laps, time, best; edited or default
3-race-session-2/  …
```

### The merged raw log (`raw.jsonl`)

- **Every line is exactly as the tablet sent it**, byte for byte, VIN
  included. Nothing is added to a line or taken out.
- **The car's sessions follow each other** in the order they were first
  heard, each starting with its own `session` header line. The header is what
  says where one session ends and the next begins: `seq` counts from 0 again
  after each header, and the header carries the session's id.
- **Each session's lines** are exactly its stored lines: what the session's
  own download gives, uncompressed.
- **Every session ends with a newline**, even if its last stored line has
  none, so two sessions' lines never run together.

### Who gets what

- **The button is for anyone signed in** who may edit at least one of the
  event's cars.
- **Each car's folder is in the zip only if you may edit that car**: its
  creator, its editors, or a master admin, as for one session's download.
  Cars you can't edit are named in `README.txt` as left out. `event.json` and
  `results.json` are public anyway, so they're always in.
- **Hidden cars** are left out as on the event page, unless you're signed in
  and may see them.

### The rest

- **A session still live or uploading** is included up to what's stored, as
  its own download does today, and is marked `"complete": false` in
  `sessions.json`.
- **Sessions in no part**, between parts or outside the event's days, aren't
  included, as on the event page. Neither is fake data.
- **One download at a time:** a second one while another is running gets 429
  "Another download is running; try again in a minute". The zip is written
  while it's sent, never held whole, on `Dispatchers.IO`.
- **The server and the site change. Nothing on the tablet or in the contract
  changes.**

### To confirm (Sam)

1. **Who gets it:** car by car, only cars you may edit (the privacy page's
   rule). The other choice is anyone, VIN included, which would change the
   privacy page's promise.
2. **The merge:** sessions back to back, each line unchanged, the session
   header line as the divider. The other choice is adding a `"session"` id to
   every line, which is easier to load into a spreadsheet, but then the lines
   are no longer exactly what the tablet sent.
3. **Lap speeds in `laps.csv`** (top, low, average): they need the browser's
   rule (`lapSpeeds.ts`) moved to Kotlin and each session's series read.
   Recommended as its own last step, M29.6, so the rest isn't held up.

## The steps

### M29.1 — The merge (pure, tested)

In `:archive`, `RaceLog.write(sessions, out)` takes the sessions in order
plus a way to read each one, and writes their lines back to back, adding a
newline wherever one is missing.

Tests:
- two sessions come out byte for byte;
- a last line without a newline;
- an empty session;
- a session still in pieces (segments);
- the order kept as given.

### M29.2 — What goes beside it (pure, tested)

`eventResults` moves out of `publicEventRoutes` into an `EventResultsService`
that both routes use. The event page's tests must pass unchanged.

`RaceExport` (in `:server`) builds `event.json`, `sessions.json`, `laps.csv`,
`stints.csv` and `README.txt` from the event, its records, the drivers and the
results.

Tests:
- a lap after a driver change carries the new driver;
- edited stints are marked edited;
- times of day use each car's clock offset;
- CSV quoting for driver and session names with commas or quotes;
- left-out cars are named in the README.

### M29.3 — The route

`GET /api/admin/events/{id}/download`:
- signed in; 404 for an event you can't see (hidden);
- 403 "You can't edit any of this event's cars" if no car's folder would be
  in it;
- streams a `ZipOutputStream`; one at a time;
- `adminLog`: "event downloaded: {id}, cars {…} by {who}".

Tests on the in-memory archive:
- the zip's entries and the merged log's bytes;
- an editor of one of two cars gets only that car;
- a master admin gets every car;
- not signed in: 401;
- a hidden car;
- a live session included and marked;
- a second download at once: 429.

### M29.4 — The button

On the event page, a **Download event data** button beside Edit, shown when
signed in and allowed to edit one of its cars (`allowedFor('car', …)` for
each). A note under it says what's inside and that it holds the cars' VINs.

The privacy page gets: "…only in a session's raw download, or an event's,
which only…".

Checked on the dev server: a synthetic event with two parts and a driver
change; the zip opened, and the merged log compared line for line with each
session's own download.

### M29.5 — Deployed, and measured on NHMS

Deployed as usual: a smoke car streaming live across the deploy.

Then Sam presses the button on the NHMS event (the download holds the VIN,
so it's his to download). From the logs and `admin.sh`, measured: the zip's
size, how long it took, the instance's memory while it ran, and no 1012
reconnects on a live stream during it.

The numbers go to JOURNAL.

### M29.6 — Lap speeds in `laps.csv` (if confirmed)

`lapSpeeds.ts`'s rule in Kotlin, with the site's tests ported. Top, low and
average are read from each session's prepared series (positions on the
receiver's time and filtered, decision 45), so the numbers match the
session's page.
