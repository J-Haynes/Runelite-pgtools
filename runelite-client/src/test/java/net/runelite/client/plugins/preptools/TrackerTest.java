package net.runelite.client.plugins.preptools;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GraphicID;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.SpotanimID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * TeamTracker, FreezeTracker and HighlightTracker against a mocked client.
 */
public class TrackerTest
{
	private Client client;
	private PrepToolsConfig config;
	private TeamTracker team;
	private int tick;

	private Player local;
	private Player mate;
	private Player enemyA;
	private Player enemyB;

	@Before
	public void before()
	{
		client = mock(Client.class);
		config = mock(PrepToolsConfig.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
		team = new TeamTracker(client);

		local = player("Me", false);
		mate = player("Mate", true);
		enemyA = player("Enemy A", false);
		enemyB = player("Enemy B", false);
		when(client.getLocalPlayer()).thenReturn(local);
		when(client.getTickCount()).thenAnswer(i -> tick);
		setPlayers(local, mate, enemyA, enemyB);
		team.refresh();
	}

	private static Player player(String name, boolean friendsChat)
	{
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		when(player.isFriendsChatMember()).thenReturn(friendsChat);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3600, 0));
		return player;
	}

	private void setPlayers(Player... players)
	{
		List<Player> list = Arrays.asList(players);
		when(client.getPlayers()).thenReturn(list);
	}

	@Test
	public void teamIsYouChatMembersAndCallers()
	{
		assertTrue(team.isOwnTeam(local));
		assertTrue(team.isOwnTeam(mate));
		assertFalse(team.isOwnTeam(enemyA));

		team.setConfiguredCallers(Collections.singleton("enemy a"));
		team.refresh();
		assertTrue(team.isOwnTeam(enemyA));
		// A caller isn't a chat member (Clan IDs lists chat members only).
		assertFalse(team.isChatMember(enemyA));
	}

	/**
	 * The player carries exactly these spot anims (hasSpotAnim), like a fresh impact graphic.
	 */
	private static void spotAnims(Player player, Integer... ids)
	{
		List<Integer> list = Arrays.asList(ids);
		when(player.hasSpotAnim(anyInt())).thenAnswer(i -> list.contains(i.<Integer>getArgument(0)));
	}

	@Test
	public void freezeIsNotRefreshedByAHitWhileFrozenOrImmune()
	{
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(enemyA, GraphicID.SNARE);

		assertFalse(freezes.onGraphicChanged(enemyA));
		FreezeState first = freezes.getFreezeStates().get(enemyA);
		// Snare is 16 ticks; the countdown shows one extra, immunity ends 16 + 5 after the hit.
		assertEquals(17, first.endTick);
		assertEquals(21, first.immunityEndTick);

		// A barrage lands mid-snare: the game doesn't refreeze, so neither do we.
		tick = 10;
		spotAnims(enemyA, GraphicID.ICE_BARRAGE);
		freezes.onGraphicChanged(enemyA);
		assertSame(first, freezes.getFreezeStates().get(enemyA));

		// Expires into immunity; a hit during immunity doesn't freeze either.
		tick = 17;
		freezes.onGameTick();
		assertTrue(first.immune);
		assertFalse(freezes.isFrozen(enemyA));
		tick = 20;
		freezes.onGraphicChanged(enemyA);
		assertSame(first, freezes.getFreezeStates().get(enemyA));
	}

	@Test
	public void refreezeOnTheFirstTickAfterImmunityCounts()
	{
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(enemyA, GraphicID.SNARE);
		freezes.onGraphicChanged(enemyA);
		tick = 17;
		freezes.onGameTick();

		// Tick 21: immunity is over, and the graphic arrives before this tick's onGameTick has
		// dropped the old entry - it must still count as a new freeze.
		tick = 21;
		spotAnims(enemyA, GraphicID.ICE_BARRAGE);
		freezes.onGraphicChanged(enemyA);
		assertEquals(21 + 33, freezes.getFreezeStates().get(enemyA).endTick);
		assertTrue(freezes.isFrozen(enemyA));

		freezes.onGameTick();
		assertTrue(freezes.isFrozen(enemyA));
	}

	@Test
	public void freezeSeenAlongsideAnotherSpotAnim()
	{
		when(config.showTeleblockTimers()).thenReturn(true);
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		// A barrage and a teleblock landing together - getGraphic() would only report one.
		spotAnims(enemyA, SpotanimID.TELE_BLOCK_IMPACT, GraphicID.ICE_BARRAGE);

		freezes.onGraphicChanged(enemyA);

		assertEquals(33, freezes.getFreezeStates().get(enemyA).endTick);
		assertTrue(freezes.getTeleblockStates().containsKey(enemyA));
	}

	@Test
	public void freezesAreTrackedWhateverTheyShow()
	{
		when(config.showFreezeTimers()).thenReturn(false);
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(enemyA, GraphicID.ICE_BARRAGE);
		freezes.onGraphicChanged(enemyA);
		assertTrue(freezes.isFrozen(enemyA));
		assertFalse(freezes.isFreezeShown(enemyA));

		// "Hide for own team" hides a teammate's timer, never yours.
		when(config.showFreezeTimers()).thenReturn(true);
		when(config.hideFreezeTimersForOwnTeam()).thenReturn(true);
		assertTrue(freezes.isFreezeShown(enemyA));
		assertFalse(freezes.isFreezeShown(mate));
		assertTrue(freezes.isFreezeShown(local));
	}

	@Test
	public void yourFreezeIsTrackedEvenWithFreezeTimersOff()
	{
		when(config.showFreezeTimers()).thenReturn(false);
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(local, GraphicID.ICE_RUSH);

		assertTrue(freezes.onGraphicChanged(local));
		assertTrue(freezes.isLocalFrozen());
		// A second hit while frozen isn't a new freeze.
		assertFalse(freezes.onGraphicChanged(local));
	}

	@Test
	public void movingAfterTheGraceTicksEndsAFreeze()
	{
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(local, GraphicID.ICE_BARRAGE);
		freezes.onGraphicChanged(local);

		// A step queued before the freeze, during the grace ticks, doesn't count.
		tick = 1;
		when(local.getWorldLocation()).thenReturn(new WorldPoint(3201, 3600, 0));
		freezes.onGameTick();
		assertTrue(freezes.isLocalFrozen());

		tick = 4;
		when(local.getWorldLocation()).thenReturn(new WorldPoint(3202, 3600, 0));
		freezes.onGameTick();
		assertNull(freezes.getLocalFreeze());
	}

	@Test
	public void deathEndsFreezes()
	{
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(local, GraphicID.BIND);
		freezes.onGraphicChanged(local);
		freezes.forget(local);
		assertNull(freezes.getLocalFreeze());
	}

	@Test
	public void despawnedPlayerLosesFreezeAndTeleblock()
	{
		when(config.showTeleblockTimers()).thenReturn(true);
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(enemyA, GraphicID.BIND, SpotanimID.TELE_BLOCK_IMPACT);
		freezes.onGraphicChanged(enemyA);
		assertTrue(freezes.isFrozen(enemyA));
		assertTrue(freezes.getTeleblockStates().containsKey(enemyA));

		// Logged out (PlayerDespawned).
		freezes.forget(enemyA);
		assertFalse(freezes.getFreezeStates().containsKey(enemyA));
		assertFalse(freezes.getTeleblockStates().containsKey(enemyA));
	}

	@Test
	public void frozenTargetAndPileGetTheirFrozenColour()
	{
		when(config.enableTargetedPlayerDetection()).thenReturn(true);
		when(config.targetedPlayerThreshold()).thenReturn(2);
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		HighlightTracker highlights = new HighlightTracker(client, config, team, freezes, new AttackRangeFilter(client, config, team));
		when(local.getInteracting()).thenReturn(enemyA);
		when(mate.getInteracting()).thenReturn(enemyA);

		highlights.onGameTick();
		assertEquals(config.targetedPlayerColor(), highlights.getStyles().get(enemyA).color);

		spotAnims(enemyA, GraphicID.ICE_BARRAGE);
		freezes.onGraphicChanged(enemyA);
		highlights.onGameTick();
		assertEquals(config.frozenPileColor(), highlights.getStyles().get(enemyA).color);

		// Immune isn't frozen - back to the normal colour.
		tick = 33;
		freezes.onGameTick();
		highlights.onGameTick();
		assertEquals(config.targetedPlayerColor(), highlights.getStyles().get(enemyA).color);
	}

	@Test
	public void pileNeedsEnoughTeammatesAndNeverATeammate()
	{
		when(config.enableTargetedPlayerDetection()).thenReturn(true);
		when(config.targetedPlayerThreshold()).thenReturn(2);
		HighlightTracker highlights = new HighlightTracker(client, config, team, new FreezeTracker(client, config, team), new AttackRangeFilter(client, config, team));

		when(local.getInteracting()).thenReturn(enemyA);
		when(mate.getInteracting()).thenReturn(enemyA);
		// Enemies piling each other, and an enemy on a teammate, never count.
		when(enemyB.getInteracting()).thenReturn(mate);
		highlights.onGameTick();
		assertEquals(Collections.singleton(enemyA), highlights.getPiledPlayers());

		// Everyone following a teammate (a fall-in) isn't a pile.
		when(local.getInteracting()).thenReturn(mate);
		when(mate.getInteracting()).thenReturn(null);
		highlights.onGameTick();
		assertTrue(highlights.getPiledPlayers().isEmpty());
	}

	@Test
	public void callerFollowingATeammateHasNoTarget()
	{
		HighlightTracker highlights = new HighlightTracker(client, config, team, new FreezeTracker(client, config, team), new AttackRangeFilter(client, config, team));
		highlights.setCallers(Collections.singletonList(new CallerEntry("Mate", "M", true)));
		team.setConfiguredCallers(Collections.singleton("mate"));
		team.refresh();

		when(mate.getInteracting()).thenReturn(local);
		highlights.onGameTick();
		assertEquals(mate, highlights.getPrimaryCaller());
		assertTrue(highlights.getTargets().isEmpty());

		when(mate.getInteracting()).thenReturn(enemyA);
		highlights.onGameTick();
		assertEquals(Collections.singleton(enemyA), highlights.getTargets());
		assertEquals("M", highlights.getCallerNicknames().get(mate));
	}

	@Test
	public void attackersCapKeepsWhoeverEngagedFirst()
	{
		when(config.highlightAttackers()).thenReturn(true);
		when(config.maxAttackers()).thenReturn(1);
		HighlightTracker highlights = new HighlightTracker(client, config, team, new FreezeTracker(client, config, team), new AttackRangeFilter(client, config, team));

		when(enemyB.getInteracting()).thenReturn(local);
		highlights.onGameTick();
		when(enemyA.getInteracting()).thenReturn(local);
		highlights.onGameTick();
		assertEquals(Collections.singleton(enemyB), highlights.getAttackers());

		// A teammate interacting with you (following) is never an attacker.
		when(mate.getInteracting()).thenReturn(local);
		when(config.maxAttackers()).thenReturn(0);
		highlights.onGameTick();
		assertEquals(2, highlights.getAttackers().size());
		assertFalse(highlights.getAttackers().contains(mate));
	}

	@Test
	public void outOfRangeTargetIsGreyedAndInRangeKeepsItsColour()
	{
		when(config.attackRangeFilter()).thenReturn(true);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(1);
		// Everyone's at level 11 wilderness (y=3600).
		when(local.getCombatLevel()).thenReturn(55);
		when(mate.getCombatLevel()).thenReturn(60);
		when(enemyA.getCombatLevel()).thenReturn(70);
		when(enemyB.getCombatLevel()).thenReturn(64);

		AttackRangeFilter range = new AttackRangeFilter(client, config, team);
		HighlightTracker highlights = new HighlightTracker(client, config, team, new FreezeTracker(client, config, team), range);
		highlights.setSnipeNames(Arrays.asList("Enemy A", "Enemy B"));

		range.onGameTick();
		highlights.onGameTick();
		// 70 is 15 away from 55: out of range. 64 is 9 away: in.
		assertEquals(config.outOfRangeColor(), highlights.getStyles().get(enemyA).color);
		assertEquals(config.snipeColor(), highlights.getStyles().get(enemyB).color);

		// Team basis: the 60 teammate is 10 from 70 - in range for them, so not greyed.
		when(config.attackRangeBasis()).thenReturn(AttackRangeBasis.TEAM);
		range.onGameTick();
		assertFalse(range.isOutOfRange(enemyA));

		// Four levels of slack brings 70 within 55's reach: 11 + 4 = 15.
		when(config.attackRangeBasis()).thenReturn(AttackRangeBasis.YOU);
		when(config.attackRangeMargin()).thenReturn(4);
		range.onGameTick();
		assertFalse(range.isOutOfRange(enemyA));
	}

	@Test
	public void outsideTheWildernessNobodyIsGreyed()
	{
		// Clan Wars, Ferox Enclave: no bracket at all - greying everyone would hide who's who.
		when(config.attackRangeFilter()).thenReturn(true);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(0);
		when(local.getCombatLevel()).thenReturn(60);
		when(enemyA.getCombatLevel()).thenReturn(126);

		AttackRangeFilter range = new AttackRangeFilter(client, config, team);
		range.onGameTick();
		assertFalse(range.isOutOfRange(enemyA));
	}

	private static void animate(Player player, int animation)
	{
		when(player.getAnimation()).thenReturn(animation);
	}

	/**
	 * Health bar of 30 steps at the given hp out of 99.
	 */
	private static void hp(Player player, int hp)
	{
		when(player.getHealthScale()).thenReturn(30);
		when(player.getHealthRatio()).thenReturn(Math.round(hp * 30f / 99));
	}

	private Hitsplat damage(int amount)
	{
		Hitsplat hit = mock(Hitsplat.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
		when(hit.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_OTHER);
		when(hit.getAmount()).thenReturn(amount);
		return hit;
	}

	private FoodTracker food()
	{
		when(config.showFoodCounter()).thenReturn(true);
		return new FoodTracker(client, config, team);
	}

	@Test
	public void foodIsCountedFromTheHealNotTheAnimation()
	{
		FoodTracker food = food();
		hp(enemyA, 50);
		food.onGameTick();

		// One animation, two pizza bites: +18.
		tick = 1;
		animate(enemyA, AnimationID.HUMAN_EAT);
		food.onAnimationChanged(enemyA);
		hp(enemyA, 68);
		food.onGameTick();
		assertEquals(2, food.getBites(enemyA));

		// A potion: same animation, no heal - nothing.
		tick = 5;
		food.onAnimationChanged(enemyA);
		for (tick = 5; tick <= 8; tick++)
		{
			food.onGameTick();
		}
		assertEquals(2, food.getBites(enemyA));
		assertEquals(0, food.getUnmeasuredEats(enemyA));

		// One bite while taking a 20 hit the same tick: 68 - 20 + 9 = 57.
		tick = 10;
		food.onAnimationChanged(enemyA);
		food.onHitsplatApplied(enemyA, damage(20));
		hp(enemyA, 57);
		food.onGameTick();
		assertEquals(3, food.getBites(enemyA));
	}

	@Test
	public void healingWithoutAnEatIsNotFood()
	{
		// e.g. a blood barrage.
		FoodTracker food = food();
		hp(enemyA, 40);
		food.onGameTick();
		tick = 1;
		hp(enemyA, 70);
		food.onGameTick();
		assertEquals(0, food.getBites(enemyA));
	}

	@Test
	public void anEatWithNoHealthBarIsUnmeasured()
	{
		FoodTracker food = food();
		when(enemyA.getHealthRatio()).thenReturn(-1);
		food.onGameTick();
		tick = 1;
		animate(enemyA, AnimationID.HUMAN_EAT);
		food.onAnimationChanged(enemyA);
		for (tick = 1; tick <= 4; tick++)
		{
			food.onGameTick();
		}
		assertEquals(0, food.getBites(enemyA));
		assertEquals(1, food.getUnmeasuredEats(enemyA));
	}

	/**
	 * Enemy A eats one bite on tick 1.
	 */
	private FoodTracker foodWithOneBite()
	{
		FoodTracker food = food();
		hp(enemyA, 50);
		food.onGameTick();
		tick = 1;
		animate(enemyA, AnimationID.HUMAN_EAT);
		food.onAnimationChanged(enemyA);
		hp(enemyA, 59);
		food.onGameTick();
		assertEquals(1, food.getBites(enemyA));
		return food;
	}

	@Test
	public void foodCountResetsOnDeath()
	{
		// Clan Wars: a dead player is out, and comes back next round with full food.
		FoodTracker food = foodWithOneBite();
		food.reset(enemyA);
		assertEquals(0, food.getBites(enemyA));
		assertFalse(food.hasCount(enemyA));
	}

	@Test
	public void enteringAnInstanceResetsEveryone()
	{
		WorldView worldView = mock(WorldView.class);
		when(client.getTopLevelWorldView()).thenReturn(worldView);
		FoodTracker food = foodWithOneBite();

		// Already in an instance: nothing happens on later ticks.
		tick = 2;
		food.onGameTick();
		assertEquals(1, food.getBites(enemyA));

		// Through the Clan Wars portal.
		when(worldView.isInstance()).thenReturn(true);
		tick = 3;
		food.onGameTick();
		assertEquals(0, food.getBites(enemyA));
	}

	@Test
	public void foodCountsSurviveLeavingViewAndAreForgottenWhenNotSeen()
	{
		when(config.foodForgetMinutes()).thenReturn(1);
		FoodTracker food = foodWithOneBite();

		food.forget(enemyA);
		assertEquals(1, food.getBites(enemyA));

		tick = 150;
		food.onGameTick();
		assertEquals(1, food.getBites(enemyA));

		setPlayers(local, mate, enemyB);
		tick = 251;
		food.onGameTick();
		assertEquals(0, food.getBites(enemyA));
	}

	@Test
	public void teammatesFoodIsNotTracked()
	{
		FoodTracker food = food();
		hp(mate, 50);
		food.onGameTick();
		tick = 1;
		animate(mate, AnimationID.HUMAN_EAT);
		food.onAnimationChanged(mate);
		hp(mate, 59);
		food.onGameTick();
		assertEquals(0, food.getBites(mate));
	}

	private TeamPerformanceTracker performance()
	{
		when(config.enablePerformanceTracker()).thenReturn(true);
		return new TeamPerformanceTracker(client, config, team);
	}

	private static PerformanceRow row(List<PerformanceRow> rows, String name)
	{
		return rows.stream().filter(r -> r.name.equals(name)).findFirst().orElseThrow(AssertionError::new);
	}

	@Test
	public void performanceTracksTimeOnTargetAndSwitches()
	{
		TeamPerformanceTracker performance = performance();
		Set<Player> targets = Collections.singleton(enemyA);

		// Tick 0: target called. Mate is already on it, you aren't.
		when(mate.getInteracting()).thenReturn(enemyA);
		performance.onGameTick(null, targets);
		// Ticks 1-2: you're still elsewhere; tick 3 you switch.
		when(local.getInteracting()).thenReturn(enemyB);
		tick = 1;
		performance.onGameTick(null, targets);
		tick = 2;
		performance.onGameTick(null, targets);
		when(local.getInteracting()).thenReturn(enemyA);
		tick = 3;
		performance.onGameTick(null, targets);

		List<PerformanceRow> rows = performance.snapshot();
		PerformanceRow me = row(rows, "Me");
		PerformanceRow mateRow = row(rows, "Mate");
		assertEquals(4, me.targetTicks);
		assertEquals(1, me.onTargetTicks);
		assertEquals(1, me.switches);
		assertEquals(3.0, me.averageSwitchTicks(), 0.001);
		assertEquals(100, mateRow.onTargetPercent());
		assertEquals(0.0, mateRow.averageSwitchTicks(), 0.001);
		assertTrue(me.isYou);
	}

	@Test
	public void aSlowSwitchIsStillMeasuredAndAnUnreachedTargetIsNotAMiss()
	{
		TeamPerformanceTracker performance = performance();
		when(local.getInteracting()).thenReturn(enemyB);

		// Mate never reaches enemyA before it's re-called - no measurement, no penalty.
		performance.onGameTick(null, Collections.singleton(enemyA));
		tick = 20;
		performance.onGameTick(null, Collections.singleton(enemyB));
		assertEquals(0, row(performance.snapshot(), "Mate").switches);

		// Mate takes 30 ticks to get onto enemyB - slow, but counted.
		tick = 50;
		when(mate.getInteracting()).thenReturn(enemyB);
		performance.onGameTick(null, Collections.singleton(enemyB));
		assertEquals(30.0, row(performance.snapshot(), "Mate").averageSwitchTicks(), 0.001);
	}

	@Test
	public void performanceCountsAttacksEatsDamageAndKills()
	{
		TeamPerformanceTracker performance = performance();
		when(mate.getInteracting()).thenReturn(enemyA);
		performance.onGameTick(null, Collections.singleton(enemyA));

		animate(mate, 7855);
		performance.onAnimationChanged(mate);
		animate(mate, AnimationID.HUMAN_SHIELD_DEFENCE);
		performance.onAnimationChanged(mate);
		animate(mate, AnimationID.HUMAN_EAT);
		performance.onAnimationChanged(mate);
		// Enemies' animations aren't tracked.
		animate(enemyA, 7855);
		performance.onAnimationChanged(enemyA);

		Hitsplat hit = mock(Hitsplat.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
		when(hit.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_OTHER);
		when(hit.getAmount()).thenReturn(25);
		performance.onHitsplatApplied(mate, hit);

		Hitsplat mine = mock(Hitsplat.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
		when(mine.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_ME);
		when(mine.getAmount()).thenReturn(30);
		performance.onHitsplatApplied(enemyA, mine);

		// Death animation and ActorDeath both report it - one kill.
		tick = 3;
		performance.onDeath(enemyA);
		performance.onDeath(enemyA);

		PerformanceRow mateRow = row(performance.snapshot(), "Mate");
		assertEquals(1, mateRow.attacks);
		assertEquals(1, mateRow.attacksOnTarget);
		assertEquals(1, mateRow.eats);
		assertEquals(25, mateRow.damageTaken);
		assertEquals(1, mateRow.killsInvolved);
		assertEquals(-1, mateRow.damageDealt);
		assertEquals(30, row(performance.snapshot(), "Me").damageDealt);
		// You weren't on enemyA.
		assertEquals(0, row(performance.snapshot(), "Me").killsInvolved);
	}
}
