# M28 — Positions on the receiver's clock, the clearly wrong ones dropped

Sam, 2026-10-19, after the NHMS race weekend: *"start by doing the receiver
position fixes and dropping the clearly erroneous ones"*.

## Why

The weekend's GPS came from an external 10 Hz receiver over Bluetooth (the
app's M54). What the site shows of it is worse than the receiver:

- **Positions are placed by when they reached the tablet** (`wall`), not when
  the receiver took them (`fixAt`, contract §22.3). Bluetooth hands fixes over
  in bursts: on Sunday's 12:56 session, three in four positions are within
  50 ms of the one before and the rest ~470 ms later. Every speed, distance
  over time and nearest-in-time lookup on the page (lap speeds, the map's dot,
  the trace's colours) reads that bunching as the car stopping and jumping.
- **A fix the car can't have reached** draws a spike on the map and pulls a
  lap's average speed. How often, on the receiver's own clock, isn't known
  (the series doesn't carry `fixAt`); the rule below is set to catch only
  what no car could do, so it costs nothing where there are none.

Not in scope: the slow drift of 5–25 m over several seconds (the antenna and
receiver's, for the tablet side), and lap timing. Re-timing stays the tablet's
rule on the tablet's own fixes (decision 33): what stands is unchanged.

## Steps

### 1. The fix's own time

`SeriesBuilder.sample` places a position at `wall − (at − fixAt)`: the
moment on the session's `wall` clock the receiver took it. Without `fixAt`
(logs before §22.3, a receiver giving no time), at `wall` as now.

*Validated:* `SeriesBuilder.sample` (`archive/.../SeriesBuilder.kt:77`) adds
`positions` at `wall`; the record is the raw sample, so `at` and `fixAt` are
there. `fixAt` is on `at`'s clock and never later than `at` (§22.3), so the
result is never later than `wall`. `SessionTrace.fix` (`timing/.../SessionTrace.kt:93`)
already times re-timing on `fixAt` the same way; re-timing doesn't read the
series, so lap times can't move. Positions are written in arrival order
(`writeLongs`, unsorted): a `wall` corrected mid-session (M11) can now put one
before the last, so they're written in time order, as numbers are (`order()`).

### 2. Dropping what no car could do

A pure `FixFilter` in `:archive`, fed each position in order with its time on
`at`'s clock (`fixAt`, else `at`), says which to keep:

- **Wrong:** further than 30 m from the last kept fix *and* faster than
  90 m/s (324 km/h) to get there. Both, so a few metres of noise between fixes
  0.1 s apart (tens of m/s on paper) never counts, and a dropout (3.6 s and
  110 m at 30 m/s) is kept.
- **Not stuck on a wrong one:** if three fixes in a row are each "wrong"
  against the last kept but fine against each other, the last kept was the
  wrong one: those three are kept and the filter goes on from them.
- **A fix no newer than the last** (§22.3 says never) is dropped.

`SeriesBuilder` drops what it says, counts them, and writes the count as
`positions.dropped` (fixes still held back at the end count as dropped). The thinned series goes through the same builder
(`ThinSeries` passes one position a second to `builder.record`), so it's
filtered too, on its own fixes.

*Validated:* every series is a `SeriesBuilder` (`ArchiveService` prepares the
full and thinned series in one pass, `LiveSeries` the thinned one of a session
still uploading); nothing else writes `positions`. Speeds aren't needed: the
rule uses only positions and times, so it works without `gps.speed`, as on a
car without GPS speed.

### 3. Every session's series made again

`SeriesBuilder.VERSION` 2 → 3. The prepared files are keyed by it
(`ArchiveService.seriesKey`, `thinSeriesKey`) and so is the thinned series'
ETag (`SessionRoutes.kt:237`): each session's series is rebuilt from its log
the next time it's opened, as after M19's change. The old files are left
(never read again).

*Validated:* `ArchiveService.kt:540`, `:545`; on a miss, the series is built
from the log (M7.2). A long session's first open after the deploy pays one
preparation, as it did when M19.6 deployed.

### 4. The site

- `positions.dropped?` on `Series` in `lib/sessionPage.ts`, carried through
  `lib/merge.ts`. The map's note on the
  session page says how many were dropped, when any were.
- `lib/compare.ts`'s note on the positions' clock is corrected (they're now on
  the receiver's time; its crossings found on their own clock still hold).
- The live lane is unchanged: the car page's last five minutes and a live
  session's newest stretch stay on arrival time (a later step if it shows).

*Validated:* `lapTrace` finds a lap's ends on the positions' own clock
(`compare.ts:95`), so positions moving earlier by the delivery lag only make
its correction smaller. `lapSpeeds`, `speedsAtPositions`, `gpsGaps` read
`positions.t` as wall-clock times, which these still are.

## Checks

- `FixFilterTest`: noise kept, a single spike dropped, a dropout kept, three
  consistent after a wrong kept one adopted, time going back dropped.
- `SeriesBuilderTest`: positions at `fixAt`'s moment; without `fixAt` at
  `wall`; written in time order; `positions.dropped`.
- On production after the deploy: the weekend's sessions' positions dropped,
  and the bunching gone (spacing by the series' times).
