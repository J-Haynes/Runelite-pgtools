# This repo

A fork of RuneLite tracking a future OSRS content revision (see recent commits like "Update
GameVals to 2026-09-16-rev240"). The active work in this repo is a single plugin:

## PvP Tools (`preptools`)

Location: `runelite-client/src/main/java/net/runelite/client/plugins/preptools/`

A team-PK / multi-clanning coordination tool — the kind of thing a small clan runs together while
fighting as a team in the wilderness. Inspired by [Exilent](https://exilent.org), a PK-focused OSRS
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
  Each row is two lines (asked for - one line was "too busy"): radio + in-game name on top,
  nickname + on-screen status underneath (`PrepToolsPanel#addRow()`).
- **Highlighting** — three independent detection categories, each with its own config section and
  colour, but sharing one rendering style (shape/outline width/font/dot size — the config's
  "Overlay" section, `PrepToolsConfig#highlightShape()` etc.) so every highlighted player looks
  visually consistent no matter which category put them there. Priority when a player qualifies
  for more than one at once, lowest to highest: backup caller < attacker < snipe list < pile
  finder < primary caller < target (`HighlightTracker#rebuildStyles()`, cached once per tick;
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
- **Friends chat log** — sidebar panel (the "FC" tab, alongside the "Missing" list), newest-first,
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
  `secret = true`), with your in-game name as the poster (`ScoutReporter#post()`). Multi-line,
  format asked for explicitly (`ScoutReport#format()`, tested in `ScoutReportTest`):
  `W308, Ferox Enclave, lvl 15 wild.` then one `10x Team-50 (EoP),` line per cape, most first,
  last line without the comma (`No team capes in sight` if none qualify).
  Counts **every** on-screen player by team cape number, own team included - the same numbers as
  the Team Capes overlay (it first skipped FC/clan members, which silently dropped named capes the
  overlay showed). Capes tab name added if set,
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
  number (`PrepToolsConfig#teamCapeNames`). Every cell reports the tallest cell's height
  (`EvenHeightCell`): core's wrapping `PanelComponent` moves a new row down by the *last* cell's
  height, so a named cape (icon + text) in row 2 after a plain icon ended row 1 hung out of the box.
- **Right-click menu integration** — prioritises/recolours Attack and freeze-spell casts (target,
  pile, and now snipe-list hits too, with a configurable tie-break order —
  four "1st..4th Attack priority" dropdowns, `PrepToolsConfig#attackPriority1()`.., default
  Target > Pile > Snipe > Attacker — when more than one applies to the same menu; a category left
  out of the four still goes last. Replaced the single `attackPriorityOrder` enum of orderings
  when Attackers made it 24; `PrepToolsPlugin#migrateAttackPriorityOrder()` carries an old
  choice over once), blocks casting **any** spell on a teammate (`preventFreezingTeammates` - key kept from when it
  was freezes only, now "Block spells on teammates" in the Menu section, default on; the Lunar
  teammate spells like Vengeance Other stay allowed; item-on-player "Use" isn't touched), removes Attack on
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
  configured callers highlighted as "Name (Nickname)", and you in a third colour (blue, wins over
  caller colour). Both sections have a Copy button. See `MENU_ORDER_PID_INVESTIGATION.md` at the repo root for the
  underlying mechanic this is built on (right-click Attack-menu ordering on a stack) and its current
  confidence level/open questions. Clan-only by design: OSRS's friendly-fire protection means an
  enemy's own menu on your team's pile is built exclusively from your team's ids, so enemy ids don't
  factor into who on your side is exposed.

- **Experimental** (config section of that name, position 19, everything **default off**) - built
  2026-10-02, not yet proven in a fight. Once one is confirmed, give it its own section (keep keys).
  - *Low food detector* (`FoodTracker`, `FoodOverlay`) - **for Clan Wars, not the wilderness.**
    Counting eat animations (829) does NOT work, as the user pointed out: potions play the same
    animation, and several anchovy pizza bites can go down in one. So the animation is only a cue
    (a window of `EAT_WINDOW_TICKS`); what's counted is the **heal on the health bar** in that
    window - hp now - hp last tick + hitsplat damage in between, 9 hp a bite (wiki), at least
    `MIN_HEAL` (rounding noise). A heal without an eat (blood spells) is ignored; an eat with no
    health bar showing is "unmeasured" (`+N?`). Other players' health is a 0-30 ratio, max hp
    assumed (`foodEnemyMaxHp`, 99) - +-3 hp per reading, enough for 9 vs 18. **Reset on death**
    (user: in Clan Wars a dead player is out of the round and returns next round with full
    food - a session briefly kept counts through deaths from the wiki's "keep your items", which
    was wrong), for everyone on entering an instance (the portal - everyone enters with full
    food), and by hand: shift-right-click "Reset food count", `foodResetHotkey` (unbound).
    Forgotten after `foodForgetMinutes` unseen. "Low" is in pizzas: assumed pizzas minus bites/2, a half pizza counting as one left.
    Enemies only, text one line below a highlighted name.
  - *Attackable-range filtering* (`AttackRange` pure, `AttackRangeFilter` per tick) - an enemy
    whose combat differs by more than `min(your wildy level, theirs)` (+15 on PvP worlds, wiki) is
    greyed (`outOfRangeColor`, overrides target/pile/snipe colour, not attackers) and its Attack is
    greyed and never promoted. Your level uses the `IN_WILDERNESS` varbit; others only have
    coordinates. Does nothing unless you're in the wilderness or on a PvP world - in Clan Wars or
    Ferox it would grey everyone. Team levels differ (e.g. 55-60): `attackRangeBasis` YOU (default, exact for
    your clicks) or TEAM (greyed only if no on-screen teammate can reach them), plus
    `attackRangeMargin` slack.
  - *Team performance tracker* (`TeamPerformanceTracker`, `PerformanceRow`, sidebar **Stats**
    tab) - per teammate by name, until Reset: on-target share while the primary caller has a
    target, ticks to switch to a new target (however long - **no time limit / "missed" count**:
    the user called a 5-tick limit "ridiculously quick"; a target re-called first just isn't
    measured), attack
    animations (minus eat/block/death) total and on target, damage taken, eats, deaths, kills
    involved (on the enemy within 10 ticks of its death). Rated HIGH/LOW at +-15 points from the
    team median on-target share (3+ rateable non-callers). **Teammates' damage dealt is not
    visible** - a hitsplat has no source - only yours (`isMine()`). Copy = TSV for a spreadsheet.
  - *Player nicknames* (`PlayerNicknameTracker`, `PlayerNicknameOverlay`, `NameLookalike`,
    sidebar **Names** tab, shift-right-click "Set nickname" via `ChatboxPanelManager`) -
    TeamSpeak-style names for anyone, as `[Nick]` after the name in the menu; over their head only
    with `showNicknamesOverHeads` (**default off**, asked for).
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
    `~/.gradle/caches/**/injected-client-*.jar`, `grep -rla <api class name>`, then `javap -c -p`.) Against renames meant to confuse:
    a name one edit off a nicknamed name, or equal after folding I/l/1, O/0, rn/m, vv/w and
    space/-/_, shows `NOT Nick?` (cached per name).
- **Decided: no "mirror window without PvP Tools overlays"** (asked 2026-10-02). Overlays are
  drawn into the same frame buffer as the game, interleaved with its layers (`Hooks#drawScene`/
  `drawAboveOverheads`/`drawInterface`/`draw`), and with GPU on that buffer is composited over
  the scene in OpenGL - a frame without our overlays means rendering everything twice. A capture
  of the finished frame (`DrawManager`) always includes them. Offered instead: a hotkey that
  hides all PvP Tools overlays while recording.

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
| `FoodTracker`, `AttackRangeFilter`, `TeamPerformanceTracker`, `PlayerNicknameTracker` | Experimental: food eaten, combat bracket (before `HighlightTracker` each tick), Stats tab, nicknames |
| `CallerEntry`, `ConfigLists`, `ScoutReport`, `WeaponStyle`, `AttackCategory#resolve()`, `AttackRange`, `PerformanceRow#rate()`, `NameLookalike`, `FoodTracker#biteCount()` | pure logic - unit tested |

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

Everything built but not yet confirmed in game / on a real machine - move an item into the relevant
section (or delete it) once the user confirms it:

- **The Experimental section (2026-10-02)** - none of it seen in game. Check: does an enemy's
  health bar update when they eat (the food detector depends on it), and does the heal land
  within 2 ticks of the eat animation? Is a pizza combo one heal or two? Is the Clan Wars arena an
  instance (the portal reset depends on `WorldView#isInstance()`)? Is the
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

- **Nicknames/Friend Notes that survive any number of renames** (discussed 2026-10-02, "leave it
  for now"): save the friends list at logout; at the next login, pair friends who vanished with new
  names you didn't add (they're renames, however many), and move notes/nicknames - automatic when
  one-to-one, a sidebar to pair by hand otherwise. Adds/removes made on another client in between
  muddy it. See the Player nicknames entry for why the game's own previous name isn't enough.
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
`--insecure-write-credentials` dumps) with Play / Rebuild / Rebuild & Play. Read from the
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
  Rebuilding is still fine to do without asking (per the rule in Build & run above) - the risk is
  specifically leaving the *old* process running across a rebuild and then continuing to interact
  with it. **Restart procedure (once the user has OK'd it): stop the client first, then
  `shadowJar`, then launch** - never rebuild under a client you're about to keep using. Match the
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
- **A freeze spell's graphic shows on every hit, frozen or not** - a barrage on someone already
  frozen, or immune (5 ticks after a freeze), still plays the ice graphic but doesn't freeze or
  refresh. `FreezeTracker` ignores freeze graphics on anyone it's already tracking (frozen or
  immune); it used to restart the countdown, showing freezes that weren't there.
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
