package net.runelite.client.plugins.preptools;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GraphicID;
import net.runelite.api.HeadIcon;
import net.runelite.api.Player;
import net.runelite.api.SpriteID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.SpotanimID;

/**
 * Freeze and teleblock timers, detected from spell impact graphics (GraphicChanged). Players shown
 * by FreezeOverlay/TeleblockOverlay live in freezeStates/teleblockStates, gated by their config
 * toggles; your own freeze is ALSO always tracked separately in localFreeze, for the infobox and
 * the Attack Alert's frozen border. Client thread only.
 */
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
	static final long FREEZE_IMMUNITY_DURATION_MILLIS = FREEZE_IMMUNITY_TICKS * 600L;

	/**
	 * 5 minutes, halved if the target was praying Protect from Magic when it landed (OSRS wiki).
	 */
	private static final int TELEBLOCK_DURATION_TICKS = 500;
	private static final int TELEBLOCK_DURATION_TICKS_PROTECTED = 250;

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	@Getter
	private final Map<Player, FreezeState> freezeStates = new HashMap<>();

	@Getter
	private final Map<Player, TeleblockState> teleblockStates = new HashMap<>();

	/**
	 * Your own freeze (then immunity), tracked whatever the Freeze Timers settings are. Null when
	 * neither.
	 */
	@Getter
	private FreezeState localFreeze;

	@Inject
	FreezeTracker(Client client, PrepToolsConfig config, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.team = team;
	}

	static boolean isFreezeGraphic(int graphicId)
	{
		return FREEZE_SPELLS.containsKey(graphicId);
	}

	/**
	 * Whether you're frozen right now (not counting post-freeze immunity).
	 */
	boolean isLocalFrozen()
	{
		return localFreeze != null && !localFreeze.immune;
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
		int graphic = player.getGraphic();
		Player local = client.getLocalPlayer();
		int tick = client.getTickCount();

		FreezeSpell spell = FREEZE_SPELLS.get(graphic);
		boolean localNewlyFrozen = false;
		if (spell != null)
		{
			if (player == local && localFreeze == null)
			{
				localFreeze = newFreeze(spell, player, tick);
				localNewlyFrozen = true;
			}

			// "Hide for own team" never hides your own freeze.
			boolean hidden = config.hideFreezeTimersForOwnTeam() && player != local && team.isOwnTeam(player);
			if (config.showFreezeTimers() && !hidden && !freezeStates.containsKey(player))
			{
				freezeStates.put(player, newFreeze(spell, player, tick));
			}
		}

		if (graphic == SpotanimID.TELE_BLOCK_IMPACT && config.showTeleblockTimers()
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

	void onGameTick()
	{
		Player local = client.getLocalPlayer();
		int tick = client.getTickCount();

		// Also drops someone who became a teammate mid-freeze, or everyone once "Hide for own team"
		// is switched on.
		if (config.hideFreezeTimersForOwnTeam())
		{
			freezeStates.keySet().removeIf(player -> player != local && team.isOwnTeam(player));
		}
		freezeStates.entrySet().removeIf(entry -> !advance(entry.getKey(), entry.getValue(), tick));

		if (localFreeze != null && (local == null || !advance(local, localFreeze, tick)))
		{
			localFreeze = null;
		}

		if (config.hideTeleblockTimersForOwnTeam())
		{
			teleblockStates.keySet().removeIf(player -> player != local && team.isOwnTeam(player));
		}
		teleblockStates.entrySet().removeIf(entry -> tick >= entry.getValue().endTick);
	}

	/**
	 * Dying ends freezes and teleblocks.
	 */
	void onDeath(Player player)
	{
		freezeStates.remove(player);
		teleblockStates.remove(player);
		if (player == client.getLocalPlayer())
		{
			localFreeze = null;
		}
	}

	void clear()
	{
		freezeStates.clear();
		teleblockStates.clear();
		localFreeze = null;
	}

	private static FreezeState newFreeze(FreezeSpell spell, Player player, int tick)
	{
		// +1 tick: the countdown otherwise hits zero a tick before the player can actually move.
		int duration = spell.durationTicks + 1;
		return new FreezeState(spell.spriteId, tick, tick + duration, System.currentTimeMillis(), duration * 600L,
			player.getWorldLocation());
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
			state.immunityEndTick = state.endTick + FREEZE_IMMUNITY_TICKS;
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
