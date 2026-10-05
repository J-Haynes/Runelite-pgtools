package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;

/**
 * Everything about the combat bracket (AttackRange), recomputed once per tick after TeamTracker:
 * <ul>
 * <li>Experimental greying (attackRangeFilter): enemies out of range are drawn in the out-of-range
 * colour instead of their target/pile/snipe colour, and never get a prioritised Attack. Whose
 * bracket (attackRangeBasis): YOU - your combat level only, exact for your own clicks; TEAM - out
 * of range only if no teammate on screen can reach them either, since a team is usually a spread
 * of combat levels (e.g. 55-60) and a pile only some of you can reach is still a pile.</li>
 * <li>Highlight Others' "only in your level range" - non-team players you could attack, with
 * othersRangeBuffer levels of slack.</li>
 * </ul>
 * Only while you're in the wilderness, in Ferox Enclave, or on a PvP world - anywhere else (Clan
 * Wars) there's no bracket and nothing is filtered.
 *
 * Ferox Enclave is a safe zone inside the wilderness: the game hides the wilderness level there,
 * so you'd count as level 0 and nobody would be in range. Inside it you're given the level of the
 * ground you're standing on - who you could attack once you step out - and anyone standing inside
 * it can't be attacked. "Inside" is the Location Mapper's Ferox Enclave box (LocationTracker).
 * Client thread only.
 */
class AttackRangeFilter
{
	static final String SAFE_ZONE_NAME = "Ferox Enclave";

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;
	private final LocationTracker locations;

	private Set<Player> outOfRange = Collections.emptySet();
	private Set<Player> othersInRange = Collections.emptySet();
	private boolean bracketApplies;

	@Inject
	AttackRangeFilter(Client client, PrepToolsConfig config, TeamTracker team, LocationTracker locations)
	{
		this.client = client;
		this.config = config;
		this.team = team;
		this.locations = locations;
	}

	boolean isEnabled()
	{
		return config.attackRangeFilter();
	}

	/**
	 * False for anyone who appeared since the last tick, or while the filter is off.
	 */
	boolean isOutOfRange(Player player)
	{
		return outOfRange.contains(player);
	}

	/**
	 * Whether there was a combat bracket at all last tick (wilderness, Ferox Enclave, PvP world).
	 */
	boolean bracketApplies()
	{
		return bracketApplies;
	}

	/**
	 * Non-team players you could attack (with othersRangeBuffer slack), while Highlight Others is
	 * limited to your level range.
	 */
	Set<Player> getOthersInRange()
	{
		return othersInRange;
	}

	void clear()
	{
		outOfRange = Collections.emptySet();
		othersInRange = Collections.emptySet();
		bracketApplies = false;
	}

	void onGameTick()
	{
		Player local = client.getLocalPlayer();
		boolean filter = config.attackRangeFilter();
		boolean others = config.highlightOthers() && config.othersOnlyInRange();
		if ((!filter && !others) || local == null)
		{
			clear();
			return;
		}

		boolean pvpWorld = client.getWorldType().contains(WorldType.PVP);
		int localLevel = attackerWildernessLevel(local, local);
		bracketApplies = pvpWorld || localLevel > 0;
		if (!bracketApplies)
		{
			outOfRange = Collections.emptySet();
			othersInRange = Collections.emptySet();
			return;
		}

		List<Player> attackers = new ArrayList<>();
		List<Player> enemies = new ArrayList<>();
		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}
			if (player == local || (config.attackRangeBasis() == AttackRangeBasis.TEAM && team.isOwnTeam(player)))
			{
				attackers.add(player);
			}
			if (player != local && !team.isOwnTeam(player))
			{
				enemies.add(player);
			}
		}

		Set<Player> out = new HashSet<>();
		Set<Player> inRange = new HashSet<>();
		for (Player enemy : enemies)
		{
			int enemyLevel = targetWildernessLevel(enemy);
			// In Ferox Enclave: nobody can touch them, PvP world or not.
			boolean safe = enemyLevel < 0;
			if (filter && (safe || !canAnyAttack(attackers, local, enemy, enemyLevel, pvpWorld, config.attackRangeMargin())))
			{
				out.add(enemy);
			}
			if (others && !safe && AttackRange.canAttack(local.getCombatLevel(), localLevel, enemy.getCombatLevel(), enemyLevel,
				pvpWorld, config.othersRangeBuffer()))
			{
				inRange.add(enemy);
			}
		}
		outOfRange = filter ? out : Collections.emptySet();
		othersInRange = others ? inRange : Collections.emptySet();
	}

	private boolean canAnyAttack(List<Player> attackers, Player local, Player enemy, int enemyLevel, boolean pvpWorld, int margin)
	{
		for (Player attacker : attackers)
		{
			if (AttackRange.canAttack(attacker.getCombatLevel(), attackerWildernessLevel(attacker, local),
				enemy.getCombatLevel(), enemyLevel, pvpWorld, margin))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Someone on your side: the level of the ground they're on. For you, 0 outside the wilderness
	 * (the IN_WILDERNESS varbit) unless you're in Ferox Enclave, where the game says 0 but you're
	 * about to step out. Teammates only have coordinates, which already put Ferox in the wilderness.
	 */
	private int attackerWildernessLevel(Player player, Player local)
	{
		WorldPoint point = player.getWorldLocation();
		int level = AttackRange.wildernessLevel(point);
		if (player == local && client.getVarbitValue(Varbits.IN_WILDERNESS) != 1 && !isInSafeZone(point))
		{
			return 0;
		}
		return level;
	}

	/**
	 * Someone to attack: their ground's level, or -1 inside Ferox Enclave (can't be attacked).
	 */
	private int targetWildernessLevel(Player player)
	{
		WorldPoint point = player.getWorldLocation();
		return isInSafeZone(point) ? -1 : AttackRange.wildernessLevel(point);
	}

	private boolean isInSafeZone(WorldPoint point)
	{
		if (point == null)
		{
			return false;
		}
		List<WildernessLocation> matches = LocationTracker.findContaining(locations.getStore().getLocations(), point);
		return !matches.isEmpty() && SAFE_ZONE_NAME.equalsIgnoreCase(matches.get(0).name);
	}
}
