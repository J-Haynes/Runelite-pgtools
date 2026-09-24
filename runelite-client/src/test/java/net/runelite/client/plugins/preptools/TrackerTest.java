package net.runelite.client.plugins.preptools;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GraphicID;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
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

	@Test
	public void freezeIsNotRefreshedByAHitWhileFrozenOrImmune()
	{
		when(config.showFreezeTimers()).thenReturn(true);
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		when(enemyA.getGraphic()).thenReturn(GraphicID.SNARE);

		assertFalse(freezes.onGraphicChanged(enemyA));
		FreezeState first = freezes.getFreezeStates().get(enemyA);
		assertEquals(17, first.endTick);

		// A barrage lands mid-snare: the game doesn't refreeze, so neither do we.
		tick = 10;
		when(enemyA.getGraphic()).thenReturn(GraphicID.ICE_BARRAGE);
		freezes.onGraphicChanged(enemyA);
		assertTrue(first == freezes.getFreezeStates().get(enemyA));

		// Expires into immunity; a hit during immunity doesn't freeze either.
		tick = 17;
		freezes.onGameTick();
		assertTrue(first.immune);
		freezes.onGraphicChanged(enemyA);
		assertTrue(first == freezes.getFreezeStates().get(enemyA));

		// Immunity over: tracking stops, and the next hit is a new freeze.
		tick = 22;
		freezes.onGameTick();
		assertFalse(freezes.getFreezeStates().containsKey(enemyA));
		freezes.onGraphicChanged(enemyA);
		assertEquals(22 + 33, freezes.getFreezeStates().get(enemyA).endTick);
	}

	@Test
	public void yourFreezeIsTrackedEvenWithFreezeTimersOff()
	{
		when(config.showFreezeTimers()).thenReturn(false);
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		when(local.getGraphic()).thenReturn(GraphicID.ICE_RUSH);

		assertTrue(freezes.onGraphicChanged(local));
		assertTrue(freezes.isLocalFrozen());
		assertTrue(freezes.getFreezeStates().isEmpty());
		// A second hit while frozen isn't a new freeze.
		assertFalse(freezes.onGraphicChanged(local));
	}

	@Test
	public void movingAfterTheGraceTicksEndsAFreeze()
	{
		FreezeTracker freezes = new FreezeTracker(client, config, team);
		when(local.getGraphic()).thenReturn(GraphicID.ICE_BARRAGE);
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
		when(local.getGraphic()).thenReturn(GraphicID.BIND);
		freezes.onGraphicChanged(local);
		freezes.onDeath(local);
		assertNull(freezes.getLocalFreeze());
	}

	@Test
	public void pileNeedsEnoughTeammatesAndNeverATeammate()
	{
		when(config.enableTargetedPlayerDetection()).thenReturn(true);
		when(config.targetedPlayerThreshold()).thenReturn(2);
		HighlightTracker highlights = new HighlightTracker(client, config, team);

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
		HighlightTracker highlights = new HighlightTracker(client, config, team);
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
		HighlightTracker highlights = new HighlightTracker(client, config, team);

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
}
