# M24 — "GPS lost", said while it happens and after

When the car is moving but the tablet has sent no GPS position for a while,
the car's live page says so, and a session's page marks the gap. Laps are
timed from the tablet's GPS (decision 31), so without it there are no laps
and no route, and the crew should know while there's still time to fix it.
**Sam, 2026-10-07:** "ok sounds good make that change", after the GTI's
2 October afternoon session at Palmer drove for 45 minutes with no GPS (it
stopped 0:48 in and came back at 53:00) and so had no laps.

> **Planned and validated 2026-10-07**, against the code and that session.

---

## What exists, and what it means for this (checked 2026-10-07)

- **The live page keeps five minutes of history** (`lib/live.ts`, `Point`s
  of `{ t, rec }`, in the server's time), and `timings` finds each signal's
  last reading in one pass. A gap longer than the window is "at least
  5 minutes", not its true length.
- **Positions are the `gps.position` samples** (`lat`, `lon`). The car's
  own speed is `vehicle.speed`, which needs no GPS: that's how a moving car
  with no fix is told apart from a parked one. Both are named in the
  contract, not in any one car's facts.
- **A session's series** has `positions.t` and `numbers['vehicle.speed']`
  (`{ t, v }`), in ms from the session's start. **A thinned series keeps a
  position every second** (`ThinSeries`, `positionMs` 1,000), so a 30 s gap
  isn't something thinning makes.
- **The 2 October session** (`66615e1d`): its full series has positions
  until 0.8 min, none until 53.0 min, while `vehicle.speed` reached 185 km/h
  from 6.2 to 51.4 min. That's the case to catch.
- **The banner** on the car page (`.banner`, its `stale` style in
  `caution`) is the pattern for a warning that must be seen.

## Decided here

- **Lost:** the car's latest speed is at least **15 km/h** and that reading
  is current (≤ 10 s old), and no position has come for **30 s**. It clears
  as soon as one does.
- **Live:** a second banner under the car's banner, in `caution`: "GPS lost:
  no position for 1 min 20 s while moving. Laps can't be timed until it's
  back. Check the tablet's location setting and its view of the sky." With no
  position in the whole five-minute window, "for over 5 min".
- **A session's page:** each gap of 30 s or more in which the car was moving
  is listed under the session's facts. For example: "GPS lost for 52 min
  (0:48–53:00) while the car was moving: no route or laps then." It's the
  tablet's GPS, so the note says nothing about the server.
- Only the website changes. The server, the contract and the tablet are
  untouched.

## The steps

### M24.1 — The rule (pure, tested) ✅

`web/src/lib/gps.ts`:
- `gpsLostLive(history, serverNow)` returns `{ seconds, atLeast }` or null;
- `gpsGaps(series)` returns `[{ from, to }]` in ms from the start.
Tests: the 2 October shape, a parked car with no fix (not lost), a stale speed
reading (not lost), a fix arriving (cleared), a gap with no movement in it
(not listed), gaps at the start and at the end, and thinned positions every
second (no false gaps).

**Built, 2026-10-07**, as sketched: 8 tests. The stretch after the last fix
runs up to and including the session's end, so a car still moving at its
last reading counts (found by its test).

### M24.2 — Shown

The car page's banner and the session page's note, with colours from
`app.css` roles. Checked on the dev server with a replay that has its GPS cut
out, then deployed.

**Built, 2026-10-07.**
- **The car page:** a second banner under the car's own, in `caution` at a
  smaller size (the message is a sentence). It shows only while the car is
  live.
- **The session page:** one note per gap, under the facts.

**Proven on the dev server:** the 2026-09-28 morning drive, a scratch copy
with every `gps.*` line cut from 3.5 to 7.5 minutes (moving from 2.5),
replayed at real time with `--live`. Headless Chrome watching the car page
saw "GPS lost: no position for 34 s while moving…" 205 s in, with the car at
43 km/h and the GPS widgets "not updating".
Once GPS returned, the banner cleared, and the session's page read "GPS lost
for 4 min 2 s (3:30–7:32) while the car was moving: no route or laps then."
On the real 2 October series (read from the public API into a scratch
folder), the afternoon session gets one note, "52 min (0:45–52:59)", and the
morning session at Palmer none.

**Still to do:** deploy, then a decision and `COMPLETED`, and the plan
deleted.
