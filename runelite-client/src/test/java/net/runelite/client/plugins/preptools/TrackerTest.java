package net.runelite.client.plugins.preptools;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Friend;
import net.runelite.api.FriendContainer;
import net.runelite.api.GraphicID;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

	/**
	 * The shipped map's Ferox Enclave box (wilderness levels 13-16).
	 */
	private static final WildernessLocation FEROX = new WildernessLocation(AttackRangeFilter.SAFE_ZONE_NAME, 3123, 3617, 3160, 3643, 0);
	private List<WildernessLocation> locationList = Collections.singletonList(FEROX);

	private AttackRangeFilter range()
	{
		LocationTracker locations = mock(LocationTracker.class);
		WildernessLocationStore store = mock(WildernessLocationStore.class);
		when(locations.getStore()).thenReturn(store);
		when(store.getLocations()).thenAnswer(i -> locationList);
		return new AttackRangeFilter(client, config, team, locations);
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
	public void aSnareLandingWithASplashOnTheSameTickStillFreezes()
	{
		// You splash, a teammate's snare catches, same tick. The game keeps one graphic per slot
		// (the second replaces the first); if it reports each as it's set, either order freezes.
		// If only the splash arrives, see aMissedFreezeIsInferredFromTheAlreadyHeldMessage().
		for (boolean splashFirst : new boolean[]{false, true})
		{
			FreezeTracker freezes = new FreezeTracker(client, config, team);
			spotAnims(enemyA, splashFirst ? GraphicID.SPLASH : GraphicID.SNARE);
			freezes.onGraphicChanged(enemyA);
			spotAnims(enemyA, splashFirst ? GraphicID.SNARE : GraphicID.SPLASH);
			freezes.onGraphicChanged(enemyA);
			assertTrue("splash first: " + splashFirst, freezes.isFrozen(enemyA));
		}
	}

	@Test
	public void heldAndImmuneFollowTheRealSpellDuration()
	{
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		spotAnims(enemyA, GraphicID.SNARE);
		freezes.onGraphicChanged(enemyA);

		// Snare holds 16 ticks, then 5 immune - the shown countdown's extra tick doesn't count.
		tick = 15;
		assertTrue(freezes.isHeld(enemyA));
		assertFalse(freezes.isImmune(enemyA));
		tick = 16;
		assertFalse(freezes.isHeld(enemyA));
		assertTrue(freezes.isImmune(enemyA));
		tick = 20;
		assertTrue(freezes.isImmune(enemyA));
		tick = 21;
		assertFalse(freezes.isImmune(enemyA));
		assertFalse(freezes.isHeld(enemyB));
		assertFalse(freezes.isImmune(enemyB));
	}

	@Test
	public void aMissedFreezeIsInferredFromTheAlreadyHeldMessage()
	{
		// Only your splash reached the client; 3 ticks later the game says your bind's target is
		// already held - the teammate's snare landed with the splash.
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		tick = 4;
		spotAnims(enemyA, GraphicID.SPLASH);
		freezes.onGraphicChanged(enemyA);
		assertFalse(freezes.isFrozen(enemyA));

		tick = 7;
		freezes.onGameTick();
		freezes.onTargetAlreadyHeld(enemyA);
		FreezeState state = freezes.getFreezeStates().get(enemyA);
		assertEquals(4, state.startTick);
		assertEquals(4 + 16 + 5, state.immunityEndTick);
		assertTrue(freezes.isHeld(enemyA));

		// No splash seen: assume it started now.
		freezes.onTargetAlreadyHeld(enemyB);
		assertEquals(7, freezes.getFreezeStates().get(enemyB).startTick);

		// A freeze we already track is left alone.
		freezes.onTargetAlreadyHeld(enemyA);
		assertSame(state, freezes.getFreezeStates().get(enemyA));
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
		HighlightTracker highlights = new HighlightTracker(client, config, team, freezes, range());
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
		HighlightTracker highlights = new HighlightTracker(client, config, team, new FreezeTracker(client, config, team), range());

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
		HighlightTracker highlights = new HighlightTracker(client, config, team, new FreezeTracker(client, config, team), range());
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
		HighlightTracker highlights = new HighlightTracker(client, config, team, new FreezeTracker(client, config, team), range());

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

		AttackRangeFilter range = range();
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

		AttackRangeFilter range = range();
		range.onGameTick();
		assertFalse(range.isOutOfRange(enemyA));
	}

	private void wilderness()
	{
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(1);
	}

	@Test
	public void inFeroxYouCountFromTheGroundAndNobodyInsideCanBeAttacked()
	{
		when(config.attackRangeFilter()).thenReturn(true);
		wilderness();
		// The game says you're not in the wilderness in Ferox...
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(0);
		// ...but you're standing at level 14.
		when(local.getWorldLocation()).thenReturn(new WorldPoint(3140, 3630, 0));
		when(local.getCombatLevel()).thenReturn(60);
		// Just north of Ferox, level 18: 10 levels apart, within 14.
		when(enemyA.getWorldLocation()).thenReturn(new WorldPoint(3140, 3660, 0));
		when(enemyA.getCombatLevel()).thenReturn(70);
		// Inside Ferox with you, same level - still safe.
		when(enemyB.getWorldLocation()).thenReturn(new WorldPoint(3130, 3625, 0));
		when(enemyB.getCombatLevel()).thenReturn(60);

		AttackRangeFilter range = range();
		range.onGameTick();
		assertTrue(range.bracketApplies());
		assertFalse(range.isOutOfRange(enemyA));
		assertTrue(range.isOutOfRange(enemyB));

		// Without the Location Mapper's Ferox box there's no telling - no bracket.
		locationList = Collections.emptyList();
		range.onGameTick();
		assertFalse(range.bracketApplies());
		assertFalse(range.isOutOfRange(enemyB));
	}

	@Test
	public void highlightOthersInRangeUsesItsOwnBuffer()
	{
		when(config.highlightOthers()).thenReturn(true);
		when(config.othersOnlyInRange()).thenReturn(true);
		wilderness();
		// Level 11 wilderness everywhere (y=3600).
		when(local.getCombatLevel()).thenReturn(60);
		when(enemyA.getCombatLevel()).thenReturn(71);
		when(enemyB.getCombatLevel()).thenReturn(73);

		AttackRangeFilter range = range();
		range.onGameTick();
		assertEquals(Collections.singleton(enemyA), range.getOthersInRange());
		// Teammates are never "others".
		assertFalse(range.getOthersInRange().contains(mate));

		when(config.othersRangeBuffer()).thenReturn(2);
		range.onGameTick();
		assertEquals(2, range.getOthersInRange().size());
		// The greying filter is separate and stays off.
		assertFalse(range.isOutOfRange(enemyB));
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
		return hitsplat(HitsplatID.DAMAGE_OTHER, amount);
	}

	private static Hitsplat hitsplat(int type, int amount)
	{
		Hitsplat hit = mock(Hitsplat.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
		when(hit.getHitsplatType()).thenReturn(type);
		when(hit.getAmount()).thenReturn(amount);
		return hit;
	}

	private FoodTracker food()
	{
		when(config.showFoodCounter()).thenReturn(true);
		return new FoodTracker(client, config, team);
	}

	/**
	 * Enemy A in a fight, as pizzas are really eaten: hit every 4 ticks for 0-20 by you; whenever
	 * they're down at least eatBelow hp they eat a whole pizza - first bite, then the second a tick
	 * later (wiki: 1 then 2 tick delay) - healing up to max (so bites near full hp are cut short).
	 * The eat animation keeps playing through the chain; AnimationChanged fires for every bite, or
	 * (eventPerBite false, the worst case) only the first bite of each pizza. Their health bar - 30 steps of their real max hp - updates every
	 * tick, or only when hit (the worst case for a heal). Returns the bites they really ate.
	 */
	private int fight(FoodTracker food, int maxHp, int eatBelow, boolean eventPerBite, boolean barOnlyOnHits, int ticks,
		long seed)
	{
		java.util.Random random = new java.util.Random(seed);
		int[] hp = {maxHp};
		int[] ratio = {30};
		int[] animation = {-1};
		when(enemyA.getHealthScale()).thenReturn(30);
		when(enemyA.getHealthRatio()).thenAnswer(i -> ratio[0]);
		when(enemyA.getAnimation()).thenAnswer(i -> animation[0]);
		food.onGameTick();

		int bites = 0;
		int nextBiteTick = -1;
		int animationEnd = -1;
		for (int t = 1; t <= ticks; t++)
		{
			tick++;
			boolean hit = t % 4 == 0;
			if (nextBiteTick < 0 && maxHp - hp[0] >= eatBelow)
			{
				// Start a pizza: first bite now, second next tick.
				nextBiteTick = t;
				animation[0] = AnimationID.HUMAN_EAT;
				food.onAnimationChanged(enemyA);
			}
			if (t == nextBiteTick || t == nextBiteTick + 1)
			{
				if (eventPerBite && t != nextBiteTick)
				{
					food.onAnimationChanged(enemyA);
				}
				hp[0] = Math.min(maxHp, hp[0] + FoodTracker.HP_PER_BITE);
				bites++;
				animationEnd = t + 3;
				nextBiteTick = t == nextBiteTick + 1 ? -1 : nextBiteTick;
			}
			if (t >= animationEnd)
			{
				animation[0] = -1;
			}
			if (hit)
			{
				// Never kills - a death resets the count.
				int damage = Math.min(random.nextInt(21), hp[0] - 1);
				hp[0] -= damage;
				food.onHitsplatApplied(enemyA, damage(damage));
			}
			if (hit || !barOnlyOnHits)
			{
				ratio[0] = Math.max(1, (int) Math.ceil(hp[0] * 30.0 / maxHp));
			}
			food.onGameTick();
		}
		return bites;
	}

	@Test
	public void aFullInventoryEatenUnderAttackIsCountedClosely()
	{
		// The in-game tests that undercounted: 23-25 pizzas eaten while being hit constantly. Eating a
		// pizza only 9 down wastes most of its second bite at full hp. The game plays one animation
		// per pizza and none for the second bite (confirmed in game, 2026-10-02).
		for (int eatBelow : new int[]{18, 9})
		{
			for (boolean barOnlyOnHits : new boolean[]{false, true})
			{
				for (long seed = 1; seed <= 5; seed++)
				{
					tick = 0;
					FoodTracker food = food();
					int eaten = fight(food, 99, eatBelow, false, barOnlyOnHits, 260, seed);
					assertTrue("ate " + eaten + ", counted " + food.getBites(enemyA) + " (eat " + eatBelow
							+ " below max, bar only on hits: " + barOnlyOnHits + ")",
						Math.abs(food.getBites(enemyA) - eaten) <= Math.max(3, eaten / 10));
				}
			}
		}
	}

	@Test
	public void anEnemysMaxHpIsMeasuredFromTheirHits()
	{
		// A 70 hp account, and you've left the setting at 99: unmeasured, every heal reads as 41% too
		// much. Hits alone first (no food), then a fight.
		FoodTracker food = food();
		int[] ratio = {30};
		when(enemyA.getHealthScale()).thenReturn(30);
		when(enemyA.getHealthRatio()).thenAnswer(i -> ratio[0]);
		food.onGameTick();
		int hpLeft = 70;
		for (int i = 0; i < 6; i++)
		{
			tick++;
			hpLeft -= 8;
			food.onHitsplatApplied(enemyA, damage(8));
			ratio[0] = (int) Math.ceil(hpLeft * 30.0 / 70);
			food.onGameTick();
		}
		assertEquals(70, food.maxHp("enemy a"), 3);

		food.reset(enemyA);
		int eaten = fight(food, 70, 18, false, false, 200, 7);
		assertTrue("ate " + eaten + ", counted " + food.getBites(enemyA), Math.abs(food.getBites(enemyA) - eaten) <= 3);
	}

	@Test
	public void healingWithoutAnEatIsNotFoodAndAPotionIsNotFood()
	{
		FoodTracker food = food();
		hp(enemyA, 40);
		food.onGameTick();

		// e.g. a blood barrage.
		tick = 1;
		hp(enemyA, 70);
		food.onGameTick();
		assertEquals(0, food.getBites(enemyA));

		// A potion: the eat animation, no heal.
		tick = 2;
		animate(enemyA, AnimationID.HUMAN_EAT);
		food.onAnimationChanged(enemyA);
		for (tick = 2; tick < 10; tick++)
		{
			food.onGameTick();
		}
		assertEquals(0, food.getBites(enemyA));
	}

	@Test
	public void aPotionAtFullHpIsNotABite()
	{
		// A combat potion sipped at full hp: the eat animation, a full bar, no food anywhere near.
		FoodTracker food = food();
		hp(enemyA, 99);
		food.onGameTick();
		tick = 1;
		animate(enemyA, AnimationID.HUMAN_EAT);
		food.onAnimationChanged(enemyA);
		food.onGameTick();
		tick = 4;
		food.onHitsplatApplied(enemyA, damage(10));
		hp(enemyA, 89);
		food.onGameTick();
		assertEquals(0, food.getBites(enemyA));
	}

	@Test
	public void poisonDamageIsAddedBackToo()
	{
		// One bite while venom ticks 6 the same tick: the bar only shows +3.
		FoodTracker food = food();
		hp(enemyA, 50);
		food.onGameTick();
		tick = 1;
		animate(enemyA, AnimationID.HUMAN_EAT);
		food.onAnimationChanged(enemyA);
		food.onHitsplatApplied(enemyA, hitsplat(HitsplatID.VENOM, 6));
		hp(enemyA, 53);
		food.onGameTick();
		assertEquals(1, food.getBites(enemyA));
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
	public void aNewRoundResetsEveryone()
	{
		// Through the Clan Wars portal: everyone has full food again.
		FoodTracker food = foodWithOneBite();
		food.startRound();
		assertEquals(0, food.getBites(enemyA));
	}

	@Test
	public void foodCountsSurviveLeavingViewAndAreNeverForgotten()
	{
		// Nobody restocks in Clan Wars.
		FoodTracker food = foodWithOneBite();
		food.forget(enemyA);
		setPlayers(local, mate, enemyB);
		tick = 100_000;
		food.onGameTick();
		assertEquals(1, food.getBites(enemyA));
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
		return new TeamPerformanceTracker(client, config, team, new PlayerNicknameTracker(client, config, team));
	}

	private static PerformanceRow row(List<PerformanceRow> rows, String name)
	{
		return rows.stream().filter(r -> r.name.equals(name)).findFirst().orElseThrow(AssertionError::new);
	}

	@Test
	public void statsCountPizzasPilesAndDeaths()
	{
		TeamPerformanceTracker performance = performance();
		when(mate.getInteracting()).thenReturn(enemyA);
		performance.onGameTick();

		animate(mate, AnimationID.HUMAN_EAT);
		performance.onAnimationChanged(mate);
		animate(mate, AnimationID.HUMAN_EAT);
		performance.onAnimationChanged(mate);
		// A block isn't an attack or an eat; enemies' animations aren't tracked.
		animate(mate, AnimationID.HUMAN_SHIELD_DEFENCE);
		performance.onAnimationChanged(mate);
		animate(enemyA, AnimationID.HUMAN_EAT);
		performance.onAnimationChanged(enemyA);

		// Death animation and ActorDeath both report it - one kill.
		tick = 3;
		performance.onDeath(enemyA);
		performance.onDeath(enemyA);

		PerformanceRow mateRow = row(performance.currentRows(), "Mate");
		assertEquals(2, mateRow.pizzas);
		assertEquals(1, mateRow.onPile);
		assertEquals(1, mateRow.pilesAround);
		// You were around but not on enemyA.
		assertEquals(0, row(performance.currentRows(), "Me").onPile);
		assertEquals(1, row(performance.currentRows(), "Me").pilesAround);
		assertEquals(1, performance.snapshot().get(0).kills);

		// Mate dies: first in the list, with the time; you're still alive.
		tick = 30;
		performance.onDeath(mate);
		List<PerformanceRow> rows = performance.currentRows();
		assertEquals("Mate", rows.get(0).name);
		assertEquals(1, rows.get(0).deathMillis.size());
		assertTrue(row(rows, "Me").deathMillis.isEmpty());
	}

	@Test
	public void aPortalRoundCountsNothingEatenDuringTheCountdown()
	{
		PlayerNicknameTracker nicknames = new PlayerNicknameTracker(client, config, team);
		nicknames.setNicknames(Map.of("Mate", "Matey"));
		when(config.enablePerformanceTracker()).thenReturn(true);
		TeamPerformanceTracker performance = new TeamPerformanceTracker(client, config, team, nicknames);

		// Through the portal late: 150 ticks of the countdown left.
		when(client.getVarbitValue(VarbitID.CLANWARS_COUNTDOWN)).thenReturn(150);
		long before = System.currentTimeMillis();
		performance.startRound();
		performance.onGameTick();
		assertTrue(performance.snapshot().get(0).startMillis >= before + 150 * 600L);

		// A strength potion during the countdown isn't a pizza.
		tick = 149;
		performance.onGameTick();
		animate(mate, AnimationID.HUMAN_EAT);
		performance.onAnimationChanged(mate);

		// The countdown ends when the varbit goes to 0, whatever it said before.
		tick = 160;
		when(client.getVarbitValue(VarbitID.CLANWARS_COUNTDOWN)).thenReturn(0);
		long end = System.currentTimeMillis();
		performance.onGameTick();
		animate(mate, AnimationID.HUMAN_EAT);
		performance.onAnimationChanged(mate);

		PerformanceRow mateRow = row(performance.currentRows(), "Mate");
		assertEquals(1, mateRow.pizzas);
		assertEquals(18, mateRow.pizzaHp());
		assertEquals("Matey", mateRow.nickname);
		long start = performance.snapshot().get(0).startMillis;
		assertTrue(start >= end && start <= System.currentTimeMillis());
	}

	@Test
	public void aKillNobodyCouldSeeIsNotCountedAgainstThem()
	{
		TeamPerformanceTracker performance = performance();
		performance.onGameTick();
		// Mate leaves your screen for a long time; an enemy dies meanwhile.
		setPlayers(local, enemyA, enemyB);
		tick = TeamPerformanceTracker.PILE_CREDIT_TICKS + 5;
		performance.onGameTick();
		performance.onDeath(enemyA);
		assertEquals(0, row(performance.currentRows(), "Mate").pilesAround);
		assertEquals(1, row(performance.currentRows(), "Me").pilesAround);
	}

	@Test
	public void pileOrderIsThePlaceTeammatesGotOnAndAMissIsLast()
	{
		TeamPerformanceTracker performance = performance();
		Player other = player("Other", true);
		setPlayers(local, mate, other, enemyA, enemyB);
		team.refresh();

		// Pile 1 on enemy A: Mate on at tick 0, you at tick 2, Other never.
		when(mate.getInteracting()).thenReturn(enemyA);
		performance.onGameTick();
		tick = 2;
		when(local.getInteracting()).thenReturn(enemyA);
		performance.onGameTick();
		tick = 4;
		performance.onDeath(enemyA);

		// Pile 2 on enemy B: Other first, then you; Mate never.
		when(mate.getInteracting()).thenReturn(null);
		when(local.getInteracting()).thenReturn(null);
		when(other.getInteracting()).thenReturn(enemyB);
		tick = 30;
		performance.onGameTick();
		when(local.getInteracting()).thenReturn(enemyB);
		tick = 31;
		performance.onGameTick();
		tick = 33;
		performance.onDeath(enemyB);

		List<PerformanceRow> rows = performance.currentRows();
		// Mate: 1st, then missed = last of 3 around.
		assertEquals(2.0, row(rows, "Mate").averagePileOrder(), 0.001);
		// You: 2nd both times.
		assertEquals(2.0, row(rows, "Me").averagePileOrder(), 0.001);
		// Other: missed (3rd), then 1st.
		assertEquals(2.0, row(rows, "Other").averagePileOrder(), 0.001);
		assertEquals(1, row(rows, "Other").onPile);
		assertEquals(2, row(rows, "Other").pilesAround);
	}

	@Test
	public void anOldChipDoesNotMakeYouFirstOntoThePile()
	{
		TeamPerformanceTracker performance = performance();
		// You chip enemy A at tick 0, leave, and come back long after Mate started the real pile.
		when(local.getInteracting()).thenReturn(enemyA);
		performance.onGameTick();
		when(local.getInteracting()).thenReturn(null);
		when(mate.getInteracting()).thenReturn(enemyA);
		for (tick = 10; tick < 10 + TeamPerformanceTracker.PILE_CREDIT_TICKS + 5; tick++)
		{
			performance.onGameTick();
		}
		when(local.getInteracting()).thenReturn(enemyA);
		performance.onGameTick();
		performance.onDeath(enemyA);
		assertEquals(1.0, row(performance.currentRows(), "Mate").averagePileOrder(), 0.001);
		assertEquals(2.0, row(performance.currentRows(), "Me").averagePileOrder(), 0.001);
	}

	private static Friend friend(String name)
	{
		Friend friend = mock(Friend.class);
		when(friend.getName()).thenReturn(name);
		return friend;
	}

	@Test
	public void aFriendRenamedTwiceWhileAwayKeepsTheirNickname()
	{
		when(config.showPlayerNicknames()).thenReturn(true);
		when(config.playerNicknames()).thenReturn("Old Name=Tank");
		ConfigManager configManager = mock(ConfigManager.class);
		when(configManager.getRSProfileKey()).thenReturn("rsprofile.test");
		// Saved last session.
		when(configManager.getRSProfileConfiguration(PrepToolsConfig.GROUP, "friendsListSnapshot")).thenReturn("Old Name\nStays");

		// Now: renamed Old Name to Middle to Newest - the game only says "previously Middle".
		Friend newest = friend("Newest");
		when(newest.getPrevName()).thenReturn("Middle");
		FriendContainer friends = mock(FriendContainer.class);
		when(friends.getCount()).thenReturn(2);
		Friend[] members = {friend("Stays"), newest};
		when(friends.getMembers()).thenReturn(members);
		when(client.getFriendContainer()).thenReturn(friends);

		ConfigListEditor lists = new ConfigListEditor(config, configManager, mock(ChatMessageManager.class),
			mock(HighlightTracker.class));
		PlayerNicknameTracker nicknames = new PlayerNicknameTracker(client, config, team);
		nicknames.setNicknames(Collections.singletonMap("Old Name", "Tank"));
		FriendRenameTracker renames = new FriendRenameTracker(client, config, configManager, lists, nicknames,
			mock(ChatMessageManager.class));

		// Nothing until the list has been in for a while after login.
		for (int i = 0; i < 9; i++)
		{
			renames.onGameTick();
		}
		verify(configManager, never()).setConfiguration(PrepToolsConfig.GROUP, "playerNicknames", "Newest=Tank");

		renames.onGameTick();
		verify(configManager).setConfiguration(PrepToolsConfig.GROUP, "playerNicknames", "Newest=Tank");
		verify(configManager).setRSProfileConfiguration(PrepToolsConfig.GROUP, "friendsListSnapshot", "Stays\nNewest");
		assertTrue(renames.getPending().isEmpty());
	}

	@Test
	public void anUnclearRenameIsOfferedAndAnUnloadedListNeverOverwritesTheSavedOne()
	{
		when(config.showPlayerNicknames()).thenReturn(true);
		ConfigManager configManager = mock(ConfigManager.class);
		when(configManager.getRSProfileKey()).thenReturn("rsprofile.test");
		when(configManager.getRSProfileConfiguration(PrepToolsConfig.GROUP, "friendsListSnapshot")).thenReturn("Ay\nBee");
		FriendContainer friends = mock(FriendContainer.class);
		when(client.getFriendContainer()).thenReturn(friends);
		when(friends.getMembers()).thenReturn(new Friend[0]);

		PlayerNicknameTracker nicknames = new PlayerNicknameTracker(client, config, team);
		nicknames.setNicknames(Collections.singletonMap("Ay", "Tank"));
		FriendRenameTracker renames = new FriendRenameTracker(client, config, configManager,
			mock(ConfigListEditor.class), nicknames, mock(ChatMessageManager.class));

		// The list never loaded: nothing compared, nothing saved.
		for (int i = 0; i < 20; i++)
		{
			renames.onGameTick();
		}
		verify(configManager, never()).setRSProfileConfiguration(anyString(), anyString(), any());

		// Next login it arrives with two new names - which one is Tank's is your call.
		renames.onSessionChanged();
		when(friends.getCount()).thenReturn(2);
		Friend[] arrived = {friend("Cee"), friend("Dee")};
		when(friends.getMembers()).thenReturn(arrived);
		for (int i = 0; i < 10; i++)
		{
			renames.onGameTick();
		}
		assertEquals(1, renames.getPending().size());
		assertEquals("Tank", renames.getPending().get(0).nickname);
		assertEquals(Arrays.asList("Cee", "Dee"), renames.getPending().get(0).candidates);
	}

	@Test
	public void eachRoundHasItsOwnNumbers()
	{
		TeamPerformanceTracker performance = performance();
		animate(mate, AnimationID.HUMAN_EAT);
		performance.onAnimationChanged(mate);
		assertEquals(1, row(performance.currentRows(), "Mate").pizzas);

		// Through the portal (no countdown running).
		performance.startRound();
		tick = 1;
		performance.onGameTick();
		performance.onAnimationChanged(mate);
		performance.onAnimationChanged(mate);

		List<PerformanceRound> rounds = performance.snapshot();
		assertEquals(2, rounds.size());
		assertEquals(1, row(rounds.get(0).rows, "Mate").pizzas);
		assertEquals(2, row(rounds.get(1).rows, "Mate").pizzas);

		// An empty round isn't kept: going through again before anything happened just restarts it.
		performance.startRound();
		performance.startRound();
		assertEquals(3, performance.snapshot().size());
		assertTrue(performance.currentRows().isEmpty());

		performance.reset();
		assertEquals(1, performance.snapshot().size());
	}

	@Test
	public void onPileCountsWhoeverWasOnTheEnemyRecently()
	{
		TeamPerformanceTracker performance = performance();

		// Mate attacked enemy A (an attack animation), then dropped them a few ticks before they died.
		when(mate.getInteracting()).thenReturn(enemyA);
		animate(mate, 1658);
		performance.onAnimationChanged(mate);
		when(mate.getInteracting()).thenReturn(null);
		for (tick = 1; tick <= TeamPerformanceTracker.PILE_CREDIT_TICKS - 2; tick++)
		{
			performance.onGameTick();
		}
		performance.onDeath(enemyA);
		assertEquals(1, row(performance.currentRows(), "Mate").onPile);

		// Too long ago doesn't count.
		when(local.getInteracting()).thenReturn(enemyB);
		performance.onGameTick();
		when(local.getInteracting()).thenReturn(null);
		tick += TeamPerformanceTracker.PILE_CREDIT_TICKS + 2;
		performance.onGameTick();
		performance.onDeath(enemyB);
		assertEquals(0, row(performance.currentRows(), "Me").onPile);
		assertEquals(2, row(performance.currentRows(), "Me").pilesAround);
	}
}
