package net.runelite.client.plugins.preptools;

import java.util.Map;

/**
 * Combat style of a wielded weapon, for the infobox's opponent/"Attacking you" breakdowns and the
 * Clan Info tab. The weapon lists are deliberately limited to the handful of weapons the team's
 * ruleset allows - anything else is UNKNOWN. A weapon's other wieldable IDs (charged/uncharged,
 * cosmetic and Last Man Standing copies - see item_variations.json) must be listed too: the game
 * reports the exact item ID worn, so a missing variant silently counts as UNKNOWN.
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
		Map.entry(20402, MELEE), // Rune scimitar (Last Man Standing)
		Map.entry(26262, MELEE), // Rune scimitar (Halloween 2021)
		Map.entry(1347, MELEE),  // Rune warhammer
		Map.entry(1289, MELEE),  // Rune sword
		Map.entry(1319, MELEE),  // Rune 2h sword
		Map.entry(20555, MELEE), // Rune 2h sword (Last Man Standing)
		Map.entry(20756, MELEE), // Hill giant club
		Map.entry(853, RANGED),  // Maple shortbow
		Map.entry(20403, RANGED), // Maple shortbow (Last Man Standing)
		Map.entry(1381, MAGIC),  // Staff of air
		Map.entry(1387, MAGIC),  // Staff of fire
		Map.entry(1385, MAGIC),  // Staff of earth
		Map.entry(1383, MAGIC),  // Staff of water
		Map.entry(22368, MAGIC), // Bryophyta's staff (uncharged)
		Map.entry(22370, MAGIC)  // Bryophyta's staff (charged)
	);

	static WeaponStyle of(int itemId)
	{
		return BY_ITEM_ID.getOrDefault(itemId, UNKNOWN);
	}
}
