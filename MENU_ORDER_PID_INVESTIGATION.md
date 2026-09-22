# OSRS Right-Click Menu Order / Player Index Investigation

**Context:** side investigation, unrelated to any specific plugin's
feature set. Question: what determines the order players appear in the
right-click menu when several overlap on one tile — and is that the same
thing as combat "PID," or something else?

## The question, precisely

- **Combat PID** is well documented (OSRS Wiki): assigned at login,
  **rotates every 100-150 ticks** (40-60 ticks on PvP/BH/tournament
  worlds), and its job is resolving simultaneous *action processing*
  each tick — who hits first, who wins a contested item pickup, etc.
- **Menu-listing order** — which order overlapping players' options
  appear when you right-click a stacked tile — is a related but
  distinct concern: a client-side interface-building question, not
  documented anywhere as confirmed identical to combat PID.

The goal is figuring out whether these are the same underlying value, or
two separate things that happen to correlate.

## Research findings so far (all verified, not guessed)

1. **RuneLite doesn't reimplement this logic.** RuneLite is a client
   wrapper/mixin layer around Jagex's own client, not a full
   reimplementation. Client scripts (`cs2`) are specifically for
   *building interfaces and handling interface input* (bank, chat box,
   dialogue) — not the base game-screen right-click menu.
2. **The public disassembled-script corpus exists but almost certainly
   doesn't contain this.** `abextm/runelite-cache-code-updater` runs on
   a schedule, uses RuneStar's decompiler, and publishes output as dated
   branches (`cache-code-2026-09-16-rev240`, etc.) to the
   `runelite/static.runelite.net` repo. This is real and was located —
   but since it only covers `cs2` interface scripts, and the world-menu
   ordering is most likely core obfuscated client code (not a moddable
   script), it's probably not in there at all. RuneLite has historically
   kept full core-client deobfuscation non-public specifically to avoid
   handing botters a roadmap, unlike interface scripts which they publish
   freely.
3. **Historical engine context (a genuine "why does this exist" answer):**
   OSRS is a preserved **August 2007 backup**, predating Jagex's "GPI"
   (Global Player Iterator) engine rewrite (~2010). Per a Rune-Server
   technical thread on the old staking-advantage exploit: pre-GPI engines
   processed players in **raw player-array index order** — your literal
   position in the server's internal player collection directly
   determined processing order, with no randomization. This is very
   likely *why* OSRS's PID system exists and feels "old-school"/gameable
   at all — the periodic 100-150-tick randomization looks like a patch
   Jagex added on top of that older, more exploitable model, specifically
   to stop players from permanently securing a low index.
4. **The concrete breakthrough — a real, usable building block:**
   Found in actual in-use RuneLite plugin source (via programcreek.com's
   indexed API usage examples, not a guess):
   ```java
   Player[] players = client.getCachedPlayers();
   int identifier = entry.getIdentifier();
   Player player = players[identifier];
   ```
   Two things this confirms:
   - **`client.getCachedPlayers()` is a real, public RuneLite API
     method** returning the raw, fixed-slot `Player[]` array — the
     direct equivalent of `NPC.getIndex()` for players (NPCs have an
     explicit `getIndex()` method; players don't, but the same concept
     is reachable via this array).
   - **For player-related menu entries** (types: `WALK`,
     `SPELL_CAST_ON_PLAYER`, `ITEM_USE_ON_PLAYER`, `PLAYER_FIRST_OPTION`
     through `PLAYER_EIGTH_OPTION`, `RUNELITE_PLAYER`), **`MenuEntry
     .getIdentifier()` *is* that player's index into
     `getCachedPlayers()`.** This is direct, not inferred — RuneLite's
     own menu-handling code resolves "which player is this option about"
     by treating the identifier as that array index.

This means the normally-invisible "index" concept isn't buried in
unreachable obfuscated code — it's sitting in menu data you already have
access to every time you open a menu on a stack of players.

## Proposed empirical method (not yet built)

Since the ground-truth answer likely isn't publicly documented anywhere,
the plan shifted from "find the source" to "test it directly":

1. **Build a small debug overlay plugin** — for every nearby player,
   draw their `client.getCachedPlayers()` index as text above their
   head. Small, standalone, doesn't need to touch any existing plugin.
2. **Multi-account test**: log in several of your own accounts, stack
   them on one tile, right-click, and record the exact top-to-bottom
   order names appear in the menu.
3. **Compare** that observed order against each account's displayed
   index at that same moment.
4. **Repeat over time**, deliberately spanning known PID rotation
   boundaries (100-150 ticks, or 40-60 on PvP worlds):
   - If the index stays fixed per account and menu order tracks it
     consistently regardless of rotation timing → menu order is driven
     by a stable slot, *not* the rotating combat-PID.
   - If menu order visibly reshuffles in step with rotation timing →
     they're likely the same underlying value.
5. **Cross-check against the known PID tell**: have two accounts attack
   the same target (or each other) simultaneously and note which
   hitsplat lands first (the standard, already-documented way to
   observe PID ordering), then compare that to menu order for the same
   pair at the same moment. Repeat across a few trials, ideally spanning
   a rotation boundary, for confidence.

## Correction to finding #4 (this fork's API has moved on)

`client.getCachedPlayers()` **does not exist in this fork's API** — it's
from an older RuneLite snapshot (this fork tracks a future content
revision and has since been refactored onto `WorldView`/
`IndexedObjectSet`). The equivalent today is
`client.getTopLevelWorldView().players().byIndex(identifier)`
(`IndexedObjectSet<T>.byIndex(int)`), reached from `WorldView.players()`
rather than a raw array.

More importantly, none of that reverse-lookup machinery is actually
needed: **`Player.getId()` already returns this same per-login index for
*any* `Player` object, not just the local player.** This fork's PvP
Tools plugin already established (via a Jagex-developer citation — see
`CLAUDE.md`) that `getId()` is the stable index used for render
priority. `MenuEntry.getPlayer()` (not `getActor()` — see `CLAUDE.md`'s
existing note on this) resolves a player-related menu entry directly, no
manual identifier/array indexing required. So the empirical build below
uses `player.getId()` + `entry.getPlayer()` throughout, not the
nonexistent `getCachedPlayers()`.

## Built

A small standalone plugin implements the debug overlay from the
"Proposed empirical method" above, plus an automated version of steps
2-3 (recording observed menu order against each player's index),
removing the need to hand-transcribe menu order during testing:

`runelite-client/src/main/java/net/runelite/client/plugins/menuorderdebug/`
- `MenuOrderDebugOverlay.java` — draws `"id=" + player.getId()` above
  every nearby player's head (local player in cyan, others in white).
- `MenuOrderDebugPlugin.java` — subscribes to `MenuOpened` (fires once,
  already-sorted, includes every entry — unlike `PostMenuSort`, no need
  to fight the game's own re-sort here since nothing is being edited).
  Walks the entries in on-screen top-to-bottom order (the array itself
  is stored bottom-to-top — last element renders first, per `MenuOpened`'s
  own javadoc), and whenever 2+ *distinct* players are involved (i.e. an
  actual tile stack, not just several options on one player), logs each
  entry's option/target text, `MenuEntry.getIdentifier()`, and the
  `Player.getId()` it resolves to — both via `log.info` and as an
  in-game message, so a screen recording across several stacked accounts
  captures the observed order, the identifiers, and the on-head labels
  together without also needing to tail a log file.

Compiles clean (`./gradlew :client:compileJava`), disabled by default
(`enabledByDefault = false` in its `@PluginDescriptor` — it's a one-off
investigation tool, not something to leave running); enable it from the
plugin panel on each test account.

## Results so far (2026-09-22 session)

Turns out we already had data without realizing it: the plugin logs to
`~/.runelite/logs/client.log` (rolling file, per `logback.xml`'s `FILE`
appender — persists across restarts, unlike the console). 13 qualifying
menu-open events (2-27 distinct players each) were captured just from
normal play in a crowded area — no deliberate multi-account stacking
needed to get a first read.

**Observation 1 — this isn't just literal same-tile stacking.** OSRS's
right-click "menu radius" pulls in every nearby entity within some
radius of the click, not only the exact clicked tile — that's why single
right-clicks were returning up to 27 distinct players at once. Good for
sample size, but means "menu order" here is being observed across a
crowd, not a guaranteed tile-exact stack; the on-head `id=` overlay +
deliberate multi-account stacking is still the cleaner test for the
literal tile-stack question.

**Observation 2 — menu order is *not* a simple ascending/descending sort
of `Player.getId()`.** From the 25-distinct-player event at 11:34:11,
the distinct-player order was id `10, 1836, 1831, 710, 113, 71, 36, ...`
— nowhere close to sorted either direction. Rules out the simplest
possible hypothesis; distance-from-click/proximity is next most likely,
which is exactly why the rebuilt plugin (below) now logs each player's
`WorldPoint` and distance from the local player.

**Observation 3 — identifier semantics differ by menu-entry type, and
this fork's `Player.getId()` still resolves correctly either way.**
Across every captured event, `MenuEntry.getIdentifier()` for `"Walk
here"` entries was consistently **exactly one more than** the same
player's `Player.getId()` (e.g. identifier=318 vs getId()=317,
identifier=1305 vs getId()=1304, identifier=11 vs getId()=10) — while
every other option (`Follow`, `Trade with`, `Lookup`, `Report`, `Cast`)
had `identifier == getId()` exactly. This is consistent with `"Walk
here"`'s identifier not being a raw player-array index at all (it's
resolved through a different `MenuAction` than the `PLAYER_*_OPTION`
family), while `MenuEntry.getPlayer()` correctly resolves the actual
player either way — confirming `CLAUDE.md`'s existing "use `getPlayer()`,
not identifier arithmetic" guidance is the right call, not just a nice
convenience. Now logged explicitly (`entry.getType()` per line) instead
of inferred from the raw numbers, so this either keeps holding or
reveals more MenuAction-specific offsets as more types get captured.

## Expanded logging (built, not yet run)

`MenuOrderDebugPlugin` now dumps everything RuneLite exposes about the
menu and every distinct player involved, each time it fires:
- **Per menu entry** (raw, top-to-bottom): `MenuAction` type, option,
  target, identifier, param0/param1, worldViewId, deprioritized flag,
  and the player it resolves to.
- **Per distinct player** (first-seen order): `getId()`, name, combat
  level, `WorldPoint` location, tile distance from the local player,
  orientation (current + target), animation + pose animation, health
  ratio/scale, dead flag, current interacting-target, friend/friends
  chat/clan flags, team, skull icon, overhead icon, and where that
  player sits in `client.getPlayers()`'s own iteration order (a second,
  independent index signal to cross-check against `getId()`).

Compiles clean, jar rebuilt. **Not yet relaunched in the running client**
— the dev client is still running the previous build, so none of this
richer logging has produced data yet.

## Round 2 results — a real pattern, from real same-tile stacks

Relaunching picked up a cluster of bot accounts auto-casting Wind Strike
on each other, genuinely stacked (identical `WorldPoint`) on tiles near
the player — the literal same-tile scenario this doc originally wanted,
captured passively, no multi-account setup needed.

**Across 8 separate single-tile stacks (sizes 3-16) one rule held in 7
of 8:** the player with the **lowest `getId()`** in the stack is listed
**first**; every other player in that stack follows in **strict
descending `getId()` order** below it. Concretely (id sequences,
top-to-bottom): `17, 2015, 1924, 1714, 1664, 1269, 1112, 1024, 729, 658,
332, 97, 96` (13-stack); `176, 2002, 1723, 1423, 1344, 970, 932, 916,
915, 656, 568` (11-stack); five more stacks of size 3-6 all fit the same
shape exactly. The one exception was a 16-player stack on the local
player's **own** tile: plain clean descending order start to finish, no
reordering — plausibly because the true frontmost entity there wasn't
in the list at all (self-cast targeting may exclude it), so what we saw
was just the already-descending "remainder."

**Why this matters:** `CLAUDE.md`'s own hard-won-lessons section already
states no mechanic is confirmed anywhere to control "which player a
left-click on a pile defaults to" — this is a real, repeatable lead on
exactly that previously-unconfirmed question. Read together, the shape
suggests **two separate mechanisms**, not one: entry #1 = whichever
player is actually the literal click target (frontmost under the
cursor), and entries 2..N = the rest of the stack in a simple, separate
descending-`getId()` sort. What still isn't known: whether "frontmost"
is a *fixed* property (e.g. literally "lowest id renders in front") or
something that *rotates* over time the way combat PID does.

A second clue, from `net.runelite.api.events.GameTick`'s own javadoc:
*"occurrences that take place purely on the client, such as right click
menus, are independent of the game tick."* That's an argument for
"frontmost" being a per-frame rendering/hit-test concern rather than
anything tied to the server-tick-driven combat PID — which is why round
3's instrumentation (below) leans on camera/mouse/hitbox data, not just
tick counting.

## Round 3 — instrumented to test "fixed vs rotating" directly

`MenuOrderDebugPlugin` now also logs, per `MenuOpened` event:
- **Mouse canvas position, camera yaw/pitch, and zoom** (`Client`'s
  `getMouseCanvasPosition()`/`getCameraYaw()`/`getCameraPitch()`/
  `getScale()`) — once per event, in the header line.
- **Per player**: whether the cursor is literally inside their click
  hitbox (`Player#getConvexHull()`, the same `Shape` other RuneLite
  plugins hit-test hover state against) and the pixel distance from the
  cursor to that hitbox's centre — a direct, sub-tile-precision
  "was this the actual click target" signal, instead of the coarse
  integer tile distance round 2 used.
- **Per player, session-tracked via `GameTick`**: how many ticks that
  player's id has been visible (`ageTicks`) and how many ticks since
  its position last changed (`ticksSinceLastMove`) — tests a "most
  recently arrived/moved wins the front slot" hypothesis alongside the
  id-based one.
- **An automatic pattern-check line** per event: whether entry #1 is the
  set's min/max id, and whether entries 2..N are strictly descending —
  so future log skims don't need hand-verification.
- **An automatic rotation detector**: keyed by the *exact sorted set* of
  distinct player ids seen together. If that same group shows up again
  with a **different** entry #1, it's logged as `*** ROTATION: ... ***`
  with the tick delta — a direct, automatic version of "does the winner
  change over time for an unchanged stack," no manual log-diffing
  needed. If the same group reappears with the same entry #1, that's
  logged too (`frontmost STABLE ...`), so a long stable streak is
  visible even without a change.

Compiles clean, jar rebuilt. **Not yet relaunched** — pending
confirmation, per this repo's standing rule of always confirming before
restarting the dev client.

## In-game tests this instrumentation is built for

1. **Stand still, don't move the mouse, right-click the same visible
   stack repeatedly** every few ticks for 150+ ticks (or 40-60+ if it's
   a PvP world). The new rotation detector will flag in the log/chat the
   moment (if ever) entry #1 changes for that exact player set — this
   alone answers "fixed vs rotating."
2. **Right-click a different edge/pixel of the same physical stack**
   (left side of the sprite pile vs right side) without anyone moving.
   If entry #1 changes with *where* you click, that's strong evidence
   for a literal pixel/hitbox hit-test driving it (check
   `hullContainsMouse`/`pixelDistToMouse` in the log) rather than a
   fixed per-player property.
3. **Rotate the camera or walk around the stack** and right-click again
   from a different angle. If entry #1 changes purely from a camera
   angle change (nobody moved), that points at screen-space/render
   z-order; if it stays the same regardless of angle, that argues
   against a purely visual explanation.
4. **Controlled multi-account version of the original plan**: log in
   several owned accounts in a *known* order, walk them onto one tile
   deliberately, and right-click. Now that there's a sharper hypothesis
   (id-based vs recency-based vs pixel-hit-based) to test, this is worth
   doing even though round 2 already got equivalent data passively —
   controlling id and login/arrival order independently is the only way
   to fully separate those two hypotheses from each other.
5. **Same test with a plain right-click (no spell selected)**, to get
   the default `Attack`/`Follow`/`Trade with` menu instead of
   `WIDGET_TARGET_ON_PLAYER` `Cast` entries — checks whether the pattern
   holds across different `MenuAction` types, and is the directly
   PvP-relevant case (attack-option ordering on a wilderness pile).

## Round 3 results — test 1 run, plus a methodological trap found and fixed

Relaunched, plugin re-enabled (it had silently reverted to disabled
across a restart — `~/.runelite/profiles2/default-*.properties` showed
`runelite.menuorderdebugplugin=false`; worth remembering if a future
round logs nothing), then test 1 (stand still, repeatedly right-click
the same stack) ran for real: 27 events, tick 176 through 224 (48
ticks), one stack shrinking from 13 down to 9 members as players left.

**Headline result: the frontmost player (`id=408`) never changed once
across all 48 ticks and 5 members leaving** — every event logged
`frontmost STABLE ... still id=408`, zero `*** ROTATION ***` lines.
Strong evidence "frontmost" is a fixed per-player property, not a
PID-style rotation — though 48 ticks is still short of combat PID's
documented 100-150 tick period, so this isn't fully conclusive yet;
worth extending test 1 past 150 ticks to be sure.

**A real methodological trap, caught and fixed**: one event (tick=191)
appeared to break the "lowest id first" pattern — entry #1 resolved to
`id=1320`, neither the set's min nor max. Turned out `"Walk here"`
entries (the top entry whenever one's present) don't target the actual
under-cursor stack at all — they resolve to whichever player is nearest
the *clicked tile*, which can be a different player on an *adjacent*
tile entirely. Confirmed directly: that entry's `param0`/`param1`
(391, 544) exactly matched the event's `mouse` canvas position, while
the resolved player (`MillaMaster`) had `hullContainsMouse=false` and a
different `WorldPoint` (`3165,3486` vs the real stack's `3165,3487`) —
while every genuine stack member, including the real frontmost `id=408`,
had `hullContainsMouse=true`. This also retroactively explains round 2's
"Walk here identifier = getId()+1" finding: it was never really about
player identity at all, since Walk-here isn't resolving a stack member
in the first place.

**Fixed in the plugin**: `MenuAction.WALK` entries are now excluded from
the distinct-player set used for the pattern check, rotation check, and
chat summary (still logged in the raw per-entry dump, just not counted
as a stack member). With that fix, the lowest-id-first/descending-rest
pattern holds with **zero exceptions** across every event captured this
session. Recompiled, jar rebuilt.

`hullContainsMouse` also turned out to be a genuinely useful validity
check on its own (true for every real stack member simultaneously, since
they're all crammed onto the same screen pixel when tightly stacked;
false for the WALK-resolved off-tile player) — even though, by itself,
it can't distinguish *which* stacked member is frontmost, since they all
read true together.

## Round 3.5 — id now visible directly in the live menu

Up to this point reading a player's id meant cross-referencing the
overlay or the log against the menu on screen. `MenuEntrySwapperPlugin`
(this repo's own cited reference for menu-entry editing) mutates
`MenuEntry.setTarget()`/`setOption()` from inside `onMenuOpened`, so the
same is possible here with no new event needed.

`MenuOrderDebugPlugin` now appends `<col=ffff00> [id:N]</col>` to each
distinct player's *first* menu entry (whichever type that happens to be,
including a `WALK` entry — a mismatched id there vs. the block below it
is now a live, in-menu confirmation of the round 3 `"Walk here"` quirk,
not just something visible in the log). Done after the raw-entry log
line so the log keeps the client's original, untagged text; only the
on-screen menu is mutated. Compiled clean, jar rebuilt.

This should make tests 2 and 3 (click a different pixel/edge of the
stack; rotate the camera) trivially fast to eyeball live — no more
alt-tabbing to the log between clicks to read who was frontmost.

## Round 5 — the sharper rule, and the real target: Attack

Re-analyzed all 42 captured stacks (2+ members) offline instead of just
trusting the live PATTERN CHECK line, prompted by the user spotting a
shape by eye that the plugin's check wasn't specific enough to catch:

**`[lowest id, highest id, then strictly descending]` - 36/42 events
(86%).** Not merely "lowest first, descending" (round 2/3's read) -
there's a distinct highest-id-second step before the descent. The
remaining 6 "exceptions" were the same two adjacent-tile leaks already
identified (`MillaMaster`, `KingLikeKong`) counted multiple times.

**Then a controlled test that matters most: 4 of the user's own
accounts, stacked and moved in/out of an attackable area repeatedly.**
`Shooter Dom` (id 1801), `Shooter Matt` (1810), `Me Overheadz` (1818),
`My Overhead` (1821). Real `Attack` menu entries (`PLAYER_SECOND_OPTION`,
not `Follow`/`Cast`) - the actual mechanic that matters for clan wars -
followed `[1801, 1821, 1818, 1810]` (min, max, descending) on **every
single capture**, including after an account left and rejoined the
stack: it slotted straight back into its correct id-sorted position,
not to the front. Zero `*** ROTATION ***` flags across 191 ticks (tick
1313 → 1504) - past both the 100-150 and 40-60 tick PID rotation
windows. This is the strongest evidence yet for "fixed, id-based front
slot," and it's on the option that was always the actual question.

**A flaw in round 4's own fix, caught by this same data**: at tick=1427,
`My Overhead` (id 1821) shared the exact same `WorldPoint` as the other
3 accounts but read `hullContainsMouse=false` anyway - ordinary
per-frame jitter in a tight stack. The `hullContainsMouse`-based stack
filter from round 4 (compiled, never actually deployed to a running
client) would have wrongly excluded a genuine member here. Switched the
filter to a `WorldPoint` majority-vote instead - exact tile equality is
the real ground truth for "same stack," not a per-frame hitbox check.
`WALK` entries are still excluded from the vote (round 3: their
resolved player is nearest-to-clicked-TILE, unrelated to the actual
stack). Compiles clean, **not yet rebuilt into a running jar** - the
client is still live from round 4/4.5, and rebuilding shadowJar under a
running client is the exact thing that corrupted it earlier this
session (see the `CLAUDE.md` lesson added after that). Rebuild + restart
together, on request.

## On deliberately changing your own id

Asked about this directly: `Player.getId()` is server-assigned - it is
not any kind of client-side setting, RuneLite config, or plugin-writable
value, so there is no legitimate way to choose or "re-roll" it from the
client. The only real lever is logging out/in or world-hopping, which
may land a different id but not a chosen one. Deliberately forcing a
specific id would mean manipulating server-authoritative state, which is
out of scope for this investigation (and for this plugin) regardless of
technical curiosity.

## Status

Core pattern - now specifically `[min id, max id, then descending]` -
confirmed on real `Attack` entries via a controlled multi-account test,
stable across 191 ticks with zero rotation. Filter logic fixed to use
`WorldPoint` matching (more robust than the briefly-considered
`hullContainsMouse` filter).

## Round 6 — a relog caught live, confirming the rule from a different angle

Deployed the `WorldPoint`-filter fix, restarted, then a live relog:
`Shooter Dom` went from id 1801 (the group's min, sitting in slot #1) to
id 1970 mid-test. Immediately became the group's max instead, and sat in
slot #2 from then on - never slot #1 again. `idsInOrder` before:
`[1801, 1821, 1818, 1810]`; after: `[1810, 1970, 1821, 1818]`. Exactly
what `[min, max, descending]` predicts.

Also confirmed: id assignment climbs on relog, 2-for-2 this session
(`Shooter Dom` 1801→1970; the observing account itself, `TrajanFan69`,
1892→1926→1950 across several relogs). But this doesn't mean relogging
is "safety" - it just moves you from occupying slot #1 (as the min) to
occupying slot #2 (as the likely-new max), since a fresh id tends to
land near the top of whatever range is currently in use on that world.
Truly escaping the front of the menu means landing a *middle* id
relative to whoever's actually in your stack, which relogging alone
doesn't obviously get you - noted as an open test.

## Round 7-8 — a real exception, and a display bug

**A genuine break in `[min, max, descending]`, not an artifact.** A
fresh batch of very-low-id accounts (`RKLB` id=10, `IGotTheDro73` id=11,
`Op in` id=18) showed up in a 5-stack alongside the established
`Me Overheadz`(1818)/`Shooter Matt`(1810) pair. Confirmed genuinely
same-tile (identical `WorldPoint` across all 5, so not the round-4
adjacent-tile-leak artifact). Result: `idsInOrder=[11, 18, 10, 1818,
1810]` - **11 is not the true minimum of the set (10 is)**, yet 11 sits
in slot #1. A repeat click moments later gave `[11, 1818, 1810, 18,
10]` - same non-minimum leader. A third click, with one account
briefly out of range, gave a plain clean descending `[1818, 1810, 18,
11]` - no reordering trick at all. This is real variance the id-only
model doesn't explain.

Two open threads worth separating:
- **Login/arrival timing, not just id value, may matter** for very
  recently-joined players specifically - `ageTicks` (visible-to-this-
  client age, not true server login time) was ~442-448 for all three
  low-id accounts and ~2109 for the two established ones, meaning the
  three joined the client's visible range at nearly the same moment.
  Whether that's *why* the ordering wobbled among just that trio is
  untested - would need the user's own actual login order for RKLB/
  IGotTheDro73/Op in to check against.
- **This location may be an instanced area, not the open world** - its
  `WorldPoint` y-coordinate (~4763-4768) is well outside normal overworld
  range, consistent with something like the Clan Wars minigame arena.
  Worth confirming directly, since an instance could plausibly run its
  own independent id/slot allocation, which would be an important
  variable this investigation hasn't controlled for yet.

**Display bug, fixed**: the in-menu `[id:N]` tag (round 3.5) was long
enough, combined with an actual `Attack` entry's already-long base text
(`<col=ffffff>Name<col=ff0000>  (level-92)`), to push slot #1's text
(shown in OSRS's own top-option/hover display, which has a hard width
cap) past its limit, truncating the name. Shortened the tag from
`<col=ffff00> [id:1970]` to a plain ` #1970` (no color, no label) -
close to the base game's own text length instead of roughly doubling
it. Compiles clean, not yet rebuilt/restarted (pending, same as the
`WorldPoint` fix before it - always on request given the corruption risk
documented in `CLAUDE.md`).

## Round 9 - working theory recorded, and a shipped PvP Tools feature

**Working theory (unconfirmed, adopted for now per the user):** id
assignment is scoped per-world, not per-area/instance - logging into a
different physical location on the same world shouldn't matter. Login
order for the three round 7-8 accounts was confirmed lowest-to-highest
(RKLB=10 first, then IGotTheDro73=11, then Op in=18) - i.e. login order
and id order matched exactly for that trio, which at least rules out
"id assignment order doesn't match login order" as a competing
explanation for the round 7-8 anomaly. Why the *menu* still put 11
ahead of the true minimum (10) that one time remains open - not
pursued further this round.

**Separate from the investigation itself**: checked vanilla RuneLite's
`MenuEntrySwapperPlugin` source directly and confirmed the user's read -
it has shift-click "Walk here" customization for NPCs
(`npcShiftClickWalkHere`), objects (`objectShiftClickWalkHere`), and
ground items (`groundItemShiftClickWalkHere`), but no equivalent
anywhere for players. Added one to PvP Tools:
`PrepToolsConfig#shiftClickWalkUnderAttackablePlayers()` (new "Menu"
section, default off) + a `PrepToolsPlugin#onClientTick()` handler that
deprioritizes a player's "Attack" entry while shift is held, same
technique as `MenuEntrySwapperPlugin`'s own NPC/object/ground-item
swaps (`setDeprioritized(true)` on `ClientTick`, guarded by
`isMenuOpen()`). Deliberately NOT the `onPostMenuSort` hook this
plugin's other menu edits use - see the code comment on
`onClientTick()` for why `setDeprioritized` specifically needs to run
*before* the game's sort (it's an input the sort reads), unlike the
reorder/recolour edits `onPostMenuSort` fixed earlier in this plugin's
history, which had to run *after* the sort for the opposite reason.
Compiles clean, not yet rebuilt/restarted.

## Backlog (not yet started - noted per the user's request)

**Feature idea**: surface a player's click-priority position *relative
to their own friends chat / clan group specifically* - e.g. "you are
slot 2 of 5 in your FC's current stack" - once the underlying mechanism
is actually understood well enough to compute this reliably. Deliberately
deferred until the round 7-8 anomaly above is resolved; building this on
top of a still-incomplete model risks giving wrong tactical advice.
