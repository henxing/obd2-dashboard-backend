# Proposed: a session is only while STREAM is on

**Revision 1, 2026-10-19, from the backend, for the tablet side.** Not part
of the contract until both sides and Sam agree; this side never writes into
the app's `docs/TELEMETRY-CONTRACT.md`. If agreed, the tablet side writes it
in as a tablet note, as §26 was.

**Sam, 2026-10-19:** *"sessions should also be just the length of when the
stream was turned on, not when the car connects/disconnects"*, and that the
boundaries come from the tablet (the server can't know them: nothing in the
log says when STREAM went on or off, and the live lane's connection can't
tell a switch from a cellular dropout).

---

## What changes on the tablet

1. **STREAM switched on starts a session**, while a car is answering (or a
   tablet session, §20, while none is): a new `id`, a new header, `started`
   now. Not the car's connection: switching STREAM on with the car already
   running begins one then.
2. **STREAM switched off ends it**, as the car going quiet does now. A
   session ends at whichever comes first.
3. **While STREAM is off, nothing goes into a streamed session.** Whether the
   tablet still logs locally then (an unstreamed session, never uploaded, as
   §10 has it) is the tablet's choice.
4. **Laps as §26 already has them**: off drops the lap under way, on starts
   timing afresh (an out-lap). Each session's laps count from 1 (as they do
   since app 1.0.1 within a session).

The archive and live lanes, records and shapes don't change.

## What the server already does with it (checked against its code)

- **Several sessions in one car connection, back to back from one device**:
  one run of the app (§22.8, `:timing`'s `runs`, `at` rising, within 12 h),
  so re-timing carries across them, as it does across the weekend's.
- **Events and races:** each session joins a part by when it was heard;
  the NHMS race weekend's two race sessions each ran on several of the car's sessions (decision 43).
- **Live:** the car page follows the new session on its `session` frame (M4).
- **Laps known by when they ended, never by number** (decision 44).

## One thing the server would add

**Who's driving carries over.** A session's driver is set on the site, per
session, so each STREAM-on would start with nobody driving, and a race's
stints with no driver. The server would give a new session the driver its car
was last being driven by, when it follows that car's last session in the same
run of the app (the same device, back to back). A change still works as now.
This is a server change (a milestone of its own), wanted only once the tablet
does the above.

## Asked of the tablet side

- Agreement with 1–4, or what differs.
- Whether a STREAM toggled off and on within a second or two (a mis-tap)
  should end the session at all, or be one session.
