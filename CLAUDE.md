# This repo

A fork of RuneLite tracking a future OSRS content revision (see recent commits like "Update
GameVals to 2026-09-16-rev240"). The active work in this repo is a single plugin:

## PvP Tools (`preptools`)

Location: `runelite-client/src/main/java/net/runelite/client/plugins/preptools/`

A team-PK / multi-clanning coordination tool — the kind of thing a small clan runs together while
fighting as a team in the wilderness. **Used for FREE-TO-PLAY multi warring, in the wilderness and
in Clan Wars** (user, 2026-10-02) - so no protection prayers, teleblock, ice spells, Entangle or
special attacks in actual use; food is anchovy pizza. Weigh new features against F2P mechanics. Inspired by [Exilent](https://exilent.org), a PK-focused OSRS
client with similar team-coordination features. Core pieces:

- **Team** — "your team" everywhere in this plugin (`TeamTracker#isOwnTeam()`) = you +
  friends chat members + **clan members** (`Player#isClanMember()` - teams don't always run an FC)
  + every configured caller, enabled or disabled. Used by pile finder, opponent prayer/weapon
  counts, freeze/TB "hide for own team", teammate spell blocking, and the Clan Info tab.
- **Caller list** — primary/backup callers, reorderable/deletable from a sidebar panel, one-click
  primary switching. Each caller has an enable checkbox: disabled callers stay in the list (stored
  with a leading `!` in the `callers` config text) and on the team, but are never primary/backup/
  highlighted. Callers get their **Follow** menu option coloured and made left-click
  (`prioritiseCallerFollow()`), not Attack - a target/pile/snipe Attack on the same tile still wins.
  ✎ on a row opens an edit form (name + nickname) below the list - a separate panel, not an in-row
  form, because the row list is a `GridLayout` and one taller row would stretch every row
  (`ConfigListEditor#editCaller()` keeps list position, enabled state and the primary pin).
  **Fall-in finder** (`prioritiseFallInFollow`, default on, `fallInThreshold` 20, asked for
  2026-10-11): a teammate that many teammates are interacting with (= following - teammates don't
  attack each other) gets left-click Follow (`HighlightTracker#getFallInLeaders()`), after the
  primary caller's Follow, before a backup's; Attack priorities still win. The leader is also
  **outlined** (`highlightFallInLeader`, default on, `fallInLeaderColor`; asked for 2026-10-18),
  found even with the left-click Follow off. **Enemy fall-in finder** (`enemyFallInFinder`, default
  off, own `enemyFallInThreshold` 10 + colour): an enemy that many enemies are interacting with -
  enemy-on-enemy counts as following, except when both wear *different* team capes (two other clans
  fighting). `HighlightTracker#getEnemyFallInLeaders()`. Optional left-click Follow on it
  (`prioritiseEnemyFallInFollow`, default off, needs the finder on; asked for 2026-10-25) - last
  of the Follows (after a backup caller's), Attack priorities still win.
  Each row is two lines (asked for - one line was "too busy"): radio + in-game name on top,
  nickname + on-screen status underneath (`PrepToolsPanel#addRow()`).
- **Highlighting** — three independent detection categories, each with its own config section and
  colour, but sharing one rendering style (shape/outline width/font/dot size — the config's
  "Overlay" section, `PrepToolsConfig#highlightShape()` etc.) so every highlighted player looks
  visually consistent no matter which category put them there. Priority when a player qualifies
  for more than one at once, lowest to highest: backup caller < fall-in leader < enemy fall-in
  leader < attacker < snipe list < pile finder < primary caller < target (`HighlightTracker#rebuildStyles()`, cached once per tick;
  `#getStyles()` adds the per-frame new-target flash on top) - e.g. a target overrides
  its own pile-finder highlight.
  - **Caller** — the primary caller and any backup callers from the caller list get an outline
    (default green for primary, a separate colour for backups), driven entirely by the configured
    caller list matching on-screen player names (`HighlightTracker#updateCallersAndTargets()`).
  - **Target** — whoever the *primary* caller is currently interacting with (attacking, following,
    etc. — `Player#getInteracting()`), i.e. one player at a time, only ever derived from the primary
    caller, never a backup. **Never a teammate** - a caller following whoever leads the fall-in used
    to make that teammate the "target" (highlighted + left-click Attack). A frozen target (and a
    frozen pile) uses its own colour (`frozenTargetColor()`/`frozenPileColor()`; not while immune),
    which is why `FreezeTracker#onGameTick()` runs before `HighlightTracker`'s. Wins over a pile-finder highlight on the same player
    (`PrepToolsConfig#targetColor()`'s doc comment: "Always wins over the pile finder colour").
    Optionally flashes a configurable colour for a moment when a *new* target first appears
    (`showTargetFlash()`/`targetFlashColor()`/`targetFlashDurationMillis()`), and can be prioritised
    to the default left-click Attack action (`prioritiseTargetAttack()`).
  - **Attackers** (own config section, default off) — enemy (non-team) players whose
    `getInteracting()` is *you* (`HighlightTracker#updateAttackers()`). Insertion-ordered, so the
    `maxAttackers` cap (0 = no limit) keeps whoever engaged you first. Optional left-click
    Attack (`prioritiseAttackerAttack()`, default off), in the Menu section's priority order.
  - **Pile Finder** — a different mechanic: detects an *uncalled* pile by counting, per candidate
    target, how many of your own team (FC/clan members, callers, you) are simultaneously interacting
    with the same non-team player (`HighlightTracker#updatePiles()`); once that count meets a configurable
    threshold (`targetedPlayerThreshold()`, default low but user-settable), that player is
    highlighted even though nobody explicitly called them. Team-on-team interactions (sparring) and
    interactions targeting a configured caller are excluded from the count so callers can never
    accidentally trip their own team's pile finder. Needs teammates on screen to count (a
    friends chat *or* clan - either works). Can also be prioritised to left-click Attack
    (`prioritiseAttackForPiles()`), subordinate to the target's own priority.
- **Freeze tracking** — detects Bind/Snare/Entangle/Ice-spell hits, shows icon + countdown, can
  hide freezes on teammates. Detection uses `Player#hasSpotAnim()`, not the deprecated
  `getGraphic()`, which returns only one of an actor's spot anims (a barrage landing with a TB was
  missed). **Every** player's freeze is tracked whatever the settings - `FreezeTracker#isFrozen()`
  feeds the infobox, the frozen border and the frozen target/pile colours - and the overlays ask
  `isFreezeShown()` for what to draw. Timing: the shown countdown is spell duration **+1** tick
  (display only); immunity ends spell duration **+5** after the hit (`FreezeState#immunityEndTick`)
  - the first tick a refreeze lands, so it's accepted even though the old entry isn't dropped
  until that tick's `onGameTick`. Refreezes were dropped before both were fixed (2026-09-25).
  **Protect from Magic does NOT shorten any freeze** - never did for Ancients, and the
  [PvP Balancing update](https://oldschool.runescape.wiki/w/Update:PvP_Balancing,_LMS,_and_Elf_Graphics)
  removed it for Bind/Snare/Entangle. (A session wrongly added PfM halving from outdated
  knowledge; check the wiki's update pages, not memory, for current mechanics.)
- **Teleblock tracking** — same idea as freeze tracking, but for the teleblock spell (icon +
  countdown; 5 min normally, 2.5 min if the target was praying Protect from Magic when it landed).
  Drawn one row below the freeze icon when a player has both (both overlays share an anchor).
  Both freeze and TB are cleared on death (`ActorDeath` + death animation) and on
  `PlayerDespawned` (logout, or leaving view - `FreezeTracker#forget()`); without the latter a
  logged-out player's icons stayed at their last spot until the timer ran out.
- **Snipe list** — a flat, always-on highlight list (default red), independent of caller/target
  status. Shift-right-click a player to add them, same idiom as "Add caller" (default green).
- **Infobox panel** — bottom-right HUD: your stats (HP/prayer/strength/ranged — prayer and
  strength colour green while boosted at/above a threshold), freeze/teleblock status, target's
  prayer, opponent prayers/weapons (melee/ranged/magic — melee/ranged/magic weapon IDs live in
  `WeaponStyle`; the worn item ID is exact, so **every wieldable variant** - charged/uncharged,
  cosmetic, LMS - must be listed: find them in `item_variations.json`. Bryophyta's staff was
  missing entirely), who's attacking
  you, current wilderness location, your player index (informational — see the PID note below).
- **Friends chat log** — sidebar panel (the "FC" tab, alongside the "Missing" list - its Copy
  starts with a `Missing names:` line), newest-first,
  logs members joining/leaving/dying (other members' deaths from the death animation, your own as
  "You died" from `ActorDeath`); copyable via a header Copy button.
- **Attack Alert** (config section, everything **default off**) — two triggers: *attacked* = an
  enemy (non-team) player starts interacting with you and none were the tick before
  (`AttackAlert#onGameTick()`, shared cooldown), and *frozen* = a freeze spell graphic on
  you (`onGraphicChanged`, independent of Freeze Timers being on). Attacked can play a sound - a
  dropdown of 7 in-game sounds (`AttackAlertSound`, `SoundEffectID` constants, picked by us not
  the user; `client.playSoundEffect(id, volume)`, volume 0-127, plays even with game sounds muted)
  or a custom `.wav` via core's `AudioPlayer` off the client thread. Either trigger can flash a
  coloured border round the whole screen (`ScreenBorderFlashOverlay`, `ALWAYS_ON_TOP`, blinking,
  per-trigger colour). "Keep border while frozen" (default off) leaves it on, solid, until your
  freeze ends - your own freeze is always tracked (`FreezeTracker#getLocalFreeze()`), whatever the
  Freeze Timers settings; the infobox's freeze status row reads the same state. Replaced a raw
  sound-ID field (asked for a dropdown instead). The infobox's "Attacking you" row skips
  teammates too (it used to count anyone following you). (Core's `Notification` config type + `Notifier` was considered: free UI
  incl. a whole-screen tint, but no in-game sounds and silent while focused by default.)
- **Scout (Discord)** — hotkey (F9 default) posts to a Discord webhook (`scoutWebhookUrl`,
  `secret = true`), posted under `scoutPosterName` ("Post as"), or your in-game name if that's
  blank (`ScoutReporter#posterName()`, cut to Discord's 80 chars), with **pictures** (asked for 2026-10-11): a map (`scoutMap`, toggle: DRAWN default /
  MINIMAP / NONE) with the **cape counter drawn on it** (`scoutCapes`, `ScoutMap#withCapes()`: item
  icon with count + `10x Team-50 (EoP)`, the overlay's minimum count; top-left on the drawn map,
  where no names go - beside the smaller minimap). **Enemy levels on picture** (`scoutEnemyLevels`,
  default off; asked for 2026-10-25): the Capes tab's Enemy Levels charts, worked out at the
  keypress, drawn to the right of the picture (`ScoutMap#withEnemyLevels()`), wrapping into another
  column rather than growing taller than the map (min 512px). A separate screenshot of the Team Capes overlay
  was built first and replaced the same day (asked for: "draw it on the map"). One picture. *Drawn* (`ScoutMap#drawn()`): north-up
  art from mejrs' map squares (`ScoutMap.TILE_URL`, zoom 2 = 4px a tile, 64 tiles a square, cached
  in `~/.runelite/pvptools-maptiles/`), `scoutMapRadius` (40) tiles each way, a dot per on-screen
  player (you white/team green/enemy red, drawn **over** the names - asked for) and the Location
  Mapper names (most specific first, overlapping names skipped). *Minimap*: cut from the next drawn frame
  (`DrawManager#requestNextFrameListener`) at the `*.MINIMAP` widget, zoomed out to 2px a tile for
  that one frame (restored in the listener), turned north-up from where `Perspective.localToMinimap`
  puts you and a point north of you, same names. No frame within 1.5s (minimised) = drawn map only,
  sent as multipart `payload_json` + `files[i]`. Multi-line,
  format asked for explicitly (`ScoutReport#format()`, tested in `ScoutReportTest`):
  `W308, Ferox Enclave, lvl 15 wild.` then one `10x Team-50 (EoP),` line per cape, most first,
  last line without the comma (`No team capes in sight` if none qualify).
  Counts **every** on-screen player by team cape number, own team included - the same numbers as
  the Team Capes overlay (it first skipped FC/clan members, which silently dropped named capes the
  overlay showed), cut to Scout's own combat level range if `scoutLevelFilter` is on. Capes tab name added if set,
  only capes with at least `scoutMinCapeCount` (default 2) wearers; capeless players are left out
  on purpose ("7 other players" was called useless); location parts are Location Spam's. Only sends to a real Discord webhook URL
  (`isDiscordWebhook()`), `allowed_mentions` empty so a cape name can't ping, 5s cooldown (Discord
  allows ~30/min per channel); result echoed as a console chat line.
- **Chat hotkeys only pre-fill the chatbox - you press Enter.** Pile Spam, Spammer and Location
  Spam type their message into the chat input with a channel prefix the game's own chat script
  strips (`/p ` public, `/f ` friends chat, `/c ` clan - `ChatHotkeys#prefill()`, core's
  `ChatKeyboardListener` pattern). Replaces whatever was typed; does nothing (console line, no
  Spammer cooldown used) while another chat prompt is open (`MESLAYERMODE`, e.g. a PM). They used to send directly; see the "without permission" lesson.
- **Pile Spam** (was "Chat Spam") — hotkey (F12 default) that types the name of whoever you're
  following/piling for **public/game chat**, always — no channel option (removed
  deliberately: this is for whoever's actually looking at your screen in the moment, not a
  coordination channel). Config keys kept as `chatSpamHotkey`/`chatSpamPrefix` (only the section/
  method names changed) so an already-configured hotkey isn't lost by the rename.
- **Spammer** — hotkey (F10 default) that types a saved message for **public chat**. Messages are
  added/selected/deleted from the sidebar "Spam" tab (select = what's sent; or tick Random for a
  random one each press, never the same twice in a row). Stored newline-separated (messages can
  contain commas) in hidden config `spamMessages`/`selectedSpamMessage`; max 80 chars. **5 second
  cooldown** between sends (anti-abuse, asked for explicitly; a blocked press prints a local
  console line) - Spammer only, Pile Spam and Location Spam are deliberately NOT rate-limited.
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
- **Location Spam** — hotkey (F11 default) that types your current wilderness location in a
  **fixed format, no configurable prefix**: `Location: W318, Ferox Enclave, lvl 15 wild` (world,
  then name, then the wilderness level if known - the "lvl N wild" part is omitted entirely, comma
  and all, if you're not in the wilderness). World number was briefly dropped, then asked back -
  it's back for good now, no need to ask again. Goes to a **configurable channel** (Friends Chat
  default, Clan Chat also selectable via `locationSpamChannel`) — unlike Pile Spam, this one's
  meant for a specific coordination channel.
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
  it 0 for locations saved before it existed). `LocationTracker#findContaining()` orders
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
  - **Updating the shipped map (confirmed with the user as the intended workflow):**
    1. The user maps in game - every add/edit/hide is saved as a *user setting* in their own
       profile (personal layer), NOT in the plugin. Nobody else sees it yet.
    2. They click **Export** on the Location tab: the *whole merged list* (shipped + their
       changes) goes to the clipboard and to `~/.runelite/pvptools-exports/wilderness_locations-<time>.json`
       (every export doubles as a backup).
    3. That file becomes the new bundled map:
       `cp ~/.runelite/pvptools-exports/wilderness_locations-<time>.json runelite-client/src/main/resources/net/runelite/client/plugins/preptools/wilderness_locations.json`
       then `./gradlew :client:test --tests 'net.runelite.client.plugins.preptools.*'`, commit, push,
       and run the Package workflow so installers carry it.
    4. On the user's next client start, `reconcile()` sees their personal changes are now part of
       the shipped map and drops them - the list shows all-white (shipped) again. Nothing is lost.
    The export keeps the file's header, including the OpenOSRS licence notice (required by its
    licence - `WildernessLocationStoreTest` fails without it), and the test checks ids are unique
    and boxes valid, so a bad data file fails CI, not users' installs. Always `git diff --stat`
    the data file before committing - a sudden big drop in entries means a wrong/old export.
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
  number (`PrepToolsConfig#teamCapeNames`). Optional combat level range (`capeLevelFilter` +
  `capeMinCombatLevel`/`capeMaxCombatLevel`, inclusive, default off; asked for 2026-10-18). Scout
  has its **own separate** range (`scoutLevelFilter`/`scoutMinCombatLevel`/`scoutMaxCombatLevel` -
  asked for separately, don't merge them); both count through `TeamCapeOverlay#countCapes()`. Every cell reports the tallest cell's height
  (`EvenHeightCell`): core's wrapping `PanelComponent` moves a new row down by the *last* cell's
  height, so a named cape (icon + text) in row 2 after a plain icon ended row 1 hung out of the box.
  **Enemy Levels** (Capes sidebar tab, below the cape names; asked for 2026-10-25 - first built
  as an overlay, then asked to be a sidebar feature instead, overlay removed): a chart per group -
  all enemies (`!isOwnTeam`), one per cape with `minimumTeamCapeCount` wearers (icon, name,
  count, low-high + median), then Others - with a row per level range: range, bar (scaled to that
  chart's fullest range) and count. Ranges are user-set in the tab (hidden `enemyLevelRanges`,
  e.g. `3-49, 50-59`; blank = automatic, a range every `enemyLevelBucketSize` levels, default 3,
  on multiples of it, lowest to highest enemy). Built once a tick (`EnemyLevels`, pure), pushed
  with the tick snapshot, rebuilt only when it changes; ignores the cape level filter. Both it and
  Scout draw from `EnemyLevels#charts()` (`Chart`), so they always show the same numbers.
- **Right-click menu integration** — prioritises/recolours Attack and freeze-spell casts (target,
  pile, and now snipe-list hits too, with a configurable tie-break order —
  four "1st..4th Attack priority" dropdowns, `PrepToolsConfig#attackPriority1()`.., default
  Target > Pile > Snipe > Attacker — when more than one applies to the same menu; a category left
  out of the four still goes last. Replaced the single `attackPriorityOrder` enum of orderings
  when Attackers made it 24; `PrepToolsPlugin#migrateAttackPriorityOrder()` carries an old
  choice over once), blocks casting **any** spell on a teammate (`preventFreezingTeammates` - key kept from when it
  was freezes only, now "Block spells on teammates" in the Menu section, default on; the Lunar
  teammate spells like Vengeance Other stay allowed; item-on-player "Use" isn't touched), blocks
  Bind/Snare/Entangle on a player who's frozen (`blockBindsOnHeldPlayers`, default on - the game
  refuses them, "already held by a magical force"; **ice spells are never blocked**, they still hit;
  `FreezeTracker#isHeld()` = real spell duration, not the shown +1) and, Experimental, on an immune
  one (`blockBindsOnImmunePlayers`, "currently immune to that spell"), removes Attack on
  teammates (`hideAttackOnTeammates`, default on - the game can offer Attack on FC members in
  the wilderness, and a fall-in stacks the team on one member; a teammate's Attack, if kept, is
  never coloured or promoted), and (opt-in) shift-click
  "Walk here" on attackable players — vanilla RuneLite's Menu Entry Swapper has this for
  NPCs/objects/ground items but not players.
- **Clan Info tab** (was "Clan IDs") — first a **Weapons** breakdown: three columns (melee/ranged/
  magic, with totals) of on-screen teammates (`isOwnTeamMember()`, you included) by wielded weapon,
  using the infobox's `WeaponStyle` lists (`PrepToolsPlugin#computeClanWeapons()`; teammates with
  an unlisted weapon are just counted). Then **Player IDs**: surfacing your own and your friends chat/clan members' player ids
  (`Player#getId()`), sorted, with the current min/max flagged as click-priority risks and
  configured callers highlighted, every row with a nickname shown as "Name (Nickname)" (caller-list
  nickname, else the Names tab's / Friend Notes'), and you in a third colour (blue, wins over
  caller colour). Both sections have a Copy button. See `MENU_ORDER_PID_INVESTIGATION.md` at the repo root for the
  underlying mechanic this is built on (right-click Attack-menu ordering on a stack) and its current
  confidence level/open questions. Clan-only by design: OSRS's friendly-fire protection means an
  enemy's own menu on your team's pile is built exclusively from your team's ids, so enemy ids don't
  factor into who on your side is exposed.

- **Hiscore lookups** (asked for 2026-10-25) - **manual only**: a "Look up" button in the Clan Info
  tab (Team Stats, with a source dropdown: teammates on screen incl. you, or the whole **friends
  chat** / **clan chat** member list, on screen or not - off-screen members' combat comes from
  their hiscore levels, `HiscoreLookups#combatLevel()`) and in the Capes tab (Enemy Stats, grouped
  by cape exactly like Enemy Levels). `HiscoreLookups` (bound singleton) snapshots who's on
  screen, then calls core's `HiscoreClient` **one at a time, 1.5-3s apart** (random), at most
  `hiscoreLookupLimit` (default and max 100, own config section) per press, biggest cape first.
  Results (attack/strength/defence/hitpoints/ranged/magic/prayer, 0 = unranked = xp -1, same rule
  as the Hiscore panel) cached 6h in `~/.runelite/pvptools-hiscores.json`, so a re-press only looks
  up new names. Stops on 2 failures in a row (or a 429), Cancel button, CSV Copy. Each group shows
  the exact Defence split 1-5 / 6-39 / 40+ / unranked. **Never make it automatic** (see the gear
  Defence backlog item for why).
- **Experimental** (config section of that name, position 19, everything **default off**) - built
  2026-10-02, not yet proven in a fight. Once one is confirmed, give it its own section (keep keys).
  - *Low food detector* (`FoodTracker`, `FoodOverlay`) - **for Clan Wars, not the wilderness.**
    Counting eat animations (829) does NOT work: potions play it too, and several pizza bites go
    down in one. Food = healing the bar can't explain: (hp after - hp before) + HP damage hitsplatted
    in between (incl. poison/venom/bleed/burn), 9 hp a bite (wiki). **Version 1 undercounted by
    more than half in the user's test** (25 pizzas eaten under constant attack, LOW FOOD only near the
    end of a second inventory) - it measured per tick within 2 ticks of the animation and dropped
    any heal under 5 hp after rounding. Version 2 (2026-10-02): readings are **segments between
    health bar changes or hits** (a heal may only show with the next hit); a segment counts if an
    eat animation played in it or the tick before it began (blood spells etc. excluded); heals are
    **summed signed and rounded to bites once**, so 30-step bar rounding evens out; **max hp is
    measured** per enemy from non-eating segments (damage / bar fraction lost, after half a bar),
    `foodEnemyMaxHp` only until then. **v2 still read ~6 pizzas short of 23** in the user's
    second test. Version 3: pizza bites go 1 then 2 ticks apart (wiki, Fast foods), so a chain is
    one long animation - "eating" also = `getAnimation()` still 829 at a tick, not just the event;
    and **bites cut short at full hp** (a pizza started 9 down wastes its second bite - a fifth of
    all bites in simulation): a segment where hp could have reached max (start + healed) counts
    `BITES_PER_EAT_ANIMATION` (2) per eat-animation event in it, within `CAPPED_BITE_TICKS` of a
    real heal (so a potion at full hp isn't food). **CONFIRMED in game: one eat animation per
    pizza, none for the second bite** (debug log: 25 events for 23 pizzas). v3 came within ~2
    pizzas of 23 (43 of 46 bites); the per-pizza compensation is newer and untested in game.
    `TrackerTest#aFullInventoryEatenUnderAttackIsCountedClosely` simulates real pizza eating
    (bar every tick / only on hits, eating 18 or 9 down, one animation per pizza) to within
    10% - keep it passing. Debug logging logs each eating segment incl. its eat-event count, and
    heals ignored for no eating seen.
    A brew reads as ~2 bites. Reset on death (user: a dead player is out of the round, back next
    round with full food), at a new round (portal, `startRound()`), and by hand (shift-right-click
    "Reset food count", `foodResetHotkey`). **No forgetting over time** (removed - nobody
    restocks in Clan Wars).
  - *Attackable-range filtering* (`AttackRange` pure, `AttackRangeFilter` per tick) - an enemy
    whose combat differs by more than `min(your wildy level, theirs)` (+15 on PvP worlds, wiki) is
    greyed (`outOfRangeColor`, overrides target/pile/snipe colour, not attackers) and its Attack is
    greyed and never promoted. Your level uses the `IN_WILDERNESS` varbit; others only have
    coordinates. No bracket (nothing filtered) outside the wilderness, e.g. Clan Wars.
    **Ferox Enclave** (asked for): the game hides the wilderness level there (varbit 0), so you'd
    be level 0. Inside the Location Mapper's best-matching box named exactly "Ferox Enclave"
    (`AttackRangeFilter.SAFE_ZONE_NAME`, shipped box levels 13-16) you get the level of the ground
    you're on - who you could attack once you step out - and anyone inside it is unattackable
    (also on PvP worlds). Renaming that box in the Mapper breaks this. Team levels differ (e.g. 55-60): `attackRangeBasis` YOU (default, exact for
    your clicks) or TEAM (greyed only if no on-screen teammate can reach them), plus
    `attackRangeMargin` slack.
  - *Team performance tracker* (`TeamPerformanceTracker`, `PerformanceRow`, `PerformanceRound`,
    sidebar **Stats** tab) - per round (entering an instance - the Clan Wars portal - starts one;
    `PrepToolsPlugin#onGameTick` detects it for food and stats alike; up to 20 kept). **Simplified
    2026-10-07 (asked for):** one row per teammate **in order of death** (survivors last,
    `PerformanceRow#byDeath()`), showing only when they died, pizzas eaten (eat animations, one a
    pizza) and **On pile N/M** - kills they were on (interacting/attack anim at the enemy within
    `PILE_CREDIT_TICKS` of its death) out of kills while they were on your screen.
    **Potions after the countdown (asked for 2026-10-11):** an eat only counts as a pizza while
    the teammate isn't known to be at full hp (`Stats#missingHp`: 0 if first seen during the
    countdown or their bar *changes* to full, + every hit, - 18 a pizza, - 1 a minute, unknown
    after leaving view; yours exact from your stats). Not "ate but didn't gain hp": another
    player's bar is only there after a hit and a heal often only shows with the next hit. The ratings,
    switch time, called-target time, damage and the fight log/EASY DEATH were removed - don't
    re-add without asking.
    **Pile order** (asked for 2026-10-07): per kill they were around for, the place they got onto
    that pile (`TeamPerformanceTracker#pileOrder()`: by the start tick of their latest stretch on
    the enemy - a gap over `PILE_CREDIT_TICKS` starts a new stretch, so an old chip isn't "1st";
    same tick = shared place). **A missed pile counts as last place** (teammates around), so
    skipping piles can't help the average; the row also shows "missed N". Shown as "Pile order avg
    4.2". A **live** "not following the pile" marker was built and **removed** the same day ("useless
    information during battle" - after the fact is what matters). Don't re-add.
    **Used by copying a round into a spreadsheet and posting it as CSV in Discord** (2026-10-08
    feedback): Copy has verbose column headers, the player's nickname in its own column (sidebar:
    `Name [Nick]`), `(you)`/`(caller)` after the name, pizzas as `2 (36hp)` (18 hp a pizza), and
    times as `2m 31s` - `2:31` pasted into LibreOffice as 2:31 AM. A Clan Wars round has a **2 minute
    countdown**: times count from its end and eats during it (boost potions) aren't counted. The
    end comes from varbit `CLANWARS_COUNTDOWN` (4286) = **ticks left** - read from the game's own
    HUD scripts in the cache (cs2 596 shows the countdown widget while it's above 0, times it from
    varbit * 30 client cycles; 598 formats it), so a late joiner is right too. Chosen over a chat
    message (server text, not in the cache). To read cs2 yourself: the `cache` module's
    `Disassembler` over a *copy* of `~/.runelite/jagexcache/oldschool/LIVE`.
  - *Big hitpoints display* (`HitpointsOverlay`) - big outlined HP number + bar under your
    character, green to red, pulses at/below `lowHitpointsPercent` (50). `bigHitpointsAttackers`
    adds the infobox's "Attacking you" weapon icons + counts under the bar (`FightInfoTracker`
    counts attackers whenever either display wants them). *Low hitpoints red edges*
    (`LowHitpointsOverlay`) - red radial vignette below that mark, stronger the lower, pulsing.
  - *Piled alert sound* (`AttackAlert`) - its own sound when more than `multiAttackThreshold` (2)
    enemies are interacting with you (rising edge, Attack Alert's volume/cooldown/custom file; wins
    over the normal attacked sound on the same tick). Default `SAILING_CHART` = sound id **11166**,
    the "Chart complete" sound - from the OSRS wiki's `File:Charting_sound_effect.wav` licence
    template (`id=11166`), not in `SoundEffectID`; unconfirmed in game.
  - *Player nicknames* - **moved out of Experimental into its own Nicknames section, on by
    default** (new key `nicknamesEnabled`; the old `showPlayerNicknames` key defaulted off, which
    is why "Set nickname" didn't appear for the user). `PlayerNicknameTracker`,
    `PlayerNicknameOverlay`, sidebar **Names** tab - TeamSpeak-style names for anyone, as `[Nick]` after the name in the menu; over their head
    only with `showNicknamesOverHeads` (**default off**, asked for). "Set nickname"
    (`ChatboxPanelManager` prompt): shift-right-click a player in game, or right-click a name in
    the friends/ignore list, friends chat, clan list, chatbox or private chat
    (`PrepToolsPlugin#onMenuEntryAdded`, same widget/option choice as core's Hiscore "Lookup").
    Hover tooltip anywhere a menu names a player (`NicknameTooltipOverlay`: left-click entry's
    player, or a name-list widget entry), like Friend Notes' tooltip. The lookalike-name warning
    was built and **removed** ("not useful") - don't re-add.
    Hidden config `playerNicknames`, `Name=Nickname` per line. **There's no account id for other
    players** - `Nameable` is just `getName()`/`getPrevName()`. Core's Friend Notes plugin (what
    the user used for nicknames that survived name changes) stores notes by name and moves them on
    `NameableNameChanged`; nicknames do the same, and Friend Notes' notes (config group
    `friendNotes`, keys `note_<name>`, first line, 20 chars) count as nicknames too
    (`useFriendNotesAsNicknames`, default on; the Names tab wins).
    **Checked in the injected client's bytecode (2026-10-02):** a friend entry holds only name,
    previous name, three ints (world, rank, and probably the order it was added) and two
    flags - no account id. The friends-list packet carries name + one previous name per friend,
    and RuneLite fires `NameableNameChanged` on *every* list update, not only on a rename. So
    renames A to B to C while you're offline arrive as "C, previously B": a note keyed on A isn't
    moved. It isn't deleted either - it stays in config under `note_A`. What the server sends as
    the previous name is server-side and unconfirmed. (To read the injected client: unzip
    `~/.gradle/caches/**/injected-client-*.jar`, `grep -rla <api class name>`, then `javap -c -p`.)
    So `FriendRenameTracker` saves each list per account (RS-profile config `friendsListSnapshot`/
    `ignoreListSnapshot`) and, once the list has loaded after login (10+ ticks, size steady 5),
    compares (`RenameMatch`, pure): the game's previous name pairs for sure; then exactly one
    left + one arrived is a rename too (catches double renames); anything else about a
    nicknamed player goes to "Possible renames" in the Names tab. Moves our nickname and the
    Friend Notes note (`ConfigListEditor#moveFriendNote()`, only if the new name has none). Never
    saves an empty list (= not loaded yet). Adds/removes on another client between logins can
    fake a one-for-one pair - the console line says what moved. Against renames meant to confuse:
    a name one edit off a nicknamed name, or equal after folding I/l/1, O/0, rn/m, vv/w and
    space/-/_, shows `NOT Nick?` (cached per name).
- **Highlight Others** (own section, default off) - core Player Indicators' "Highlight others"
  built in (`OthersOverlay`): names over every non-team player, our name font, skipping anyone
  `CallerNameOverlay` already names. "Only in your level range" (+ `othersRangeBuffer`, default
  0) uses `AttackRangeFilter#getOthersInRange()` - same bracket and Ferox rules; with no bracket
  (Clan Wars) everyone shows.
- **Misc: world on map button** (`WorldNumberOverlay`, default on) - "W318" over
  `InterfaceID.Orbs.ORB_WORLDMAP` (`OrbsNomap` fallback).
- **Decided: no "mirror window without PvP Tools overlays"** (asked 2026-10-02). Overlays are
  drawn into the same frame buffer as the game, interleaved with its layers (`Hooks#drawScene`/
  `drawAboveOverheads`/`drawInterface`/`draw`), and with GPU on that buffer is composited over
  the scene in OpenGL - a frame without our overlays means rendering everything twice. A capture
  of the finished frame (`DrawManager`) always includes them. Offered instead: a hotkey that
  hides all PvP Tools overlays while recording.

**Jagex's third-party client guidelines:** raised with the user on 2026-10-05 (several features
here match Jagex's banned list). The user says they don't apply in the context this project is
developed and used in. Don't re-raise them for feature requests. Still out of scope: anything
that performs game actions for the player (clicks, casts, eats, moves) - that's botting.
Roadmap: `REVIEW_AND_ROADMAP.md`.

Config file (`PrepToolsConfig.java`) is the fastest way to see the full feature surface — it's kept
well-commented and organized by section, sections ordered/all collapsed-by-default to match the
sidebar's own tab order. The sidebar (`PrepToolsPanel.java`) is a `MaterialTabGroup` in a fixed
3-column grid (Callers/Snipe/Spam, Clan Info/FC/Capes, Location/Stats/Names - 3/3/3; 4 columns was tried and
cut every label off at sidebar width), icon+label
centred in each cell. Every tab's icon is a **real in-game image**, not a bundled file (the plugin's
only resources are two data files - `multi_lines.json` and `wilderness_locations.json` in
`runelite-client/src/main/resources/net/runelite/client/plugins/preptools/` - no image assets; the
installer icons in `runelite-client/jpackage/` are packaging-only) - a UI sprite via
`SpriteManager#getSpriteAsync()` (`PrepToolsPanel#loadTabIcon()`) for eight of them, but Capes uses an actual team cape **item** icon via `ItemManager`
(`#loadTabItemIcon()`, `TeamCapeOverlay.teamCapeItemId(30)`) since the generic equipment-slot sprite
tried first rendered as a blank placeholder, not a real cape. Every section follows the same
three-tier visual style, spaced apart with explicit `Box.createVerticalStrut(TIER_GAP)` calls
(border-insets alone read as "no spacing") - `createSectionTitle()` (bold + brand-orange underline),
`createDescriptionArea()` (muted text + left accent stripe), `styleInputRow()` (padding only, no
background/border box - tried a boxed-card look, asked to remove it while keeping the spacing).

### Code map (where to make a change)

`PrepToolsPlugin` is only wiring: lifecycle, routing events to the feature classes below,
reloading them on `ConfigChanged`, and pushing data to the sidebar. Each feature owns its state:

| Class | Owns |
|---|---|
| `TeamTracker` | who's on your team - `refresh()` once per tick, **first**; everything else calls `isOwnTeam()` (a set lookup) |
| `HighlightTracker` | primary/backup callers + pin, target (+flash), snipes, piles, attackers, per-player `HighlightStyle` |
| `MenuHandler` | all right-click menu edits (`onPostMenuSort`, shift-walk on `onClientTick`) |
| `FreezeTracker` | freeze/TB timers (`FreezeState`/`TeleblockState`) + your own freeze |
| `FightInfoTracker` | infobox numbers (opponent prayers/weapons, attacking you, stat boosts) |
| `AttackAlert` | attacked/frozen sound + `ScreenBorderFlashOverlay` colour |
| `LocationTracker` | position, wilderness level/location, Mapper corners/preview, `WildernessLocationStore` |
| `ChatHotkeys`, `ScoutReporter` | Pile Spam, Spammer, Location Spam; Scout webhook |
| `ConfigListEditor` | every edit to the hidden list config (callers/snipe/spam/capes/nicknames) - sidebar + menu |
| `HiscoreLookups` | manual team/enemy hiscore lookups (rate-limited queue, disk cache) + pure grouping/Defence split/CSV |
| `FoodTracker`, `AttackRangeFilter`, `TeamPerformanceTracker`, `PlayerNicknameTracker`, `FriendRenameTracker` | Experimental: food eaten, combat bracket incl. Highlight Others' range (before `HighlightTracker` each tick), Stats tab, nicknames, renames between logins |
| `CallerEntry`, `ConfigLists`, `ScoutReport`, `WeaponStyle`, `AttackCategory#resolve()`, `AttackRange`, `PerformanceRow#byDeath()`, `RenameMatch`, `FoodTracker#biteCount()`, `EnemyLevels` | pure logic - unit tested |

Overlays inject the tracker they draw from, not the plugin. Tests live in
`runelite-client/src/test/java/net/runelite/client/plugins/preptools/` - pure logic, plus
`MenuHandlerTest`/`TrackerTest` driving the real classes against a mocked `Client`/`Player`
(`TestMenuEntry` from core tests builds menu entries). Add a test with any behaviour change there.

- **Feature classes are bound as singletons in `PrepToolsPlugin#configure()`.** RuneLite installs
  the plugin as a Guice module in its own child injector, so this scopes them to the plugin. Without
  it every injection point (plugin, overlays, other trackers) gets its **own** instance and they
  silently stop sharing state; `@Singleton` on the class instead risks Guice creating it in the
  parent injector (all its deps live there), outliving the plugin. New feature class = add it to
  that list.
- **Threads:** game state only on the client thread (game events, overlays, `clientThread.invoke`
  from hotkeys). The sidebar only on the Swing EDT - `PrepToolsPlugin#onSwing()` hands it a
  snapshot. It used to be updated straight from `onGameTick` (client thread), which Swing doesn't
  allow. Values the sidebar writes (pin, config lists, Mapper preview) are `volatile` swaps.

## Open questions / unverified

- **Hiscore lookups (2026-10-25)** - not seen in game. Do names with spaces resolve? Does the
  stat table fit the sidebar width at 2x scale?
- **Enemy fall-in left-click Follow (2026-10-25)** - not seen in game. Enemy Levels tab section
  CONFIRMED ("works", "looks great").

- **Fall-in leader outline, enemy fall-in finder, cape level range (2026-10-18)** - not seen in
  game. Is threshold 10 sensible for enemies? Do enemies attacking a capeless third clan show up
  as a false "fall-in"?

- **Scout pictures, fall-in finder (2026-10-11)** - the first minimap crop was seen working.
  Not yet seen: the drawn map, the minimap's north-up turn + zoom-out (does the minimap blink
  visibly, is the turn's direction right?), the cape counter on the map.
- **Pizza vs potion by missing hp, Scout "Post as", Clan Info nicknames, attackers under big HP
  (2026-10-11)** - not seen in game. Does a strength potion mid-round still count? Debug logging
  logs "ate at full hp - a potion". Assumes everyone is at full hp during the Clan Wars countdown.

Everything built but not yet confirmed in game / on a real machine - move an item into the relevant
section (or delete it) once the user confirms it:

- **Stats countdown from the varbit (2026-10-08)** - from the cache scripts, not seen in game.
  Debug logging logs each change ("Clan Wars countdown A to B"): it should go to ~200 at the
  portal (less if late) and to 0 when the walls drop.
- **Simplified Stats tab + pile order, big HP display/red edges, piled alert (2026-10-07)** - not
  seen in game. Does sound 11166 really play the Sailing chart sound? Does a mage's interacting
  stay on the target between manual casts (else their pile stretch breaks and they rank late)?
- **Bind blocks + "already held" fallback (2026-10-05)** - not seen in game. Is the message
  `GAMEMESSAGE` and exactly that text (wiki)? Is your interacting target still the bind's target
  when it arrives? Does the immune block ever hide a cast that would have landed (a cast is
  checked at cast time, not when the projectile hits - unconfirmed)?
- **Same-tick splash + teammate's snare** - does it show the snare (Debug logging lines
  "PvP Tools freeze:")?
- **The Experimental section (2026-10-02)** - Check: food detector v2 against the user's
  25-pizza test (v1 undercounted - see its entry); turn on Debug logging to see each eating segment
  and the measured max hp in `client.log`. Is the Clan Wars arena an
  instance (the portal reset depends on `WorldView#isInstance()`)? Does "Set nickname" appear once
  per name in each list/chat, and the hover tooltip there? Does the friends list really finish
  loading within ~10 ticks of login (else the rename comparison runs on a partial list - it
  would see "left" names and, with one arrival, could mis-pair)? Does the world number sit right on
  the map button in fixed and resizable modes? Ferox: range from the ground you stand on. Is the
  shallower-wilderness-level rule right (wiki only says "your level +- wilderness level")? On a PvP
  world the filter adds 15 to coordinate levels and knows nothing of PvP-world safe zones (banks
  etc.) - does that grey/ungrey the right people? Are attack animations counted without false positives (blocks not in
  `BLOCK_ANIMATION_IDS`, spec/teleport animations)? Is the hitsplat on another player
  `DAMAGE_OTHER` and on you `DAMAGE_ME` (damage taken counts `isMine() || isOthers()`)?
- **2026-09-26 fixes** - Bryophyta's staff (+ LMS/cosmetic weapon variants) counted as magic,
  "Block spells on teammates" (every spell, not just freezes). Not yet seen in game.
- **Chat hotkeys pre-filling (2026-09-25)** - Spammer/Location/Pile Spam now type into the
  chatbox with `/p `, `/f ` or `/c `; confirm the text appears and Enter sends it to the right
  channel.
- **Refreeze fix, frozen target/pile colours, even-height Team Capes rows (2026-09-25)** - not
  yet seen in game. The refreeze timing (immunity = duration + 5 from the hit) is from the OSRS
  wiki; if a refreeze shows a tick early/late, `FreezeTracker#newFreeze()` is where to adjust.
- **Multi Lines' wilderness areas and extended-scene heights** - see the Multi Lines entry and the
  `WorldLines` lesson.
- **Fall-in leader showing Attack / highlighted** (reported 2026-09-24) - fixed by excluding
  teammates from the caller's target and hiding teammate Attack. The pile finder already excluded
  teammates, so the report's "set as a pile" was most likely the *target* highlight (primary caller
  following the leader); confirm the leader no longer gets any highlight in game.
- **The 2026-09-24 refactor** (plugin split into the classes in the Code map) - tests cover the
  logic, but it hasn't run in game yet. Watch for: highlights, menu priority, freeze/TB timers,
  sidebar status, hotkeys, scout. Also the bug fixes it shipped with: freeze not restarted by a hit
  while frozen/immune; shift-walk now beats a prioritised Attack; a random enemy's Attack only
  promoted when the removed teammate entry was the left-click; tracked players cleared on
  hop/logout; "Add caller"/"Add to snipe list" hidden when already added; freeze status row works
  with Freeze Timers off; Scout's multi-line format.
- **Attackers highlight + the 4-slot Attack priority, "Keep border while frozen", Scout's new
  `2x Team-50 (EoP)` format** - built 2026-09-24, not yet seen in game. Scout sending itself is
  CONFIRMED working.
- **Attack Alert (sounds + border flash), Clan Info weapons + Copy buttons, "You
  died" log line, two-line caller rows, Clan Info "you" colour** - built 2026-09-24, not yet seen
  in game. Also whether "Clan Info" still fits its tab cell at sidebar width.
- **The fixed `.deb` on the user's other machine** - fixed and validated locally (desktop file,
  `apt-get install --simulate`, packaged client launched on its Java 11), but the machine where it
  failed hasn't retried yet.
- **Windows `.exe` and macOS `.dmg`** - never built on this machine (jpackage can't cross-compile);
  only a Package workflow run proves them. That includes the WiX PATH fix (below), the `.ico`/`.icns`
  icons, and jpackage 17's launcher running the bundled Java 11 runtime on those OSes.

## Backlog

Agreed with the user but deliberately not built yet. Low priority unless marked otherwise:

- **Enemy Defence distribution from worn gear** (agreed 2026-10-25, plan in
  [issue #1](https://github.com/J-Haynes/Runelite-pgtools/issues/1)). The client never gets
  another player's Defence; gear proves only a minimum (40+/20-39/10-19/unknown - 1-5 can't be
  confirmed). **No bulk hiscore lookups of players in view** - not standard (core looks up only
  the player you fight), rate-limited, and pures are often unranked in Defence anyway.

- **Freeze sometimes not shown on an obviously frozen target** (reported 2026-09-26: casting
  Snare got "this player is already snared", no icon). Investigated, no clear cause - backlogged
  to revisit if it keeps happening. Ruled out: Protect from Magic (doesn't shorten any freeze,
  see Freeze tracking), clear/forget paths (only hop/logout, death, despawn), graphic IDs (Snare
  is only 180; Burst/Blitz look swapped vs the gameval names, but core `GameTimer` maps them the
  same way this plugin does - Jagex's names are backwards), no errors in `client.log`. Still
  suspects, none confirmed: (1) `advance()`'s movement check dropping a real freeze; (2) a missed
  `GraphicChanged` (mixin source isn't in this repo), e.g. a player spawning into view already
  hit - a per-tick `hasSpotAnim` poll would catch it, but must not re-detect a lingering graphic
  as a new freeze; (3) an unlisted impact variant (`ICE_BARRAGE_IMPACT2/3/4/_LONG/_SHORT`,
  `ICE_BLITZ_IMPACT2/3` exist in `SpotanimID`), not Snare's case though. Next time: turn on
  debug logging and log freeze add/drop reasons in `FreezeTracker` to see which it is.
  **Likely cause found 2026-10-05:** a same-tick splash (see the lessons) - now covered by the
  "already held" message fallback, which is exactly the message from this report.

- **Location Mapper personal layer: one config key per change** (agreed 2026-10-11). Today all of
  `wildernessLocationsUserLayer` is one key, and RuneLite saves per *key* (each client writes only
  keys it changed, merged into the re-read file) - so mapping in two clients at once, or editing in
  B after mapping in A without restarting B, makes the last saver's whole layer win. Split into a
  key per added/edited/hidden box id (+ a one-time migration from the single key) so two clients'
  edits merge, conflicting only on the same box. Until then: map in one client only.

- **(Low) Clear a TB when an in-view player leaves the Wilderness** - the game ends it then (OSRS
  wiki), but the icon stays until the timer runs out. Rare in practice, so deferred on purpose
  (2026-09-25). If built: a per-tick position check on each teleblocked player
  (`LocationTracker`'s coordinate formula works for any player).
- **Decided against (don't re-propose): remembering a TB across the player leaving view.** A TB
  ends on logout (incl. hop), death, or leaving the Wilderness - all likely out of view - so a
  remembered TB would usually be stale, and a false TB is worse than none. Freeze/TB state is
  dropped on `PlayerDespawned`.

## Build & run

```
./gradlew :client:compileJava     # compile-check only
./gradlew :client:shadowJar       # build runelite-client/build/libs/client-*-shaded.jar
./run-runelite.sh                 # launch the locally built client (2x UI scale)

./gradlew :client:test --tests 'net.runelite.client.plugins.preptools.*'   # plugin tests only (seconds)
./gradlew :client:test            # all unit tests (incl. PluginManagerTest) - run before pushing
./gradlew :client:checkstyleMain :client:pmdMain  # style + static analysis CI enforces
./gradlew :buildAll               # what CI actually runs (ci/build.sh): compile, checkstyle, tests, javadoc
```

Run `:buildAll` (or at least checkstyle **and PMD**) before pushing - PMD's unused field/local
variable rules failed CI after a refactor that passed compile, checkstyle and tests.
`:buildAll` passing locally is NOT enough for Javadoc - this machine's JDK only warns where CI
errors; see the strict Javadoc check in the lessons below.

**Jagex accounts / RL Account Selector app** (`tools/rl-account-selector/`, `install.sh` adds a
menu entry + `rl-account-selector`): GTK app listing `~/.runelite/Accounts/*.properties` (the user's
`--insecure-write-credentials` dumps) with Play / Rebuild / Rebuild & Play. **How the tokens work** (read from the
injected client 2026-10-05, closed source): `JX_*` env vars (set by the Jagex launcher) win; with
`--insecure-write-credentials` they're also dumped to the file. Otherwise the file is read. On
login the client trades `JX_REFRESH_TOKEN` at `auth.jagex.com/shield/oauth/token`
(`grant_type=refresh_token`, `scope=gamesso.token.create`) for a fresh access token, and if Jagex
returned a *different* refresh token it rewrites the file. So the refresh tokens look rotating: two
copies of one account's file can invalidate each other, and so can running one account twice.
Refresh-token lifetime is unknown. The dumped files are mode 664 (group-readable). Plan for an easier, all-OS version (not built): `JAGEX_ACCOUNTS_PLAN.md`. Read from the
injected client (the wiki doesn't say): it loads `new File(~/.runelite,
System.getProperty("runelite.credentials.path", "credentials.properties"))` and **writes
refreshed tokens back to that file**. So the app (and `./run-runelite.sh <account>`) passes
`-Drunelite.credentials.path=Accounts/<name>.properties` via `JDK_JAVA_OPTIONS` - no copying,
accounts side by side. "Copy mode" keeps the old copy-to-`credentials.properties` workflow but
saves that file back to its account (matched by `JX_CHARACTER_ID`) before switching away.
"No account" (pinned first row) = the normal login screen: points the property at
`no-credentials/none.properties` (folder doesn't exist) and strips `JX_*` env vars, which the
client also reads credentials from. Icon: `icon.png`, the user's artwork cropped to a circle round the wreath, filling the icon, made by
`make-icon.py` (re-run it to change the artwork, then `install.sh`: GNOME caches an icon by path until logout, so
install.sh copies it to a content-hashed name under `~/.local/share/icons/rl-account-selector/`); the desktop entry is named after the app id so GNOME's dock matches it.

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
  machine has 11 and 25 (no 17). `settings.gradle.kts` adds the `foojay-resolver-convention`
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
  Rebuilding is still fine to do without asking (per the rule in Build & run above) - the risk is
  specifically leaving the *old* process running across a rebuild and then continuing to interact
  with it. **Restart procedure (once the user has OK'd it): stop the client first, then
  `shadowJar`, then launch** (a `kill` skips the settings save - see the settings lesson; there's
  no wmctrl/xdotool on this Wayland machine, so ask the user to close the window if they've
  changed settings recently) - never rebuild under a client you're about to keep using. Match the
  process with a pattern that can't match your own shell (e.g. `ps -eo pid,args | grep "[j]ava -jar
  runelite-client/build/libs"`) - a plain `pgrep -f` on the jar path also matched and killed the
  shell running the command once. If the client freezes after a rebuild, this is the first thing to
  check in `~/.runelite/logs/client.log`.
- **A plain `ArrayList` read by an `Overlay#render()` (game render thread) and mutated by a sidebar
  button click (Swing EDT) will eventually throw `ConcurrentModificationException`.** Hit this with
  the wilderness location list: `WildernessLocationDebugOverlay.render()` iterates it every frame
  while sidebar buttons add/remove/edit from the EDT. `OverlayRenderer.safeRender()` catches the
  exception so it doesn't crash the client, but that frame's overlay silently draws nothing - easy
  to misread as "the button didn't do anything" rather than "it worked, the overlay skipped a
  frame." Current fix: `WildernessLocationStore` never mutates the list it hands out - every change
  builds a new unmodifiable list and swaps a `volatile` reference, so readers always iterate a
  stable snapshot. (`CopyOnWriteArrayList` was the first fix and works too.) A single simple
  reference field read cross-thread without synchronization (e.g. `lastKnownWorldPoint`) is fine -
  reference assignment is atomic; this is specifically a collection-structural-mutation problem.
- **A plugin can't send chat any more: `client.runScript(ScriptID.CHAT_SEND, ...)` throws
  `RuntimeException: Packet sent from script without permission`** (logged as `Client error: 5517
  5008`, nothing visible in game - the hotkeys just silently did nothing). It came with the
  `injected-client:1.12.40-SNAPSHOT` Gradle downloaded on 2026-09-24 16:26 - the fork pulls
  RuneLite's latest snapshot, so the game client can change under an unchanged plugin. Upstream
  commit `fd5083ef8` ("rl-client: block packets sent from cs2s by default", committed 2026-09-23,
  not yet merged into this fork) adds an opt-in, `ScriptEvent#setCanSendPackets(true)`. Core uses
  it only for input the player made themselves: re-sending chat they typed, a bank pin key they
  pressed, a kick they confirmed. Sending a canned message from a hotkey is against RuneLite's
  rules: its
  [Rejected or Rolled Back Features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features)
  wiki page bans inserting text into the chatbox "for any reason" as autotyping - which covers
  the current pre-fill (`setVarcStrValue(VarClientID.CHATINPUT, ...)` +
  `runScript(ScriptID.CHAT_PROMPT_INIT)`) too. When a feature that worked stops, grep
  `~/.runelite/logs/client.log` for `Client error` before reading the plugin code.
- **The injected client can outrun this fork's API: crash at login with `NoSuchMethodError`.**
  `injected-client` is a `-SNAPSHOT` that any Gradle build re-downloads when upstream publishes a
  new one, but `runelite-api` is this fork's own source, only as new as the last upstream merge.
  2026-09-26: a rev 241 injected client (fetched by a test run) called
  `VarbitChanged.setLongValue(long)`, added by upstream's `376527a13 api: update 241` - the client
  started fine and died on login. **Fix: merge `upstream/master`** (don't hand-patch the API).
  `InjectedClientApiTest` (`runelite-client/src/test/java/net/runelite/client/`) scans the
  injected client's constant pools and fails, listing every missing `net.runelite` method/field,
  so `:client:test` catches it before a launch. `run-runelite.sh` launches the newest
  `client-*-shaded.jar` because upstream merges bump the version (1.13.0-SNAPSHOT next) - delete
  old jars in `runelite-client/build/libs/` if in doubt.
  **Quieter variant: the game updates its cs2 scripts and RuneLite's script overlays
  (`runelite-client/src/main/scripts/*.rs2asm` + `.hash`) stop applying** - logged as `WARN
  injected-client - Mismatch in overlaid cache archive hash for 12/<script id>`, no error. Every
  RuneLite hook in that script silently dies: 2026-10-04, script 73 (`ChatDefaultOnKey`, fires
  the `runeliteCommand` callback) broke every `::` command (`::hop 308` did nothing) while the
  official client worked. Same fix: merge `upstream/master` ("Update Scripts to <date>").
- **A `PluginPanel` with its own per-tab scroll panes must call `super(false)`.** The default
  `PluginPanel()` constructor wraps the whole panel in an outer `JScrollPane`. Nested inside that,
  a tab taller than the sidebar grows to full height in the OUTER pane, so its own inner pane has
  nothing to scroll - but still swallows every mouse-wheel event over the content, so the tab only
  scrolls by dragging the outer scrollbar (the Location tab once its list got long). `PrepToolsPanel`
  now calls `super(false)` like `TimeTrackingPanel` (RuneLite core), doesn't override
  `getPreferredSize()` (PluginPanel's own width already leaves room for a scrollbar), and each tab's
  content is a `ViewportWidthPanel` (`Scrollable`: tracks the viewport width so content can't end up
  wider than the sidebar, 16px per wheel notch instead of Swing's 1px default). **Fix CONFIRMED in
  game.**
- **Never expose a list the sidebar edits as a visible text `@ConfigItem` - mark it
  `hidden = true`.** RuneLite's settings panel (`ConfigPanel#createTextField`) saves a text field on
  *focus-lost* with whatever text it loaded when opened, and never refreshes on outside changes. So
  settings open -> edit list in sidebar -> back to settings -> focus leaves the field = stale text
  written straight over the sidebar's edits ("switching between the sidebar and settings can destroy
  the caller list"). `callers`, `snipeList`, `teamCapeNames`, `spamMessages`, `selectedSpamMessage`, `playerNicknames`
  are all hidden for this reason; the sidebar is their only editor.
- **The system `java` is Java 25 now (Ubuntu 26.04), not 11 - found 2026-10-25.** The client
  launched on it, and Gson then threw `InaccessibleObjectException` serializing
  `Collections.emptyList()`, so every Scout (F9) press failed with nothing shown in game.
  Gradle fails on it too (the error is just "25.0.4.1"). `run-runelite.sh` now prefers
  `/usr/lib/jvm/java-11-openjdk-amd64`; run Gradle with
  `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64`. Don't hand Gson immutable JDK collections
  (`Collections.empty*`, `List.of`) - use `ArrayList`/`LinkedHashMap`.
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
- **Same-tick splash + freeze - the client may only ever get one of them (2026-10-05):** the
  2026-10-02 debug log has many `[85]`/`[180]` (splash/snare) lines and never both on one player,
  so they likely share a graphic slot and the server sends one. The user reported exactly this
  (you splash, a teammate's snare catches, no icon). Fallback: the game message "Your target is
  already held by a magical force." (`PrepToolsPlugin#onChatMessage`) calls
  `FreezeTracker#onTargetAlreadyHeld()` for whoever you're interacting with - assumes a Snare
  starting at their last splash (if within 16 ticks), else now. Earlier note, still true as far
  as it goes:
- **Same-tick splash + freeze (checked 2026-10-02):** an actor's spot anims are stored per SLOT
  (injected client: `ci.do(slot, id, ...)` replaces the slot's entry, then posts GraphicChanged for
  each set) - so a snare and a splash landing the same tick are both seen, either order, and
  `FreezeTracker` freezes (`TrackerTest#aSnareLandingWithASplashOnTheSameTickStillFreezes`). It can
  only be missed if the SERVER never sends the snare graphic; with Debug logging on, FreezeTracker
  logs every splash/freeze graphic with all the player's spot anims - check `client.log` after a
  same-tick test to settle it.
- **A freeze spell's graphic shows on every hit, frozen or not** - a barrage on someone already
  frozen, or immune (5 ticks after a freeze), still plays the ice graphic but doesn't freeze or
  refresh. `FreezeTracker` ignores freeze graphics on anyone it's already tracking (frozen or
  immune); it used to restart the countdown, showing freezes that weren't there.
- **RuneLite settings are saved only on a window close (`ClientShutdown`) or every 5 minutes
  (`ConfigManager`'s scheduled `sendConfig`) - there's no JVM shutdown hook, so a SIGTERM (`kill`,
  desktop logout) drops up to 5 minutes of changes.** Each client writes only the keys it changed.
  Settings are per *profile*: the user's PvP client runs profile `pvp`, their normal settings
  (NPC highlights, ground items) are in `default` - switching profile looks like settings vanished.
- **Git remotes/identity:** `origin` is the user's own fork (`git@github.com:J-Haynes/Runelite-pgtools.git`,
  push here); `upstream` is `runelite/runelite` (fetch only - never push). The user has set their
  own git `user.name`/`user.email`; Claude still must not change git config itself. The `gh` CLI
  is installed and logged in as J-Haynes (installed 2026-09-24; SSH git protocol): check CI after a
  push with `gh run list --repo J-Haynes/Runelite-pgtools` and
  `gh run view <id> --repo J-Haynes/Runelite-pgtools --log-failed | grep -E "FAILED|What went wrong|violation| error:"`
  (the raw log is thousands of lines of unrelated upstream warnings).
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
