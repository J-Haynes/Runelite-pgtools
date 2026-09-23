# This repo

A fork of RuneLite tracking a future OSRS content revision (see recent commits like "Update
GameVals to 2026-09-16-rev240"). The active work in this repo is a single plugin:

## PvP Tools (`preptools`)

Location: `runelite-client/src/main/java/net/runelite/client/plugins/preptools/`

A team-PK / multi-clanning coordination tool — the kind of thing a small clan runs together while
fighting as a team in the wilderness. Inspired by [Exilent](https://exilent.org), a PK-focused OSRS
client with similar team-coordination features. Core pieces:

- **Team** — "your team" everywhere in this plugin (`PrepToolsPlugin#isOwnTeamMember()`) = you +
  friends chat members + **clan members** (`Player#isClanMember()` - teams don't always run an FC)
  + every configured caller, enabled or disabled. Used by pile finder, opponent prayer/weapon
  counts, freeze/TB "hide for own team", teammate freeze-spell blocking, and the Clan IDs tab.
- **Caller list** — primary/backup callers, reorderable/deletable from a sidebar panel, one-click
  primary switching. Each caller has an enable checkbox: disabled callers stay in the list (stored
  with a leading `!` in the `callers` config text) and on the team, but are never primary/backup/
  highlighted. Callers get their **Follow** menu option coloured and made left-click
  (`prioritiseCallerFollow()`), not Attack - a target/pile/snipe Attack on the same tile still wins.
  ✎ on a row opens an edit form (name + nickname) below the list - a separate panel, not an in-row
  form, because the row list is a `GridLayout` and one taller row would stretch every row
  (`PrepToolsPlugin#editCaller()` keeps list position, enabled state and the primary pin).
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
    target, how many of your own team (FC/clan members, callers, you) are simultaneously interacting
    with the same non-team player (`PrepToolsPlugin#updateTargetedPlayers()`); once that count meets a configurable
    threshold (`targetedPlayerThreshold()`, default low but user-settable), that player is
    highlighted even though nobody explicitly called them. FC-on-FC interactions (sparring) and
    interactions targeting a configured caller are excluded from the count so callers can never
    accidentally trip their own team's pile finder. Needs teammates on screen to count (a
    friends chat *or* clan - either works). Can also be prioritised to left-click Attack
    (`prioritiseAttackForPiles()`), subordinate to the target's own priority.
- **Freeze tracking** — detects Bind/Snare/Entangle/Ice-spell hits, shows icon + countdown, can
  hide/block freezes against teammates.
- **Teleblock tracking** — same idea as freeze tracking, but for the teleblock spell (icon +
  countdown; 5 min normally, 2.5 min if the target was praying Protect from Magic when it landed).
  Drawn one row below the freeze icon when a player has both (both overlays share an anchor).
  Both freeze and TB are cleared on death (`ActorDeath` + death animation).
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
- **Spammer** — hotkey (F10 default) that sends a saved message to **public chat**. Messages are
  added/selected/deleted from the sidebar "Spam" tab (select = what's sent; or tick Random for a
  random one each press, never the same twice in a row). Stored newline-separated (messages can
  contain commas) in hidden config `spamMessages`/`selectedSpamMessage`; max 80 chars. **5 second
  cooldown** between sends (anti-abuse, asked for explicitly; a blocked press prints a local
  console line) - Spammer only, Pile Spam and Location Spam are deliberately NOT rate-limited.
  Confirmed working in game.
- **Multi Lines** — ground lines around multi-combat areas (+ optional dragon-spear range). Two
  sources, both BSD-2 (notices kept in `MultiLinesOverlay.java`), both **bundled** rather than
  downloaded at login like Multi-Lines does:
  - Non-wilderness: [tsbreuer/Multi-Lines](https://github.com/tsbreuer/Multi-Lines) `geoJSON`
    branch, as `runelite-client/src/main/resources/net/runelite/client/plugins/preptools/multi_lines.json`.
    Its `"Wilderness": true` entries are **ignored** - they're old, unmaintained, and were visibly
    wrong in game (Multi-Lines itself skips them and defers to Wilderness Lines).
  - Wilderness: [Nightfirecat's Wilderness Lines](https://github.com/Nightfirecat/plugin-hub-plugins/tree/wilderness-lines)
    v1.5.0 - `WILDERNESS_MULTI_AREAS` copied verbatim into `MultiLinesOverlay`, surface only
    (intersected with its `WILDERNESS` bounds, exactly as upstream draws them), spear range 5.
    Also its level 20/30 lines (`TWENTY_LINES`/`THIRTY_LINES`, overworld + rev/slayer caves),
    both shown by default.
  Wilderness and non-wilderness shapes are kept as separate paths so the non-wilderness ones are
  simply skipped unless `showMultiLinesOutsideWilderness` is on (**default off**). Drawn out to the
  GPU plugin's draw distance via `WorldLines` - see the lesson below.
- **Member counts** — friends chat / clan member "near you" infoboxes, same as the vanilla Team
  plugin's counters (Team Capes config section).
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
  `showWildernessLocationOverlay` ("Show/edit wilderness locations") gates both that overlay (drawn
  via `WorldLines`, so boxes partly outside the loaded scene still draw) *and*
  a shift-right-click-the-ground "Set Corner A"/"Set Corner B" menu option
  (`PrepToolsPlugin#onMenuOpened()`), an alternative to the sidebar's own Set Corner buttons that
  doesn't require standing exactly on the tile - it captures the clicked tile, not your own position.
  **Overlapping boxes / priority:** each `WildernessLocation` has an integer `priority` (Gson leaves
  it 0 for locations saved before it existed). `PrepToolsPlugin#findContainingLocations()` orders
  every box containing a point: **highest priority, then smallest area (most specific), then list
  order** - the first one is the resolved location, the rest show in the Mapper as "Also in (lost on
  priority)". Before priorities, it was purely first-in-list, so a big box saved first swallowed
  every smaller box inside it. New boxes default to P0 (spinner next to the name field when
  saving; "Priority" field in the ✎ edit form); the saved list displays sorted by priority then
  name (display only - saved order is untouched, it's the last tie-break). The sidebar's own help
  text explains the same rule - keep the two in sync.
  **The location list ships with the plugin (two layers - `WildernessLocationStore`):**
  - *Shipped:* `runelite-client/src/main/resources/net/runelite/client/plugins/preptools/wilderness_locations.json`
    - the team's map, the same for every install (dev client and jpackage installers alike).
    Every entry has a stable `id`. It started as the user's own mapping (141 boxes on 2026-09-24),
    including the 53 OpenOSRS starter boxes they chose to keep (P-1).
  - *Personal:* saved in the user's RuneLite profile under `wildernessLocationsUserLayer` - only
    their own changes: `added` (ids `u-...`, shown green), `edits` to shipped entries keyed by id
    (orange, ↺ reverts), `hidden` shipped ids (✕ on a shipped row hides it; "Restore" un-hides).
    These survive shipped-map updates. `reconcile()` drops personal entries a new shipped map has
    made redundant (edit equal to the shipped value, addition now shipped).
  - **Updating the shipped map:** the mapper (the user) edits in game, clicks **Export** on the
    Location tab (clipboard + `~/.runelite/pvptools-exports/wilderness_locations-<time>.json`, which
    doubles as a backup), and that file replaces `wilderness_locations.json` - commit, rebuild,
    Package. The export keeps the file's header, including the OpenOSRS licence notice (required
    by its licence, and `WildernessLocationStoreTest` fails without it). The test also checks ids
    are unique and boxes valid, so a bad data file fails CI, not users' installs.
  - **Migration:** a profile from before this existed has one flat list under the old key
    `wildernessLocationsJson`. It's converted once: entries identical to a shipped box, to one of
    the five old starter boxes, or to an original OpenOSRS import are stock data and dropped;
    anything else becomes a personal addition. The old key is left in place as a backup.
  - **Can't silently lose data:** an unreadable personal layer is copied to
    `wildernessLocationsUserLayerUnreadable` before anything can save over it. (The old code
    reset to defaults and saved over the list on a parse failure.) The user also keeps manual
    backups in `~/runelite-pvptools-backups/`.
  - OpenOSRS source details: `OpenOsrsWildernessLocations.java` (licence notice verbatim at the top)
    is now only used by the migration. Upstream passed each box's *north* y as a south-west-anchored
    `WorldArea` origin, so its own lookups sat a box-height too far north - the corners as written
    are used. Pre-dates Ferox Enclave, so entries near it may be stale; the user edits/deletes them
    by hand. A source not used: the game cache's world-map labels (`cache` module) - current, but
    points only.
- **Team cape overlay** — like vanilla RuneLite's "Teams" plugin, but with a custom name per team
  number (`PrepToolsConfig#teamCapeNames`).
- **Right-click menu integration** — prioritises/recolours Attack and freeze-spell casts (target,
  pile, and now snipe-list hits too, with a configurable tie-break order —
  `PrepToolsConfig#attackPriorityOrder()`, default Target > Pile > Snipe — when more than one
  applies to the same menu), blocks casting a freeze spell on a teammate, and (opt-in) shift-click
  "Walk here" on attackable players — vanilla RuneLite's Menu Entry Swapper has this for
  NPCs/objects/ground items but not players.
- **Clan IDs panel** — sidebar tab surfacing your own and your friends chat/clan members' player ids
  (`Player#getId()`), sorted, with the current min/max flagged as click-priority risks and
  configured callers highlighted as "Name (Nickname)" — see `MENU_ORDER_PID_INVESTIGATION.md` at the repo root for the
  underlying mechanic this is built on (right-click Attack-menu ordering on a stack) and its current
  confidence level/open questions. Clan-only by design: OSRS's friendly-fire protection means an
  enemy's own menu on your team's pile is built exclusively from your team's ids, so enemy ids don't
  factor into who on your side is exposed.

Config file (`PrepToolsConfig.java`) is the fastest way to see the full feature surface — it's kept
well-commented and organized by section, sections ordered/all collapsed-by-default to match the
sidebar's own tab order. The sidebar (`PrepToolsPanel.java`) is a `MaterialTabGroup` in a fixed
3-column grid (Callers/Snipe/Spam, Clan IDs/FC/Capes, Location - 3/3/1; 4 columns was tried and
cut every label off at sidebar width), icon+label
centred in each cell. Every tab's icon is a **real in-game image**, not a bundled file (the plugin's
only resource is the multi-lines data JSON - no image assets) - a UI sprite via
`SpriteManager#getSpriteAsync()` (`PrepToolsPanel#loadTabIcon()`) for six of them, but Capes uses an actual team cape **item** icon via `ItemManager`
(`#loadTabItemIcon()`, `TeamCapeOverlay.teamCapeItemId(30)`) since the generic equipment-slot sprite
tried first rendered as a blank placeholder, not a real cape. Every section follows the same
three-tier visual style, spaced apart with explicit `Box.createVerticalStrut(TIER_GAP)` calls
(border-insets alone read as "no spacing") - `createSectionTitle()` (bold + brand-orange underline),
`createDescriptionArea()` (muted text + left accent stripe), `styleInputRow()` (padding only, no
background/border box - tried a boxed-card look, asked to remove it while keeping the spacing).

## Open questions / unverified

- **Multi Lines' wilderness areas and extended-scene heights** - see the Multi Lines entry and the
  `WorldLines` lesson; neither has been checked in game yet.

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
  Release). Only runs on a repo you can push to and that has Actions enabled - i.e. `origin`
  (the user's fork), not `upstream` (`runelite/runelite`).
  `jpackage.appName`/`jpackage.vendor`/`jpackage.type` are exposed as Gradle properties
  (`-Pjpackage.appName=...`) if the defaults ("RuneLite PvP Tools") need overriding per-platform.
- **Installers are unsigned** (SmartScreen warning on Windows, Gatekeeper on macOS), and
  `macos-latest` is Apple Silicon, so the `.dmg` won't run on Intel Macs. The Windows job's
  "Ensure WiX" step originally used `Get-ChildItem ... -Directory` (lists folders *inside* `bin`),
  so it never added WiX to PATH - fixed to `Get-Item`, but only exercised when the runner image
  lacks WiX, so it's unconfirmed until a Windows run passes.
- **Bundled runtime is Java 11, jlinked separately** (`jpackageRuntime` task, `--runtime-image`),
  not jpackage's default (which would jlink its own JDK 17). Official RuneLite ships Temurin 11 and
  this client is built/tested on 11; Java 17's strong encapsulation breaks reflective access the
  client uses (world hopper `Ping`'s `FileDescriptor.fd` only catches `NoSuchField`/`IllegalAccess`,
  not `InaccessibleObjectException`). jpackage 17's launcher runs a Java 11 image fine (verified
  locally, including launching the packaged client). jlink needs a JDK 11 **with `jmods/`** -
  Temurin has it (CI's `setup-java`); on Ubuntu it's the separate `openjdk-11-jmods` package. JVM
  flags copied from official RuneLite's launcher: `-Xmx768m -Xss2m -XX:CompileThreshold=1500
  -XX:+DisableAttachMechanism`.
- **The .deb failed to install on another machine - two jpackage defaults, both fixed via
  `--resource-dir` overrides written by `writeLinuxResources()`** (file names must match what
  `jpackage --verbose` asks for: `control`, `<app name>.desktop`):
  (1) the `.desktop` entry had `Categories=Unknown` + an empty `MimeType=` - fails
  `desktop-file-validate`, and `postinst` runs `xdg-desktop-menu install` under `set -e`, so a
  strict xdg-utils aborts the install (this machine's 1.2.1 happens to tolerate it). Now mirrors
  official RuneLite's entry (`Categories=Game;`). (2) jpackage writes `Depends:` from the build
  machine's package names, and the CI runner (Ubuntu 24.04) names ALSA `libasound2t64`, which
  doesn't exist on Ubuntu 22.04/Debian 12 - now `libasound2t64 | libasound2`. That Depends list is
  **hardcoded** - re-check it against a default build's `dpkg-deb -I` if the runtime changes. The
  Package workflow's Linux job now runs `desktop-file-validate` + `apt-get install --simulate` on
  the built `.deb`, so both regressions fail CI instead of reaching users.
- **Icons:** `--icon` needs `.png`/`.ico`/`.icns` per OS. `runelite-client/jpackage/runelite.ico`
  and `.icns` are generated from `runelite_128.png` (each just wraps that one PNG - both formats
  allow embedded PNG), so a single 128px image, not a full multi-size set.
- **Other per-OS defaults overridden:** Windows Start Menu folder defaulted to "Unknown"
  (`--win-menu-group`); a fixed `--win-upgrade-uuid` (never change it once installers are
  published) so new versions upgrade in place; macOS bundle id/category set explicitly
  (`--mac-package-identifier`, `--mac-app-category games` - jpackage adds the
  `public.app-category.` prefix). Windows/macOS builds can't be tested on this machine - only a
  Package workflow run confirms them.
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
  `"Level: N"` (null when not in the wilderness at all). **Match it unanchored with `find()`** -
  `CombatLevelPlugin` (RuneLite core, "attack level range" option on by default) *rewrites that
  same widget's text* to `"Level: 15<br>81-111"`, so its own anchored `^Level: (\d+)$` pattern,
  which this plugin originally copied, silently never matched and Location Spam lost its level.
  **Fix CONFIRMED in game.** A coordinate formula (`(y - 3520) / 8 + 1`, `9920` underground) is
  kept only as a fallback, gated on the `IN_WILDERNESS` varbit.
- **Draw ground lines with `WorldLines`, not `LocalPoint.fromWorld()` + `getCanvasTileAreaPoly`.**
  `LocalPoint.fromWorld()` returns null outside the 104x104 loaded scene, so a shape with any corner
  outside it silently vanished - and "outside" depends on where the scene last loaded, which is why
  Location Mapper boxes "sometimes didn't show when you leave and re-enter the same area". Also, the
  GPU plugin draws terrain over the 184x184 *extended* scene (40 tiles further each way), so lines
  limited to the loaded scene (or the 20-tile clip copied from NpcAggroAreaPlugin, which Multi Lines
  first had) stop well short of what's on screen. `WorldLines` clips to the extended scene, converts
  world->local by plain arithmetic, projects with `Perspective.localToCanvas(client, x, y, z)`
  (accepts the extended range), and draws out to the GPU plugin's `drawDistance` (25 without GPU).
  Limitation: the API has no ground height outside the loaded scene (`getTileHeight()` returns 0),
  so points there borrow the nearest loaded tile's height - can float/sink a little on steep hills.
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
- **`ScriptID.CHAT_SEND`'s "modes" integer, all CONFIRMED in game:** public = 0 (Spammer/Pile
  Spam), clan chat = 1, friends chat = 2 (`PrepToolsPlugin`'s `CHAT_MODE_*` constants). Neither
  RuneLite's source/docs nor a web search had the literal values - they were guessed (FC/clan
  initially swapped) and fixed by testing.
- **Never expose a list the sidebar edits as a visible text `@ConfigItem` - mark it
  `hidden = true`.** RuneLite's settings panel (`ConfigPanel#createTextField`) saves a text field on
  *focus-lost* with whatever text it loaded when opened, and never refreshes on outside changes. So
  settings open -> edit list in sidebar -> back to settings -> focus leaves the field = stale text
  written straight over the sidebar's edits ("switching between the sidebar and settings can destroy
  the caller list"). `callers`, `snipeList`, `teamCapeNames`, `spamMessages`, `selectedSpamMessage`
  are all hidden for this reason; the sidebar is their only editor.
- **Never put `/` in a `@PluginDescriptor` `name`.** `PluginManagerTest` writes one `<name>.dot`
  file per plugin, so a slash becomes a missing subdirectory and CI (`ci/build.sh` -> `:buildAll`)
  fails - "Menu Order/PID Debug" kept CI red for several commits unnoticed. Run
  `./gradlew :client:test` before pushing; the enabled-state config key is the class name, so
  renaming a plugin's display name is safe.
- **No bare `<`, `>` or `&` in `/** */` doc comments** - write "A to B" not "A -> B", and wrap
  literal markup in `{@code ...}`. CI's `:client:javadoc` treats them as errors ("bad use of '>'"),
  and Lombok copies a field's doc comment onto its generated getter, so even a private field's doc
  breaks it once it has `@Getter`. **The local JDK only warns**, so a plain local build passes
  while CI fails. To reproduce CI locally after `./gradlew :client:javadoc`:
  `sed '/^-d /d' runelite-client/build/tmp/javadoc/javadoc.options > /tmp/jd.options && javadoc @/tmp/jd.options -Xdoclint:all,-missing -d /tmp/jd 2>&1 | grep error`.
  (`//` comments are unaffected.)
- **Git remotes/identity:** `origin` is the user's own fork (`git@github.com:J-Haynes/Runelite-pgtools.git`,
  push here); `upstream` is `runelite/runelite` (fetch only - never push). The user has set their
  own git `user.name`/`user.email`; Claude still must not change git config itself. The `gh` CLI
  is not installed.
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
