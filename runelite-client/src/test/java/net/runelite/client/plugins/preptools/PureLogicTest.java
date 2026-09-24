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
}
