# M25 — Hidden: cars, courses and events only signed-in users see

A car, course or event can be marked **hidden**: then only someone signed in
sees it. To everyone else it doesn't exist, the same "not found" as a thing
that never did. **Sam, 2026-10-15:** "if i wanted certain cars/courses/events
only visible to logged in users, how big a change is that?", and then:

1. who sees a hidden thing: **any signed-in user** (no teams, and none
   planned for now);
2. a hidden car in a visible event: **left out of the event** unless signed in;
3. the crew code: "I'm not sure how they would find the car to use the code?"
   (see *Decided here*);
4. new things are **visible**, as now.

> **Planned 2026-10-15**, against the code as it stands (M24 and the
> timing-line fix deployed). Each step is validated again before it's built.

---

## What exists, and what it means for this (checked 2026-10-15)

- **Every car, course, event and driver can have an access record** (M23,
  `admin/.../Access.kt`): `Access(creator, editors)`, kept in Firestore at
  `access/{kind}:{id}` (`archive-gcp/.../FirestoreUserStore.kt`). A thing
  made before M23 has none, and is the master's alone. `AccessStore.all()`
  already returns every record, for filtering a list.
- **Every request can say who it is** (`Gate.kt`: `Gate.who(cookie)`,
  `call.signedIn(change)`, `call.may(action, thing)`); a public read never
  asks today.
- **The public reads** (decision 11: the site is public), 18 of them:
  - cars: `GET /api/cars`, `/api/cars/{slug}/live` (the browser's live
    feed), `/api/cars/{slug}/messages`, `/api/cars/{slug}/sessions`,
    `/api/cars/{slug}/crew`;
  - sessions: `/api/sessions/{id}`, `…/laps`, `…/live-laps`, `…/messages`,
    `…/series` (each names its car, `record.car`);
  - courses: `/api/courses`, `/api/courses/{id}`, `/api/courses/{id}/versions`;
  - events: `/api/events`, `/api/events/{id}` (results and standings,
    `EventResults.kt`, `Standings.kt`, `RaceRoutes.kt`, naming cars, drivers
    and a course);
  - drivers: `/api/drivers`, `/api/drivers/{id}` (a driver's sessions, and so
    their cars).
  The landing page is made from these lists, so it follows them.
- **Tablets read courses with a car token** (`GET /v1/courses`, one `ETag`
  for the whole set, contract §22.3), not a sign-in.
- **The crew's cookie is scoped to `/api/cars/{slug}`** (`CrewRoutes.kt`):
  it reaches the car's own routes, not `/api/sessions/…`.
- **Caching:** public API answers carry no `Cache-Control` today, and Cloud
  Run has no shared cache in front. Pages are `no-cache`; `/api/admin/*` is
  `no-store` (`AdminNoStore`).

## Decided here

- **The flag lives on the access record:** `Access(creator, editors, hidden
  = false)`. A thing with no record is visible, as now; hiding one makes its
  record (creator "", the master's, as M23 already reads it).
- **Who may hide or show:** whoever may **SHARE** it (its creator, or the
  master), from the Editors panel; and `admin.sh hide` / `show`.
- **A hidden thing to someone signed out:** `404`, word for word the answer
  for one that doesn't exist; left out of every list.
  - a **hidden car** takes with it its sessions (and their laps, series,
    messages), its live feed, and its rows in events and a driver's page;
  - a **hidden course** is left out of the course list, and an event at it
    shows no course (its name or link);
  - a **hidden event** is left out of the event list, and its sessions' event
    name (`/api/sessions/{id}/laps` names it) isn't said.
  - **Drivers aren't hidden themselves**; their page leaves out what's hidden.
- **Tablets still get every course,** hidden or not: `/v1/courses` is
  untouched. A hidden car streams and records exactly as now.
- **The crew code (question 3): a hidden car has no crew access for someone
  signed out.** The crew could only reach it from a link someone sent them,
  and opening it would have to say the car exists. A crew member who needs a
  hidden car is added as a user (Users page) and signs in. **Sam,
  2026-10-15:** "whichever is easier to implement is fine": this one.
- **Answers that differ by sign-in are `Cache-Control: private, no-store`**
  with `Vary: Cookie`, so nothing in between keeps a signed-in answer for
  someone signed out.
- **On the page:** a "Hidden" badge beside a hidden thing's name, signed in,
  so it's plain who else can see it; the toggle with the editors.

## The steps

### M25.1 — The flag (pure, tested) ✅

`Access` gains `hidden`; both stores keep it (Firestore's field read with a
default, so records from before read as visible); `Visibility`, one place
that says whether a thing is visible to a request: `visible(thing, who)`, and
`visibleSet(kind, who)` for a list, from one `AccessStore.all()`.
Tests: no record (visible), a record without the field (visible), hidden and
signed out (not), hidden and signed in (seen, any user).

**Validated 2026-10-15:** `Access` is a data class in two stores; the editors
route rebuilt it from scratch (`Access(creator, editors)`), which would have
dropped the flag: now a `copy`. **Built** as sketched, but `Visibility` is
`Hidden` (what's hidden from one request, worked out once per request) and
`HiddenThings` (every hidden thing from one `AccessStore.all()`, held 5 s, as
`admin.sh` writes Firestore directly; forgotten at once on a change through
the server). Firestore reads a record without the field as shown.

### M25.2 — Every public read asks ✅

Each of the 18 routes asks `Visibility`: one thing, `404`; a list, filtered;
events, results and a driver's page with hidden cars and courses taken out.
**The guard:** one test makes a hidden car (with a session and messages), a
hidden course and a hidden event, then reads **every public route** signed
out and finds none of their ids or names anywhere, and the same routes
signed in finding them. The route list in the test is checked against the
server's own routing tree, so a public route added later without a check
fails it.

**Built, 2026-10-15.**
- **One plugin for every path about one thing** (`HideFromSignedOut`):
  `/api/cars/{slug}…`, `/api/courses/{id}…` and `/api/events/{id}…` answer
  `404` before any route runs, the live feed and the crew's login included,
  so a route added under them later is covered too. Each `404` is word for
  word the route's own for one that never was; the test compares them. It
  found one that wasn't: an unknown course's versions were `[]`, now `404`.
- **Filtered in place:** the car, course and event lists; a session (`404`
  with its car); events and results (`seen`: the event without hidden cars,
  their sessions and stints; a hidden course not named), held per what's
  seen; a driver's page; a session's laps (still timed on a hidden event's
  course, the course not named if hidden).
- **Every public answer** is `private, no-cache` (unless the route says
  otherwise: the series keep their ETags) with `Vary: Cookie`.
- **Found by the dev server, not the tests: the sign-in cookie was
  `Path=/api/admin`** (decision 25), so a browser never sent it to the public
  API, and signed in saw nothing hidden either. Now `Path=/api`; sign-in and
  sign-out clear one at the old path. The tests had set the cookie by hand: a
  new one checks the real cookie's path covers every public read.

### M25.3 — The toggle ✅

`PUT /api/admin/access/{kind}/{id}/hidden` (SHARE, Origin as every change);
the Editors panel's "Only signed-in users can see this"; the Hidden badge on
lists and pages; `admin.sh hide <kind> <id>` and `show`.

**Built, 2026-10-15**, as sketched; a driver is refused. The "Hidden" mark
rides on the public answers (`"hidden": true`), said only to someone signed
in: never written for anyone else. Badges on the car, course and event lists
(and the landing page), a course's page and an event's. An event at a hidden
course reads "date · cars", not "date · , · cars".

### M25.4 — Proven, then deployed (proven ✅, not deployed)

On the dev server: a car hidden and replayed into, its page and live feed
seen signed in and `404` signed out; an event with it, with and without; a
hidden course still timing laps on the tablet's lane. Then deployed with a
live stream open, and the signed-out reads checked on production with a
throwaway hidden car.

**Proven on the dev server, 2026-10-15:** `dev-car` and NHMS hidden, the
2026-09-28 morning drive replayed live into the car. Signed out: no cars, no
courses, the car's page "No car", its session and live feed `404`. Signed in:
both listed with "Hidden", the session `200`, the live feed streaming. The
tablet's `/v1/courses` still had NHMS. The Editors panel's checkbox showed
it hidden, with the note.

**Still to do at the end:** a decision (the flag, who sees, crew), and
`COMPLETED`, and the plan deleted.
