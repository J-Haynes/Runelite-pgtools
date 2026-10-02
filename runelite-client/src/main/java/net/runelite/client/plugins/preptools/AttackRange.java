package net.runelite.client.plugins.preptools;

import net.runelite.api.coords.WorldPoint;

/**
 * The wilderness combat bracket (OSRS wiki, Wilderness): two players can fight if their combat
 * levels differ by at most the wilderness level. A PvP world adds 15 (anywhere outside a safe zone
 * counts as level 15, and inside the Wilderness the two stack). Both players must be able to reach
 * each other, so the shallower of the two wilderness levels is the one that limits - you can't hit
 * someone standing at level 3 from level 20 if you're 10 combat levels apart.
 *
 * Pure logic - unit tested.
 */
final class AttackRange
{
	private static final int PVP_WORLD_BONUS = 15;

	private AttackRange()
	{
	}

	/**
	 * Wilderness level from coordinates alone - all we have for other players. 8 tiles per level
	 * from y=3520 on the surface or y=9920 underground; 0 outside the wilderness. Doesn't know about
	 * safe zones like Ferox Enclave.
	 */
	static int wildernessLevel(WorldPoint point)
	{
		if (point == null)
		{
			return 0;
		}
		int x = point.getX();
		int y = point.getY();
		if (x >= 2944 && x < 3392 && y >= 3520 && y < 3968)
		{
			return (y - 3520) / 8 + 1;
		}
		if (x >= 2944 && x < 3456 && y >= 9920 && y < 10432)
		{
			return (y - 9920) / 8 + 1;
		}
		return 0;
	}

	/**
	 * Whether two players can attack each other. margin widens the bracket by that many levels
	 * either way - slack for a fight drifting a level or two deeper.
	 */
	static boolean canAttack(int combatA, int wildA, int combatB, int wildB, boolean pvpWorld, int margin)
	{
		int bonus = pvpWorld ? PVP_WORLD_BONUS : 0;
		if (!pvpWorld && (wildA <= 0 || wildB <= 0))
		{
			return false;
		}
		int range = Math.min(Math.max(0, wildA), Math.max(0, wildB)) + bonus;
		return Math.abs(combatA - combatB) <= range + Math.max(0, margin);
	}
}
