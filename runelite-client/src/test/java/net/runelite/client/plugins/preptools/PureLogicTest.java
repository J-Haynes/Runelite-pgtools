package net.runelite.client.plugins.preptools;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PureLogicTest
{
	private static Map<AttackCategory, Integer> indices(int target, int pile, int snipe, int attacker)
	{
		Map<AttackCategory, Integer> map = new EnumMap<>(AttackCategory.class);
		map.put(AttackCategory.TARGET, target);
		map.put(AttackCategory.PILE, pile);
		map.put(AttackCategory.SNIPE, snipe);
		map.put(AttackCategory.ATTACKER, attacker);
		return map;
	}

	@Test
	public void attackPriorityFollowsConfiguredOrder()
	{
		List<AttackCategory> order = Arrays.asList(AttackCategory.PILE, AttackCategory.TARGET, AttackCategory.SNIPE, AttackCategory.ATTACKER);
		assertEquals(2, AttackCategory.resolve(order, indices(1, 2, 3, 4)));
		assertEquals(1, AttackCategory.resolve(order, indices(1, -1, 3, 4)));
		assertEquals(-1, AttackCategory.resolve(order, indices(-1, -1, -1, -1)));
	}

	@Test
	public void attackPriorityChecksAnUnpickedCategoryLast()
	{
		// ATTACKER isn't picked (TARGET picked twice) but must still win when it's all there is.
		List<AttackCategory> order = Arrays.asList(AttackCategory.TARGET, AttackCategory.TARGET, AttackCategory.PILE, AttackCategory.SNIPE);
		assertEquals(4, AttackCategory.resolve(order, indices(-1, -1, -1, 4)));
		assertEquals(3, AttackCategory.resolve(order, indices(-1, -1, 3, 4)));
	}

	@Test
	public void weaponStyles()
	{
		assertEquals(WeaponStyle.MELEE, WeaponStyle.of(1333));
		assertEquals(WeaponStyle.RANGED, WeaponStyle.of(853));
		assertEquals(WeaponStyle.MAGIC, WeaponStyle.of(1381));
		// Bryophyta's staff, uncharged and charged - the game reports the exact variant worn.
		assertEquals(WeaponStyle.MAGIC, WeaponStyle.of(22368));
		assertEquals(WeaponStyle.MAGIC, WeaponStyle.of(22370));
		assertEquals(WeaponStyle.UNKNOWN, WeaponStyle.of(-1));
		assertEquals(WeaponStyle.UNKNOWN, WeaponStyle.of(4151));
	}

	@Test
	public void locationsResolveByPriorityThenSizeThenOrder()
	{
		WildernessLocation big = new WildernessLocation("Big", 0, 0, 100, 100, 0, 0);
		WildernessLocation small = new WildernessLocation("Small", 10, 10, 20, 20, 0, 0);
		WildernessLocation smallTwin = new WildernessLocation("SmallTwin", 10, 10, 20, 20, 0, 0);
		WildernessLocation priority = new WildernessLocation("Priority", 0, 0, 200, 200, 0, 1);
		WildernessLocation otherPlane = new WildernessLocation("Upstairs", 0, 0, 100, 100, 1, 5);

		List<WildernessLocation> matches = LocationTracker.findContaining(
			Arrays.asList(big, small, smallTwin, priority, otherPlane), new WorldPoint(15, 15, 0));

		assertEquals(Arrays.asList(priority, small, smallTwin, big), matches);
		assertTrue(LocationTracker.findContaining(Arrays.asList(small), new WorldPoint(50, 50, 0)).isEmpty());
	}

	@Test
	public void freezeSpellMenuParsing()
	{
		assertEquals("Snare", MenuHandler.spellNameFromTarget("<col=00ff00>Snare</col><col=ffffff> -> <col=ffffff>Bob<col=ff00>  (level-92)"));
		assertNull(MenuHandler.spellNameFromTarget("<col=ffffff>Bob"));
		assertNull(MenuHandler.spellNameFromTarget(null));
		assertTrue(MenuHandler.isFreezeSpellName("Ice Barrage"));
		assertTrue(MenuHandler.isFreezeSpellName("snare"));
		assertFalse(MenuHandler.isFreezeSpellName("Fire Blast"));
		assertFalse(MenuHandler.isFreezeSpellName(null));
		assertEquals("Attack", MenuHandler.stripTags("<col=ff0000>Attack</col>"));
	}

	@Test
	public void wildernessLevelFromCoordinates()
	{
		assertEquals(1, AttackRange.wildernessLevel(new WorldPoint(3100, 3520, 0)));
		assertEquals(11, AttackRange.wildernessLevel(new WorldPoint(3200, 3600, 0)));
		assertEquals(0, AttackRange.wildernessLevel(new WorldPoint(3200, 3519, 0)));
		// Underground (revenant caves etc.).
		assertEquals(17, AttackRange.wildernessLevel(new WorldPoint(3200, 10050, 0)));
		assertEquals(0, AttackRange.wildernessLevel(null));
	}

	@Test
	public void combatBracketUsesTheShallowerWildernessLevel()
	{
		assertTrue(AttackRange.canAttack(100, 24, 76, 24, false, 0));
		assertFalse(AttackRange.canAttack(100, 24, 75, 24, false, 0));
		// They're at level 3: only 3 levels apart can fight, however deep you are.
		assertFalse(AttackRange.canAttack(60, 20, 55, 3, false, 0));
		assertTrue(AttackRange.canAttack(60, 20, 55, 3, false, 2));
		// Outside the wilderness nobody can fight on a normal world...
		assertFalse(AttackRange.canAttack(60, 0, 60, 10, false, 5));
		// ...but a PvP world counts as level 15 there, and adds 15 inside.
		assertTrue(AttackRange.canAttack(60, 0, 75, 0, true, 0));
		assertTrue(AttackRange.canAttack(60, 20, 95, 20, true, 0));
		assertFalse(AttackRange.canAttack(60, 20, 96, 20, true, 0));
	}

	@Test
	public void lowFoodIsPizzasLeftAtOrBelowTheThreshold()
	{
		assertEquals(14, FoodTracker.pizzasLeft(0, 14));
		// A half pizza left is still one to eat.
		assertEquals(14, FoodTracker.pizzasLeft(1, 14));
		assertEquals(13, FoodTracker.pizzasLeft(2, 14));
		assertEquals(4, FoodTracker.pizzasLeft(21, 14));
		assertEquals(3, FoodTracker.pizzasLeft(22, 14));
		assertEquals(0, FoodTracker.pizzasLeft(40, 14));
		assertFalse(FoodTracker.isLow(21, 14, 3));
		assertTrue(FoodTracker.isLow(22, 14, 3));
		// No eats seen: unknown, not low - even with a threshold above the assumed inventory.
		assertFalse(FoodTracker.isLow(0, 2, 5));
	}

	private static PerformanceRow perf(String name, boolean caller, int targetTicks, int onTarget)
	{
		return new PerformanceRow(name, false, caller, targetTicks, targetTicks, onTarget, 0, 0, 0, 0, 0, -1, 0, 0, 0,
			PerformanceRow.Rating.UNRATED);
	}

	@Test
	public void performanceIsRatedAgainstTheTeamMedian()
	{
		List<PerformanceRow> rated = PerformanceRow.rate(Arrays.asList(
			perf("Low", false, 100, 40),
			perf("Mid", false, 100, 70),
			perf("High", false, 100, 90),
			perf("Mid2", false, 100, 72),
			perf("New", false, 10, 0),
			perf("Caller", true, 100, 100)), 50);

		assertEquals("High", rated.get(0).name);
		assertEquals(PerformanceRow.Rating.HIGH, rated.get(0).rating);
		assertEquals(PerformanceRow.Rating.LOW, rated.get(3).rating);
		assertEquals("Low", rated.get(3).name);
		// Not enough time yet, and the caller, are never rated - and sort last.
		assertEquals(PerformanceRow.Rating.UNRATED, rated.get(4).rating);
		assertEquals(PerformanceRow.Rating.UNRATED, rated.get(5).rating);

		// Fewer than three rateable players: no median to compare against.
		assertTrue(PerformanceRow.rate(Arrays.asList(perf("A", false, 100, 10), perf("B", false, 100, 90)), 50)
			.stream().allMatch(r -> r.rating == PerformanceRow.Rating.UNRATED));
	}

	@Test
	public void bitesAreCountedFromHealing()
	{
		assertEquals(1, FoodTracker.biteCount(50, 59, 0));
		assertEquals(2, FoodTracker.biteCount(50, 68, 0));
		// Health bar rounding: +-3 hp either way still reads the right number of bites.
		assertEquals(1, FoodTracker.biteCount(50, 56.7, 0));
		assertEquals(2, FoodTracker.biteCount(50, 64.7, 0));
		// Damage the same tick hides the heal unless it's added back.
		assertEquals(1, FoodTracker.biteCount(50, 39, 20));
		// Noise, a potion, or no health bar.
		assertEquals(0, FoodTracker.biteCount(50, 53.3, 0));
		assertEquals(0, FoodTracker.biteCount(Double.NaN, 70, 0));
		assertEquals(Double.NaN, FoodTracker.estimateHp(-1, 30, 99), 0);
		assertEquals(49.5, FoodTracker.estimateHp(15, 30, 99), 0.001);
	}

	@Test
	public void lookalikeNames()
	{
		// Lookalike characters and separators.
		assertTrue(NameLookalike.isLookalike("Il Zezima", "1l_Zezima"));
		assertTrue(NameLookalike.isLookalike("Modern", "Modem"));
		assertTrue(NameLookalike.isLookalike("B0ss", "Boss"));
		// One letter off.
		assertTrue(NameLookalike.isLookalike("Zezima", "Zezimaa"));
		assertTrue(NameLookalike.isLookalike("Zezima", "Zezlma"));
		// The same name (case, non-breaking space) isn't a lookalike.
		assertFalse(NameLookalike.isLookalike("Big Bob", "big\u00A0bob"));
		// Short names and real differences.
		assertFalse(NameLookalike.isLookalike("Bob", "Rob"));
		assertFalse(NameLookalike.isLookalike("Zezima", "Woox"));
		assertFalse(NameLookalike.isLookalike("Zezima", "Zezxyz"));
	}

	@Test
	public void friendNoteNicknameIsTheFirstLineCutShort()
	{
		assertEquals("Tank", PrepToolsPlugin.friendNoteNickname("  Tank \nmains a dds, PKs at night"));
		assertEquals("Their main is Zezima..", PrepToolsPlugin.friendNoteNickname("Their main is Zezima and alt is Bob"));
		assertNull(PrepToolsPlugin.friendNoteNickname("   "));
		assertNull(PrepToolsPlugin.friendNoteNickname(null));
	}

	@Test
	public void playerNicknamesRoundTrip()
	{
		Map<String, String> nicknames = ConfigLists.parsePlayerNicknames("Zezima=Tank\nbroken line\nB0ss=The = Boss\nEmpty=\n");
		assertEquals(2, nicknames.size());
		assertEquals("Tank", nicknames.get("Zezima"));
		assertEquals("The = Boss", nicknames.get("B0ss"));
		assertEquals(nicknames, ConfigLists.parsePlayerNicknames(ConfigLists.serializePlayerNicknames(nicknames)));
	}
}
