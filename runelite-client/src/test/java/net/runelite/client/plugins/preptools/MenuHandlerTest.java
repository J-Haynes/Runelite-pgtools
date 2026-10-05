package net.runelite.client.plugins.preptools;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.client.menus.TestMenuEntry;
import net.runelite.client.util.ColorUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MenuHandlerTest
{
	private Client client;
	private PrepToolsConfig config;
	private TeamTracker team;
	private HighlightTracker highlights;
	private AttackRangeFilter range;
	private PlayerNicknameTracker nicknames;
	private MenuHandler menu;

	private final Player local = mock(Player.class);
	private final Player enemy = mock(Player.class);
	private final Player target = mock(Player.class);
	private final Player teammate = mock(Player.class);

	private final Set<Player> targets = new HashSet<>();
	private final Set<Player> piles = new HashSet<>();

	@Before
	public void before()
	{
		client = mock(Client.class);
		config = mock(PrepToolsConfig.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
		team = mock(TeamTracker.class);
		highlights = mock(HighlightTracker.class);
		range = mock(AttackRangeFilter.class);
		nicknames = mock(PlayerNicknameTracker.class);
		menu = new MenuHandler(client, config, team, highlights, range, nicknames);

		when(client.getLocalPlayer()).thenReturn(local);
		when(team.isOwnTeam(teammate)).thenReturn(true);
		when(team.isOwnTeam(local)).thenReturn(true);
		when(highlights.getTargets()).thenReturn(targets);
		when(highlights.getPiledPlayers()).thenReturn(piles);
		when(highlights.getSnipedPlayers()).thenReturn(Collections.emptySet());
		when(highlights.getAttackers()).thenReturn(Collections.emptySet());
		when(highlights.getBackupCallers()).thenReturn(Collections.emptySet());
		when(highlights.getStyles()).thenReturn(Collections.emptyMap());
	}

	private static MenuEntry entry(String option, Player player)
	{
		TestMenuEntry entry = new TestMenuEntry();
		entry.setOption(option);
		entry.setTarget("");
		entry.setType(MenuAction.PLAYER_SECOND_OPTION);
		entry.setActor(player);
		return entry;
	}

	private static MenuEntry walkHere()
	{
		TestMenuEntry entry = new TestMenuEntry();
		entry.setOption("Walk here");
		entry.setTarget("");
		entry.setType(MenuAction.WALK);
		return entry;
	}

	/**
	 * Runs the handler on a menu (last entry = left-click) and returns what it set, or null if it
	 * left the menu alone.
	 */
	private List<MenuEntry> run(MenuEntry... entries)
	{
		when(client.getMenuEntries()).thenReturn(entries);
		menu.onPostMenuSort();
		ArgumentCaptor<MenuEntry[]> captor = ArgumentCaptor.forClass(MenuEntry[].class);
		try
		{
			verify(client).setMenuEntries(captor.capture());
		}
		catch (AssertionError e)
		{
			return null;
		}
		return Arrays.asList(captor.getValue());
	}

	private static MenuEntry leftClick(List<MenuEntry> entries)
	{
		return entries.get(entries.size() - 1);
	}

	@Test
	public void targetAttackBecomesLeftClick()
	{
		targets.add(target);
		MenuEntry attackTarget = entry("Attack", target);

		List<MenuEntry> result = run(attackTarget, entry("Attack", enemy), walkHere());

		assertEquals(attackTarget, leftClick(result));
	}

	@Test
	public void configuredOrderBreaksTies()
	{
		targets.add(target);
		piles.add(enemy);
		when(config.attackPriority1()).thenReturn(AttackCategory.PILE);
		when(config.attackPriority2()).thenReturn(AttackCategory.TARGET);
		MenuEntry attackPile = entry("Attack", enemy);

		List<MenuEntry> result = run(attackPile, entry("Attack", target), walkHere());

		assertEquals(attackPile, leftClick(result));
	}

	@Test
	public void outOfRangeTargetIsNotPromoted()
	{
		targets.add(target);
		when(range.isOutOfRange(target)).thenReturn(true);
		MenuEntry walk = walkHere();

		List<MenuEntry> result = run(entry("Attack", target), walk);

		// Nothing promoted, nothing removed: the game's own order stands.
		assertNull(result);
	}

	@Test
	public void nicknameIsTaggedOnceAfterTheName()
	{
		when(nicknames.getNicknames()).thenReturn(Collections.singletonMap(enemy, "Tank"));
		MenuEntry attackEnemy = entry("Attack", enemy);
		attackEnemy.setTarget("Zezima  (level-126)");

		run(attackEnemy);
		// PostMenuSort can run again on the same entries next frame.
		run(attackEnemy);

		assertEquals("Zezima  (level-126) " + ColorUtil.wrapWithColorTag("[Tank]", config.playerNicknameColor()),
			attackEnemy.getTarget());
	}

	@Test
	public void teammateAttackIsRemoved()
	{
		MenuEntry walk = walkHere();
		List<MenuEntry> result = run(walk, entry("Attack", teammate));

		assertEquals(Collections.singletonList(walk), result);
	}

	@Test
	public void enemyAttackReplacesARemovedLeftClick()
	{
		MenuEntry attackEnemy = entry("Attack", enemy);
		List<MenuEntry> result = run(attackEnemy, walkHere(), entry("Attack", teammate));

		assertEquals(attackEnemy, leftClick(result));
	}

	@Test
	public void removingANonLeftClickEntryKeepsTheGamesOrder()
	{
		// Walk here was the left-click (e.g. Attack set to right-click only) - removing the
		// teammate's Attack must not promote the enemy's Attack over it.
		MenuEntry walk = walkHere();
		MenuEntry attackEnemy = entry("Attack", enemy);
		List<MenuEntry> result = run(entry("Attack", teammate), attackEnemy, walk);

		assertEquals(Arrays.asList(attackEnemy, walk), result);
	}

	@Test
	public void shiftClickWalkHereBeatsAPrioritisedTarget()
	{
		targets.add(target);
		when(config.shiftClickWalkUnderAttackablePlayers()).thenReturn(true);
		when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);

		assertNull(run(entry("Attack", target), walkHere()));
	}

	@Test
	public void callerFollowIsLeftClickWithoutATarget()
	{
		Player caller = mock(Player.class);
		when(team.isOwnTeam(caller)).thenReturn(true);
		when(highlights.getPrimaryCaller()).thenReturn(caller);
		MenuEntry follow = entry("Follow", caller);

		List<MenuEntry> result = run(follow, entry("Attack", enemy), walkHere());

		assertEquals(follow, leftClick(result));
	}

	@Test
	public void freezeOnTeammateIsRemoved()
	{
		MenuEntry snareTeammate = entry("Cast", teammate);
		snareTeammate.setType(MenuAction.WIDGET_TARGET_ON_PLAYER);
		snareTeammate.setTarget("<col=00ff00>Snare</col><col=ffffff> -> <col=ffffff>Mate");
		MenuEntry snareEnemy = entry("Cast", enemy);
		snareEnemy.setType(MenuAction.WIDGET_TARGET_ON_PLAYER);
		snareEnemy.setTarget("<col=00ff00>Snare</col><col=ffffff> -> <col=ffffff>Foe");

		List<MenuEntry> result = run(snareEnemy, snareTeammate);

		assertEquals(Collections.singletonList(snareEnemy), result);
	}

	private static MenuEntry cast(String option, String spell, Player player)
	{
		MenuEntry entry = entry(option, player);
		entry.setType(MenuAction.WIDGET_TARGET_ON_PLAYER);
		entry.setTarget("<col=00ff00>" + spell + "</col><col=ffffff> -> <col=ffffff>Name");
		return entry;
	}

	@Test
	public void anySpellOnTeammateIsRemovedButItemsAndSupportSpellsStay()
	{
		MenuEntry tbTeammate = cast("Cast", "Tele Block", teammate);
		MenuEntry blastTeammate = cast("Cast", "Fire Blast", teammate);
		MenuEntry vengTeammate = cast("Cast", "Vengeance Other", teammate);
		MenuEntry itemOnTeammate = cast("Use", "Shark", teammate);
		MenuEntry tbEnemy = cast("Cast", "Tele Block", enemy);

		List<MenuEntry> result = run(tbEnemy, itemOnTeammate, vengTeammate, blastTeammate, tbTeammate);

		assertEquals(Arrays.asList(tbEnemy, itemOnTeammate, vengTeammate), result);
	}

	@Test
	public void spellsOnTeammatesAllowedWhenToggledOff()
	{
		when(config.preventFreezingTeammates()).thenReturn(false);
		when(client.getMenuEntries()).thenReturn(new MenuEntry[]{cast("Cast", "Snare", teammate)});
		menu.onPostMenuSort();
		verify(client, never()).setMenuEntries(any());
	}

	@Test
	public void plainMenuIsLeftAlone()
	{
		when(client.getMenuEntries()).thenReturn(new MenuEntry[]{entry("Attack", enemy), walkHere()});
		menu.onPostMenuSort();
		verify(client, never()).setMenuEntries(any());
	}
}
