package net.runelite.client.plugins.preptools;

import java.util.Map;

/**
 * Combat style of a wielded weapon, for the infobox's opponent/"Attacking you" breakdowns and the
 * Clan Info tab. The weapon lists are deliberately limited to the handful of weapons the team's
 * ruleset allows - anything else is UNKNOWN.
 */
enum WeaponStyle
{
	MELEE,
	RANGED,
	MAGIC,
	UNKNOWN;

	private static final Map<Integer, WeaponStyle> BY_ITEM_ID = Map.ofEntries(
		Map.entry(1333, MELEE),  // Rune scimitar
		Map.entry(12389, MELEE), // Gilded scimitar
		Map.entry(23330, MELEE), // Rune scimitar (guthix)
		Map.entry(23332, MELEE), // Rune scimitar (saradomin)
		Map.entry(23334, MELEE), // Rune scimitar (zamorak)
		Map.entry(1347, MELEE),  // Rune warhammer
		Map.entry(1289, MELEE),  // Rune sword
		Map.entry(1319, MELEE),  // Rune 2h sword
		Map.entry(20756, MELEE), // Hill giant club
		Map.entry(853, RANGED),  // Maple shortbow
		Map.entry(1381, MAGIC),  // Staff of air
		Map.entry(1387, MAGIC),  // Staff of fire
		Map.entry(1385, MAGIC),  // Staff of earth
		Map.entry(1383, MAGIC)   // Staff of water
	);

	static WeaponStyle of(int itemId)
	{
		return BY_ITEM_ID.getOrDefault(itemId, UNKNOWN);
	}
}
