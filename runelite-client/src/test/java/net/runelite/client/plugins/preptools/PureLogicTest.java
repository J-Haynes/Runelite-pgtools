package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.menus.TestMenuEntry;
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

	private static PerformanceRow perf(String name, Long... deaths)
	{
		return new PerformanceRow(name, null, false, false, Arrays.asList(deaths), 0, 0, 0, 0);
	}

	@Test
	public void statsAreInOrderOfDeathWithTheLivingLast()
	{
		List<PerformanceRow> sorted = PerformanceRow.byDeath(Arrays.asList(
			perf("Zed"), perf("Late", 300L), perf("Abe"), perf("Early", 100L, 900L)));
		assertEquals(Arrays.asList("Early", "Late", "Abe", "Zed"),
			sorted.stream().map(r -> r.name).collect(Collectors.toList()));
	}

	@Test
	public void healingIsRoundedToBitesOnlyAtTheEnd()
	{
		assertEquals(1, FoodTracker.biteCount(9));
		assertEquals(2, FoodTracker.biteCount(18));
		assertEquals(2, FoodTracker.biteCount(14));
		assertEquals(0, FoodTracker.biteCount(4));
		// Rounding noise can leave the sum just under zero.
		assertEquals(0, FoodTracker.biteCount(-3));
		assertEquals(Double.NaN, FoodTracker.barFraction(-1, 30), 0);
		assertEquals(0.5, FoodTracker.barFraction(15, 30), 0);
	}

	@Test
	public void maxHpIsMeasuredOnceThereIsHalfABarOfHits()
	{
		assertNull(FoodTracker.measuredMaxHp(30, 0.4));
		assertEquals(Integer.valueOf(70), FoodTracker.measuredMaxHp(35, 0.5));
		// Clamped to real hitpoints levels.
		assertEquals(Integer.valueOf(99), FoodTracker.measuredMaxHp(200, 1.0));
		assertEquals(Integer.valueOf(10), FoodTracker.measuredMaxHp(2, 1.0));
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
	public void renamesArePairedWhenSure()
	{
		// Renamed twice while you were away: the game's previous name ("Middle") isn't one you
		// ever saw, but one left and one arrived.
		RenameMatch twice = RenameMatch.match(Arrays.asList("Old Name", "Stays"), Arrays.asList("Stays", "Newest"),
			Collections.singletonMap("Newest", "Middle"));
		assertEquals(Collections.singletonMap("Old Name", "Newest"), twice.renames);

		// Two left, two arrived: the game's previous name settles one, which leaves one each.
		Map<String, String> previous = new HashMap<>();
		previous.put("Bee2", "Bee");
		RenameMatch two = RenameMatch.match(Arrays.asList("Ay", "Bee", "Stays"), Arrays.asList("Stays", "Bee2", "Ay2"), previous);
		assertEquals("Bee2", two.renames.get("Bee"));
		assertEquals("Ay2", two.renames.get("Ay"));

		// Two each with nothing to go on: left for you to pair.
		RenameMatch unsure = RenameMatch.match(Arrays.asList("Ay", "Bee"), Arrays.asList("Cee", "Dee"), Collections.emptyMap());
		assertTrue(unsure.renames.isEmpty());
		assertEquals(Arrays.asList("Ay", "Bee"), unsure.unmatchedLeft);
		assertEquals(Arrays.asList("Cee", "Dee"), unsure.unmatchedArrived);

		// Names compare standardized (case, non-breaking space): nobody renamed.
		assertTrue(RenameMatch.match(Arrays.asList("big bob"), Arrays.asList("Big\u00A0Bob"), Collections.emptyMap()).renames.isEmpty());
	}

	@Test
	public void hoveredNameFromAWidgetOrAPlayer()
	{
		TestMenuEntry friend = new TestMenuEntry();
		friend.setType(MenuAction.CC_OP);
		friend.setParam1(net.runelite.api.gameval.InterfaceID.FRIENDS << 16 | 11);
		friend.setTarget("<col=ff9040>Big\u00A0Bob</col>");
		assertEquals("Big Bob", NicknameTooltipOverlay.hoveredName(friend));

		// Some other interface's entry isn't a player name.
		friend.setParam1(net.runelite.api.gameval.InterfaceID.INVENTORY << 16);
		assertNull(NicknameTooltipOverlay.hoveredName(friend));
	}

	@Test
	public void pileOrderSharesAPlaceForTeammatesOnInTheSameTick()
	{
		Map<String, Integer> starts = new java.util.HashMap<>();
		starts.put("a", 10);
		starts.put("b", 10);
		starts.put("c", 14);
		starts.put("d", 12);
		Map<String, Integer> places = TeamPerformanceTracker.pileOrder(starts);
		assertEquals(1, (int) places.get("a"));
		assertEquals(1, (int) places.get("b"));
		assertEquals(3, (int) places.get("d"));
		assertEquals(4, (int) places.get("c"));
	}

	@Test
	public void hitpointsColourAndLowEdges()
	{
		assertEquals(Color.GREEN, HitpointsOverlay.hitpointsColor(1));
		assertEquals(Color.RED, HitpointsOverlay.hitpointsColor(0));
		// Above the mark: nothing. Below: stronger the lower, never zero.
		assertEquals(0, LowHitpointsOverlay.edgeAlpha(60, 99, 50, 0));
		int justUnder = LowHitpointsOverlay.edgeAlpha(49, 99, 50, 0);
		int nearlyDead = LowHitpointsOverlay.edgeAlpha(5, 99, 50, 0);
		assertTrue(justUnder > 0);
		assertTrue(nearlyDead > justUnder);
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
