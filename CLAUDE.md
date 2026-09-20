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
- **Highlighting** — caller/target/pile-finder players get an outline or dot, styled from a shared
  config section, colours per-category.
- **Freeze tracking** — detects Bind/Snare/Entangle/Ice-spell hits, shows icon + countdown, can
  hide/block freezes against teammates.
- **Infobox panel** — bottom-right HUD: your stats, freeze status, target's prayer, opponent
  prayers/weapons, who's attacking you.
- **Right-click menu integration** — prioritises/recolours Attack and freeze-spell casts, blocks
  casting a freeze spell on a teammate.

Config file (`PrepToolsConfig.java`) is the fastest way to see the full feature surface — it's kept
well-commented and organized by section.

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
