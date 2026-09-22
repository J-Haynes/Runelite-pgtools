# This repo

A fork of RuneLite tracking a future OSRS content revision (see recent commits like "Update
GameVals to 2026-09-16-rev240"). The active work in this repo is a single plugin:

## PvP Tools (`preptools`)

Location: `runelite-client/src/main/java/net/runelite/client/plugins/preptools/`

A team-PK / multi-clanning coordination tool — the kind of thing a small clan runs together while
fighting as a team in the wilderness. Inspired by [Exilent](https://exilent.org), a PK-focused OSRS
client with similar team-coordination features. Core pieces:

- **Caller list** — primary/backup callers, reorderable/deletable from a sidebar panel, one-click
  primary switching.
- **Highlighting** — three independent detection categories, each with its own config section and
  colour, but sharing one rendering style (shape/outline width/font/dot size — the config's
  "Overlay" section, `PrepToolsConfig#highlightShape()` etc.) so every highlighted player looks
  visually consistent no matter which category put them there. Priority when a player qualifies
  for more than one at once is pile finder < caller < target (`PrepToolsPlugin#computeHighlightColors()`),
  i.e. a target overrides its own pile-finder highlight.
  - **Caller** — the primary caller and any backup callers from the caller list get an outline
    (default green for primary, a separate colour for backups), driven entirely by the configured
    caller list matching on-screen player names (`PrepToolsPlugin#updateCallersAndTargets()`).
  - **Target** — whoever the *primary* caller is currently interacting with (attacking, following,
    etc. — `Player#getInteracting()`), i.e. one player at a time, only ever derived from the primary
    caller, never a backup. Wins over a pile-finder highlight on the same player
    (`PrepToolsConfig#targetColor()`'s doc comment: "Always wins over the pile finder colour").
    Optionally flashes a configurable colour for a moment when a *new* target first appears
    (`showTargetFlash()`/`targetFlashColor()`/`targetFlashDurationMillis()`), and can be prioritised
    to the default left-click Attack action (`prioritiseTargetAttack()`).
  - **Pile Finder** — a different mechanic: detects an *uncalled* pile by counting, per candidate
    target, how many of your own friends chat members are simultaneously interacting with the same
    non-FC player (`PrepToolsPlugin#updateTargetedPlayers()`); once that count meets a configurable
    threshold (`targetedPlayerThreshold()`, default low but user-settable), that player is
    highlighted even though nobody explicitly called them. FC-on-FC interactions (sparring) and
    interactions targeting a configured caller are excluded from the count so callers can never
    accidentally trip their own team's pile finder. Requires being in a friends chat to work at all
    (no FC members to count = feature is a no-op). Can also be prioritised to left-click Attack
    (`prioritiseAttackForPiles()`), subordinate to the target's own priority.
- **Freeze tracking** — detects Bind/Snare/Entangle/Ice-spell hits, shows icon + countdown, can
  hide/block freezes against teammates.
- **Teleblock tracking** — same idea as freeze tracking, but for the teleblock spell (icon +
  countdown; 5 min normally, 2.5 min if the target was praying Protect from Magic when it landed).
- **Snipe list** — a flat, always-on highlight list (default red), independent of caller/target
  status. Shift-right-click a player to add them, same idiom as "Add caller" (default green).
- **Infobox panel** — bottom-right HUD: your stats (HP/prayer/strength/ranged — prayer and
  strength colour green while boosted at/above a threshold), freeze/teleblock status, target's
  prayer, opponent prayers/weapons (melee/ranged/magic — melee/ranged/magic weapon IDs live in
  `PrepToolsPlugin`'s `MELEE_WEAPON_IDS`/`RANGED_WEAPON_IDS`/`MAGIC_WEAPON_IDS`), who's attacking
  you, current wilderness location, your player index (informational — see the PID note below).
- **Friends chat log** — sidebar panel (the "FC" tab, alongside the "Missing" list), newest-first,
  logs members joining/leaving/dying; copyable via a header Copy button.
- **Pile Spam** (was "Chat Spam") — hotkey (F12 default) that sends the name of whoever you're
  following/piling straight to **public/game chat**, always — no channel option (removed
  deliberately: this is for whoever's actually looking at your screen in the moment, not a
  coordination channel). Config keys kept as `chatSpamHotkey`/`chatSpamPrefix` (only the section/
  method names changed) so an already-configured hotkey isn't lost by the rename.
- **Location Spam** — hotkey (F11 default) that sends your current wilderness location to chat in a
  **fixed format, no configurable prefix**: `Location: W318, Ferox Enclave, lvl 15 wild` (world,
  then name, then the wilderness level if known - the "lvl N wild" part is omitted entirely, comma
  and all, if you're not in the wilderness). World number was briefly dropped, then asked back -
  it's back for good now, no need to ask again. Sends on a **configurable channel** (Friends Chat
  default, Clan Chat also selectable via `locationSpamChannel`) — unlike Pile Spam, this one's
  meant for a specific coordination channel.
  Both hotkeys actually *send* (not just pre-fill) via `PrepToolsPlugin#sendChatMessage()` /
  `client.runScript(ScriptID.CHAT_SEND, ...)`.
- **Location Mapper** (sidebar "Location" tab, builds the `WildernessLocation` list Location Spam
  reads from) — coordinate fields are labelled by cardinal direction + axis (West/East X,
  South/North Y), not "Corner A/B"; focused on one, Up/Down arrow keys nudge its value by ±1
  (`PrepToolsPanel#installArrowKeyAdjust()`); every change (arrow-key or typed) live-previews the
  edited box in a distinct colour via `WildernessLocationDebugOverlay`, before Save is clicked
  (`PrepToolsPlugin#updateLocationPreview()`/`#clearLocationPreview()`). The debug setting
  `showWildernessLocationOverlay` ("Show/edit wilderness locations") gates both that overlay *and*
  a shift-right-click-the-ground "Set Corner A"/"Set Corner B" menu option
  (`PrepToolsPlugin#onMenuOpened()`), an alternative to the sidebar's own Set Corner buttons that
  doesn't require standing exactly on the tile - it captures the clicked tile, not your own position.
- **Team cape overlay** — like vanilla RuneLite's "Teams" plugin, but with a custom name per team
  number (`PrepToolsConfig#teamCapeNames`).
- **Right-click menu integration** — prioritises/recolours Attack and freeze-spell casts (target,
  pile, and now snipe-list hits too, with a configurable tie-break order —
  `PrepToolsConfig#attackPriorityOrder()`, default Target > Pile > Snipe — when more than one
  applies to the same menu), blocks casting a freeze spell on a teammate, and (opt-in) shift-click
  "Walk here" on attackable players — vanilla RuneLite's Menu Entry Swapper has this for
  NPCs/objects/ground items but not players.
- **Clan IDs panel** — sidebar tab surfacing your own and your friends chat members' player ids
  (`Player#getId()`), sorted, with the current min/max flagged as click-priority risks and
  configured callers highlighted — see `MENU_ORDER_PID_INVESTIGATION.md` at the repo root for the
  underlying mechanic this is built on (right-click Attack-menu ordering on a stack) and its current
  confidence level/open questions. Clan-only by design: OSRS's friendly-fire protection means an
  enemy's own menu on your team's pile is built exclusively from your team's ids, so enemy ids don't
  factor into who on your side is exposed.

Config file (`PrepToolsConfig.java`) is the fastest way to see the full feature surface — it's kept
well-commented and organized by section, sections ordered/all collapsed-by-default to match the
sidebar's own tab order. The sidebar (`PrepToolsPanel.java`) is a `MaterialTabGroup` in a fixed
2-row/3-col grid (Callers/Snipe/Clan IDs/FC/Capes/Location tabs), icon+label centred in each cell.
Every tab's icon is a **real in-game image**, not a bundled file (this plugin has no resource assets
of its own) - a UI sprite via `SpriteManager#getSpriteAsync()` (`PrepToolsPanel#loadTabIcon()`) for
five of them, but Capes uses an actual team cape **item** icon via `ItemManager`
(`#loadTabItemIcon()`, `TeamCapeOverlay.teamCapeItemId(30)`) since the generic equipment-slot sprite
tried first rendered as a blank placeholder, not a real cape. Every section follows the same
three-tier visual style, spaced apart with explicit `Box.createVerticalStrut(TIER_GAP)` calls
(border-insets alone read as "no spacing") - `createSectionTitle()` (bold + brand-orange underline),
`createDescriptionArea()` (muted text + left accent stripe), `styleInputRow()` (padding only, no
background/border box - tried a boxed-card look, asked to remove it while keeping the spacing).

## Open questions / unverified

- **`ScriptID.CHAT_SEND`'s "modes" integer: Friends Chat (2) and Clan Chat (1) are CONFIRMED
  in-game** (`PrepToolsPlugin`'s `CHAT_MODE_FRIENDS_CHAT`/`CHAT_MODE_CLAN_CHAT` constants) - the
  first guess had them swapped, fixed once real testing showed Location Spam landing in the wrong
  channel. `CHAT_MODE_PUBLIC = 0` (used by Pile Spam) is still an **unverified guess** - neither
  RuneLite's own source/docs nor a web search turned up the literal value, and no other call site of
  this script exists anywhere in this codebase to copy a known-good value from. If Pile Spam doesn't
  land in public/game chat, this is the line to fix.

## Build & run

```
./gradlew :client:compileJava     # compile-check only
./gradlew :client:shadowJar       # build runelite-client/build/libs/client-*-shaded.jar
./run-runelite.sh                 # launch the locally built client (2x UI scale)
```

**Always ask before killing/relaunching the running dev client**, even after rebuilding the jar and
even mid-task. Rebuilding is fine to do without asking — that's non-disruptive. Restarting the
client kills whatever game session is active (possibly mid-fight), so confirm first every time,
unless the user's own message already explicitly says to restart.

## Packaging for non-technical users

For someone who just wants to download-and-run this fork rather than clone/build it themselves,
`runelite-client/build.gradle.kts` has two `jpackage`-based Gradle tasks (group `distribution`):

```
./gradlew :client:jpackageImage        # self-contained app folder, bundled JRE, no installer
./gradlew :client:jpackageInstaller    # native installer - .deb on Linux (double-click to install,
                                        # Start Menu/Applications shortcut, no separate Java needed)
```

Both depend on `shadowJar` and stage *only* the shaded jar into their own `--input` directory
before calling jpackage - pointing `--input` straight at `build/libs/` (which also holds the
plain/`-sources`/`-javadoc` jars) would bundle all of them into the app for no reason, ~15MB of
dead weight. Output lands under `runelite-client/build/jpackage/{image,installer}/`.

- **jpackage needs a JDK 14+ to run**, even though the app itself still targets Java 11 - this
  machine only has 11 installed. `settings.gradle.kts` adds the `foojay-resolver-convention`
  plugin so Gradle's Java toolchain support can auto-download and cache a JDK 17 under
  `~/.gradle/jdks` on first use, with **no sudo/system-wide install** - that's the whole reason
  that plugin is there instead of just `apt install openjdk-17-jdk`.
- **jpackage cannot cross-compile.** Running these tasks on Linux only ever produces a `.deb`.
  A Windows `.exe`/`.msi` or macOS `.dmg`/`.pkg` needs this exact same task run ON that OS -
  `.github/workflows/package.yml` handles this with a build matrix (one job per OS, each running
  `./gradlew :client:jpackageInstaller`, each uploading its own platform's artifact). Triggered by
  either the Actions tab's manual "Run workflow" button (uploads as plain build artifacts, no
  public release) or pushing a `v*` tag (additionally publishes those same files to a GitHub
  Release). Only runs on a repo you can push to and that has Actions enabled - not the upstream
  `runelite/runelite` this repo's `origin` remote points at by default.
  `jpackage.appName`/`jpackage.vendor`/`jpackage.type` are exposed as Gradle properties
  (`-Pjpackage.appName=...`) if the defaults ("RuneLite PvP Tools") need overriding per-platform.
- **Windows/macOS icons aren't wired up** - only a `.png` (reused from the client's own
  `runelite_128.png`) is passed on Linux, since `--icon` needs a format-matched file per OS
  (`.ico` for Windows, `.icns` for macOS) that doesn't exist in this repo yet. Without one,
  jpackage silently falls back to its own generic default icon rather than failing - easy to miss.
- **Adding a new Gradle plugin (like `foojay-resolver-convention`) needs its checksum added to
  `gradle/verification-metadata.xml`**, or the build fails with "Dependency verification failed"
  the first time anything resolves the new plugin. Fix: run any task once with
  `./gradlew --write-verification-metadata sha256 <task>` - it computes and appends the missing
  entry (or entries, if the plugin pulled in new transitive deps) automatically. Always `git diff`
  that file afterwards to confirm only the expected new `<component>` entries were added before
  trusting it, since it's a security-relevant file.

## Hard-won lessons from building this plugin

These cost real debugging time to find. Don't rediscover them the hard way:

- **Menu entry edits (reorder/recolour/remove) must happen in `onPostMenuSort`, not
  `onMenuEntryAdded` or `onClientTick`.** `PostMenuSort` fires after the game's own internal menu
  sort; edits made earlier get silently overwritten by that later sort. `MenuEntrySwapperPlugin` in
  RuneLite core is the proven reference for this — same event, same `isMenuOpen()` guard.
- **Use `MenuEntry.getPlayer()`, not `getActor()`, to resolve a player-targeted menu entry.**
  `getActor()` doesn't reliably resolve for every `MenuAction` type (e.g.
  `WIDGET_TARGET_ON_PLAYER`, the type used for a manually-cast spell). `getPlayer()` does, across
  every player-related action type — confirmed via `PlayerIndicatorsPlugin`'s own `onClientTick()`.
- **A manually-cast spell's menu option is always literally `"Cast"` — never the spell's name.**
  The real name lives in the **target** field instead, formatted like
  `<col=...>Snare</col><col=...> -> <col=...>PlayerName<col=...>  (level-92)`. Parse it from there
  (text before the first `" -> "`), not from the option. `client.getSelectedWidget()` (checked
  against `InterfaceID.MAGIC_SPELLBOOK`, then `.getActions()[0]`) is a good second, independent
  signal for "which spell is selected" when you need it.
- **`PostMenuSort` can fire at up to render-frame frequency** while the cursor rests over any
  interactive entity — not once per 600ms game tick like most of this plugin's other logic. Any
  logging or expensive work added inside it must be throttled independent of call frequency (a
  timestamp-gated "at most once per N ms" check), or it can flood the log and freeze the client.
  This actually happened once — unthrottled per-entry debug logging inside `onPostMenuSort` froze
  the client the first time someone left "Debug logging" on while hovering a full menu.
- **Don't allocate a new `BufferedImage`/`Graphics2D` every `render()` call in an `Overlay`.**
  Allocate once (lazily, on first render), clear it each frame with
  `graphics.setComposite(AlphaComposite.Clear); graphics.fillRect(...)`, reuse it. A fresh
  allocation every frame (~60/sec) was a measurable FPS cost before this was fixed.
  Related: cache expensive per-player computed state (e.g. per-player highlight colour/style) once
  per `GameTick`, not inside `render()` — `render()` runs once per frame per overlay, so anything
  computed there runs far more often than the underlying game state actually changes.
- **Wilderness level: read the game's own widget, don't derive it from coordinates.**
  `client.getWidget(InterfaceID.PvpIcons.WILDERNESSLEVEL)` returns a widget whose text is
  `"Level: N"` (null when not in the wilderness at all) - this is exactly what
  `CombatLevelPlugin` (RuneLite core) reads for the same purpose. Far more robust than a
  hand-written Y-coordinate formula, especially in a fork tracking a future content revision
  where wilderness geometry could shift.
- **A `MenuAction.WALK` menu entry's `param0`/`param1` are NOT the clicked tile's scene
  coordinates** - unlike object-click entries (tree, bookcase, compost bin: see
  `HerbiboarPlugin`/`KourendLibraryPlugin`/`CompostTracker` in RuneLite core, all of which legitimately
  decode `getParam0()`/`getParam1()` via `WorldPoint.fromScene()` for their own entry type), doing
  the same for a WALK entry resolves to a wildly wrong tile - seen as several hundred tiles of
  error. The Location Mapper's shift-right-click "Set Corner A/B" hit exactly this. The correct,
  proven pattern for "what tile did this shift-right-click land on" is
  `client.getTopLevelWorldView().getSelectedSceneTile()` (ie. "last right-clicked tile") - this is
  what `GroundMarkerPlugin`'s own shift-right-click "Mark tile" (RuneLite core) uses, and what
  `PrepToolsPlugin#onMenuOpened()` was fixed to use instead.
- **Teleblock's spot-animation ID isn't in the old-style `net.runelite.api.GraphicID`** (unlike
  the freeze spells, which are). It's `net.runelite.api.gameval.SpotanimID.TELE_BLOCK_IMPACT`
  (345) instead - the newer gameval-style constants class. Player death (for detecting an
  on-screen friends chat member dying) is similarly only in the gameval class:
  `net.runelite.api.gameval.AnimationID.HUMAN_DEATH` / `HUMAN_DEATH_BACKWARDS` (836/837).
- **"PID" is two unrelated OSRS mechanics — don't conflate them.** (1) The classic PvP "PID":
  combat action-processing priority, randomizes every ~100-150 ticks (60-90s; 40-60 ticks/24-36s
  on PvP worlds), not exposed anywhere in the RuneLite API, not controllable. (2) A separate stable
  per-login player index (`Player.getId()`) that a Jagex developer (Mod Ash, Feb 2023, cited on the
  OSRS Wiki's "Player identification number" page) confirmed the client uses for **render
  priority** when players stack on one tile (who's drawn on top vs hidden) — this is what
  `PrepToolsConfig#showPlayerIndex()`/`FightInfoOverlay#drawPlayerIndexRow()` surface. Neither
  mechanic is confirmed anywhere (wiki, PK guides, community) to control right-click menu order or
  which player a left-click on a pile defaults to — every "PID guide" online is actually about
  mechanic (1), which is irrelevant to click targeting. Don't imply otherwise in UI copy.
- **Rebuilding `:client:shadowJar` while the dev client is still running from an earlier build of
  that same jar can corrupt it out from under the live JVM.** `shadowJar` overwrites the exact same
  output file path in place; the running process still has it open and is lazily loading classes
  from it as needed. If a rebuild lands mid-run, the next not-yet-loaded class can come back
  corrupted - seen as `WARN injected-client - error checking hash` /
  `java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)`, then a
  `NoClassDefFoundError`/`ClassNotFoundException` for whatever class was needed, then the client
  hangs (high sustained CPU, no further log output - a thread stuck after failing to recover).
  Rebuilding itself is still fine to do freely without asking (per the rule below) - the risk is
  specifically leaving the *old* process running across a rebuild and then continuing to interact
  with it. If the client freezes after a rebuild, this is the first thing to check in
  `~/.runelite/logs/client.log`.
- **A plain `ArrayList` read by an `Overlay#render()` (game render thread) and mutated by a sidebar
  button click (Swing EDT) will eventually throw `ConcurrentModificationException`.** Hit this with
  `wildernessLocations`: `WildernessLocationDebugOverlay.render()` iterates it every frame while
  it's on screen, while `saveLocationFromPanel()`/`removeWildernessLocation()`/
  `updateWildernessLocation()` add/remove from the EDT. `OverlayRenderer.safeRender()` catches the
  exception so it doesn't crash the client, but that frame's overlay silently draws nothing - easy
  to misread as "the button/menu action didn't do anything" rather than "it worked, the overlay just
  skipped a frame." `CopyOnWriteArrayList` is the fix (small, rarely-mutated, read-heavy-from-
  another-thread list - the standard case for it); a single simple reference field (`WorldPoint`,
  not a collection) read cross-thread without synchronization is fine as-is elsewhere in this plugin
  (e.g. `lastKnownWorldPoint`) - reference assignment is atomic, this is specifically a
  collection-structural-mutation problem.
- **git has no configured `user.email`/`user.name` in this environment**, and Claude must not set
  git config itself. Ask the user to run it themselves via `! git config user.email ... && git
  config user.name ...` before attempting a commit.
- **`git commit` commits the whole index, not just what you just `git add`ed.** If files were
  staged earlier in a long session and forgotten about, a later `git add <specific files> && git
  commit` can silently sweep in everything already staged. Run `git status --short` immediately
  before every commit in a multi-commit sequence to confirm exactly what's about to be committed.

## Maintaining this file

This file only stays useful if it stays accurate — a stale instruction (wrong file paths, outdated
workflow rules, a "lesson" that's since been fixed or changed) misleads a future session worse than
having nothing at all. When you learn something during a session that would have saved you time had
you known it going in, add it here. When something here turns out to be wrong, outdated, or no
longer relevant, fix or remove it — don't just append. Keep the whole file high-signal: a short list
of things that actually change how you'd approach this repo, not an exhaustive changelog or a
restatement of what the code already makes obvious by reading it.
