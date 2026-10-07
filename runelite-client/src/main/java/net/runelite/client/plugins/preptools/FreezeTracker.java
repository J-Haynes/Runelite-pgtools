package net.runelite.client.plugins.preptools;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.GraphicID;
import net.runelite.api.HeadIcon;
import net.runelite.api.Player;
import net.runelite.api.SpriteID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.SpotanimID;

/**
 * Freeze and teleblock timers, detected from spell impact graphics (GraphicChanged). Every
 * player's freeze is tracked whatever the Freeze Timers settings - the infobox, the Attack Alert's
 * frozen border and the frozen target/pile colours need it - and the overlays ask
 * isFreezeShown() for what to draw. Teleblocks are only tracked when shown. Client thread only.
 */
@Slf4j
class FreezeTracker
{
	/**
	 * A freeze-spell graphic's base duration in ticks and its spell icon. Graphic IDs from
	 * GraphicID, durations from the OSRS wiki. Gear that extends freezes (swampbark, ancient
	 * sceptre) isn't accounted for.
	 */
	private static final class FreezeSpell
	{
		final int durationTicks;
		final int spriteId;

		FreezeSpell(int durationTicks, int spriteId)
		{
			this.durationTicks = durationTicks;
			this.spriteId = spriteId;
		}
	}

	private static final Map<Integer, FreezeSpell> FREEZE_SPELLS = Map.of(
		GraphicID.BIND, new FreezeSpell(8, SpriteID.SPELL_BIND),
		GraphicID.SNARE, new FreezeSpell(16, SpriteID.SPELL_SNARE),
		GraphicID.ENTANGLE, new FreezeSpell(24, SpriteID.SPELL_ENTANGLE),
		GraphicID.ICE_RUSH, new FreezeSpell(8, SpriteID.SPELL_ICE_RUSH),
		GraphicID.ICE_BURST, new FreezeSpell(16, SpriteID.SPELL_ICE_BURST),
		GraphicID.ICE_BLITZ, new FreezeSpell(24, SpriteID.SPELL_ICE_BLITZ),
		GraphicID.ICE_BARRAGE, new FreezeSpell(32, SpriteID.SPELL_ICE_BARRAGE)
	);

	/**
	 * Movement is ignored this many ticks after a freeze lands: a step queued just before the freeze
	 * can still play out, and read as "they moved, so they're not frozen" it cleared timers early.
	 */
	private static final int MOVEMENT_CHECK_GRACE_TICKS = 3;

	/**
	 * After a freeze ends the player can't be frozen again for 5 ticks (OSRS wiki).
	 */
	private static final int FREEZE_IMMUNITY_TICKS = 5;

	/**
	 * Added to the shown freeze countdown only: it otherwise hits zero a tick before the player can
	 * actually move. Not added to immunity - that ends spell duration + 5 ticks after the hit, the
	 * first tick a refreeze lands, so a well-timed refreeze isn't thrown away as "immune".
	 */
	private static final int DISPLAY_EXTRA_TICKS = 1;

	/**
	 * 5 minutes, halved if the target was praying Protect from Magic when it landed (OSRS wiki).
	 */
	private static final int TELEBLOCK_DURATION_TICKS = 500;
	private static final int TELEBLOCK_DURATION_TICKS_PROTECTED = 250;

	/**
	 * What onTargetAlreadyHeld() assumes the unseen freeze was: Snare, the usual F2P bind.
	 */
	private static final int ASSUMED_FREEZE_GRAPHIC = GraphicID.SNARE;

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	@Getter
	private final Map<Player, FreezeState> freezeStates = new HashMap<>();

	@Getter
	private final Map<Player, TeleblockState> teleblockStates = new HashMap<>();

	/**
	 * The last tick each player was hit by a splash - when a teammate's snare catches on the same
	 * tick, the splash can be the only graphic the client gets (see onTargetAlreadyHeld()).
	 */
	private final Map<Player, Integer> lastSplashTicks = new HashMap<>();

	@Inject
	FreezeTracker(Client client, PrepToolsConfig config, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.team = team;
	}

	/**
	 * Your own freeze (then immunity), or null when neither.
	 */
	FreezeState getLocalFreeze()
	{
		Player local = client.getLocalPlayer();
		return local != null ? freezeStates.get(local) : null;
	}

	/**
	 * Whether you're frozen right now (not counting post-freeze immunity).
	 */
	boolean isLocalFrozen()
	{
		return isFrozen(client.getLocalPlayer());
	}

	/**
	 * Whether the player is frozen right now (not counting post-freeze immunity).
	 */
	boolean isFrozen(Player player)
	{
		FreezeState state = player != null ? freezeStates.get(player) : null;
		return state != null && !state.immune;
	}

	/**
	 * Whether the player is held by a freeze right now - the game then refuses Bind, Snare and
	 * Entangle ("Your target is already held by a magical force."). Unlike isFrozen(), this ends at
	 * the spell's real duration, not the shown countdown's extra tick.
	 */
	boolean isHeld(Player player)
	{
		FreezeState state = player != null ? freezeStates.get(player) : null;
		return state != null && client.getTickCount() < state.immunityEndTick - FREEZE_IMMUNITY_TICKS;
	}

	/**
	 * Whether the player is in the 5 ticks after a freeze when they can't be frozen again - the game
	 * then refuses Bind, Snare and Entangle ("The target is currently immune to that spell.").
	 */
	boolean isImmune(Player player)
	{
		FreezeState state = player != null ? freezeStates.get(player) : null;
		int tick = client.getTickCount();
		return state != null && tick >= state.immunityEndTick - FREEZE_IMMUNITY_TICKS && tick < state.immunityEndTick;
	}

	/**
	 * The game said the player you cast a bind on is already held, so a freeze was missed - most
	 * likely your splash and a teammate's snare landed on the same tick, and only one graphic per
	 * slot reaches the client (the 2026-10-02 debug log never shows a splash and a snare together).
	 * Assumes a snare that began at the splash seen within a snare's length, else now; if that
	 * guess runs long, the movement check drops it as soon as they move.
	 */
	void onTargetAlreadyHeld(Player player)
	{
		if (player == null || isHeld(player))
		{
			return;
		}
		int tick = client.getTickCount();
		FreezeSpell spell = FREEZE_SPELLS.get(ASSUMED_FREEZE_GRAPHIC);
		Integer splashTick = lastSplashTicks.get(player);
		int startTick = splashTick != null && tick - splashTick < spell.durationTicks ? splashTick : tick;
		freezeStates.put(player, newFreeze(spell, player, startTick, tick));
		if (config.debugLogging())
		{
			log.info("PvP Tools freeze: tick {} {} already held - assumed a snare from tick {}", tick, player.getName(), startTick);
		}
	}

	/**
	 * Whether FreezeOverlay (and the teleblock row offset) should draw this player's freeze: Freeze
	 * Timers on, and not a teammate hidden by "Hide for own team" - which never hides your own.
	 */
	boolean isFreezeShown(Player player)
	{
		return config.showFreezeTimers()
			&& !(config.hideFreezeTimersForOwnTeam() && player != client.getLocalPlayer() && team.isOwnTeam(player));
	}

	/**
	 * Handles a spell impact graphic on a player. A freeze graphic on someone already frozen or
	 * immune is ignored - the game doesn't refresh or restart a freeze then, even though the spell
	 * still hits and shows its graphic.
	 *
	 * @return true if this froze YOU (a new freeze, not a hit during a freeze or immunity)
	 */
	boolean onGraphicChanged(Player player)
	{
		Player local = client.getLocalPlayer();
		int tick = client.getTickCount();
		if (config.debugLogging())
		{
			logSpellGraphics(player, tick);
		}

		if (player.hasSpotAnim(GraphicID.SPLASH))
		{
			lastSplashTicks.put(player, tick);
		}

		boolean localNewlyFrozen = false;
		FreezeSpell spell = freezeSpellOn(player);
		if (spell != null)
		{
			// The entry isn't dropped until onGameTick, which runs after this tick's graphics - so
			// an entry whose immunity has already run out mustn't block the new freeze.
			FreezeState existing = freezeStates.get(player);
			if (existing == null || tick >= existing.immunityEndTick)
			{
				freezeStates.put(player, newFreeze(spell, player, tick, tick));
				localNewlyFrozen = player == local;
			}
		}

		if (player.hasSpotAnim(SpotanimID.TELE_BLOCK_IMPACT) && config.showTeleblockTimers()
			&& !(config.hideTeleblockTimersForOwnTeam() && player != local && team.isOwnTeam(player)))
		{
			// Protect from Magic is read at impact - it can be switched off straight after.
			int duration = player.getOverheadIcon() == HeadIcon.MAGIC ? TELEBLOCK_DURATION_TICKS_PROTECTED : TELEBLOCK_DURATION_TICKS;
			TeleblockState existing = teleblockStates.get(player);
			if (existing == null || tick + duration > existing.endTick)
			{
				teleblockStates.put(player, new TeleblockState(tick, tick + duration, System.currentTimeMillis(), duration * 600L));
			}
		}

		return localNewlyFrozen;
	}

	/**
	 * Splash and freeze graphics, for checking how the game shows a splash and a freeze landing on
	 * the same tick. A player's spell graphics live in slots, a new one replacing whatever was in
	 * its slot; GraphicChanged fires after each one is stored (seen in the injected client), so a
	 * snare followed by a splash in the same tick is still seen here first. It can only be missed
	 * if the server never sends the snare graphic - this log shows which.
	 */
	private void logSpellGraphics(Player player, int tick)
	{
		StringBuilder ids = new StringBuilder();
		boolean relevant = false;
		for (ActorSpotAnim spotAnim : player.getSpotAnims())
		{
			ids.append(ids.length() > 0 ? "," : "").append(spotAnim.getId());
			relevant |= spotAnim.getId() == GraphicID.SPLASH || FREEZE_SPELLS.containsKey(spotAnim.getId());
		}
		if (relevant)
		{
			log.info("PvP Tools freeze: tick {} {} spot anims [{}]", tick, player.getName(), ids);
		}
	}

	void onGameTick()
	{
		Player local = client.getLocalPlayer();
		int tick = client.getTickCount();

		freezeStates.entrySet().removeIf(entry -> !advance(entry.getKey(), entry.getValue(), tick));
		lastSplashTicks.values().removeIf(splashTick -> tick - splashTick >= FREEZE_SPELLS.get(ASSUMED_FREEZE_GRAPHIC).durationTicks);

		if (config.hideTeleblockTimersForOwnTeam())
		{
			teleblockStates.keySet().removeIf(player -> player != local && team.isOwnTeam(player));
		}
		teleblockStates.entrySet().removeIf(entry -> tick >= entry.getValue().endTick);
	}

	/**
	 * Stops tracking a player's freeze and teleblock: they died (which ends both), or despawned -
	 * logged out or left view - and would otherwise be drawn at their last position until the
	 * timer ran out (up to 5 minutes for a TB). Back in view they're a new Player anyway.
	 */
	void forget(Player player)
	{
		freezeStates.remove(player);
		teleblockStates.remove(player);
		lastSplashTicks.remove(player);
	}

	void clear()
	{
		freezeStates.clear();
		teleblockStates.clear();
		lastSplashTicks.clear();
	}

	/**
	 * The longest freeze spell whose impact graphic the player has. An actor can carry several
	 * spot anims at once (a barrage landing with a teleblock or a spec), and the deprecated
	 * getGraphic() only returns one of them.
	 */
	private static FreezeSpell freezeSpellOn(Player player)
	{
		FreezeSpell longest = null;
		for (Map.Entry<Integer, FreezeSpell> entry : FREEZE_SPELLS.entrySet())
		{
			FreezeSpell spell = entry.getValue();
			if (player.hasSpotAnim(entry.getKey()) && (longest == null || spell.durationTicks > longest.durationTicks))
			{
				longest = spell;
			}
		}
		return longest;
	}

	/**
	 * A freeze that landed on startTick, seen on tick (later only when inferred after the fact).
	 */
	private static FreezeState newFreeze(FreezeSpell spell, Player player, int startTick, int tick)
	{
		int duration = spell.durationTicks + DISPLAY_EXTRA_TICKS;
		long startTimeMillis = System.currentTimeMillis() - (tick - startTick) * 600L;
		return new FreezeState(spell.spriteId, startTick, startTick + duration, startTimeMillis, duration * 600L,
			startTick + spell.durationTicks + FREEZE_IMMUNITY_TICKS, player.getWorldLocation());
	}

	/**
	 * Moves a freeze on by a tick: into immunity once it expires, gone once immunity expires, and
	 * gone early if the player moves while supposedly frozen (after the grace ticks).
	 *
	 * @return false to stop tracking it
	 */
	private static boolean advance(Player player, FreezeState state, int tick)
	{
		if (!state.immune && tick >= state.endTick)
		{
			state.immune = true;
			state.immunityStartTimeMillis = System.currentTimeMillis();
		}

		if (state.immune)
		{
			// Free to move while immune.
			return tick < state.immunityEndTick;
		}

		WorldPoint location = player.getWorldLocation();
		if (tick - state.startTick >= MOVEMENT_CHECK_GRACE_TICKS
			&& state.lastLocation != null && location != null && !location.equals(state.lastLocation))
		{
			return false;
		}
		state.lastLocation = location;
		return true;
	}
}
