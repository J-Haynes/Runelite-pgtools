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

/**
 * Experimental: enemies outside the combat bracket (AttackRange) for the current wilderness level.
 * They're drawn in the out-of-range colour instead of their target/pile/snipe colour, and never
 * get a prioritised left-click Attack. Only while you're in the wilderness or on a PvP world -
 * anywhere else (Clan Wars, Ferox Enclave) it does nothing.
 *
 * Whose bracket (attackRangeBasis): YOU - only your own combat level, exact for your own clicks.
 * TEAM - out of range only if no teammate on screen can attack them either, since a team is
 * usually a spread of combat levels (e.g. 55-60) and a pile on someone only some of you can reach
 * is still a pile. Recomputed once per tick, after TeamTracker. Client thread only.
 */
class AttackRangeFilter
{
	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	private Set<Player> outOfRange = Collections.emptySet();

	@Inject
	AttackRangeFilter(Client client, PrepToolsConfig config, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.team = team;
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

	void clear()
	{
		outOfRange = Collections.emptySet();
	}

	void onGameTick()
	{
		Player local = client.getLocalPlayer();
		if (!config.attackRangeFilter() || local == null)
		{
			outOfRange = Collections.emptySet();
			return;
		}

		boolean pvpWorld = client.getWorldType().contains(WorldType.PVP);
		// Outside the wilderness (Clan Wars, Ferox Enclave) the bracket doesn't apply - greying
		// everyone there would only hide who's who.
		if (!pvpWorld && wildernessLevelOf(local, local) <= 0)
		{
			outOfRange = Collections.emptySet();
			return;
		}
		int margin = config.attackRangeMargin();

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
			else if (!team.isOwnTeam(player))
			{
				enemies.add(player);
			}
		}

		Set<Player> result = new HashSet<>();
		for (Player enemy : enemies)
		{
			int enemyWild = AttackRange.wildernessLevel(enemy.getWorldLocation());
			boolean reachable = false;
			for (Player attacker : attackers)
			{
				if (AttackRange.canAttack(attacker.getCombatLevel(), wildernessLevelOf(attacker, local),
					enemy.getCombatLevel(), enemyWild, pvpWorld, margin))
				{
					reachable = true;
					break;
				}
			}
			if (!reachable)
			{
				result.add(enemy);
			}
		}
		outOfRange = result;
	}

	/**
	 * Your own level also knows about safe zones: in Ferox Enclave the IN_WILDERNESS varbit is off
	 * and you can't attack anyone. Teammates only have coordinates.
	 */
	private int wildernessLevelOf(Player player, Player local)
	{
		int level = AttackRange.wildernessLevel(player.getWorldLocation());
		if (player == local && !client.getWorldType().contains(WorldType.PVP)
			&& client.getVarbitValue(Varbits.IN_WILDERNESS) != 1)
		{
			return 0;
		}
		return level;
	}
}
