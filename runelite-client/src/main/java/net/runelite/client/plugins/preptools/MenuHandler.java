package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.util.ColorUtil;

/**
 * Right-click menu changes on players: recolouring and prioritising Attack (target, pile, snipe,
 * attacker), making a caller's Follow the left-click, removing Attack and spell casts on
 * teammates, removing binds on players already held (or immune), and shift-click Walk here.
 *
 * Edits happen in PostMenuSort, after the game's own sort - earlier edits get overwritten. The one
 * exception is setDeprioritized() (shift-click Walk here), an input the sort itself reads, so it's
 * set on ClientTick, before the sort, like MenuEntrySwapperPlugin does. PostMenuSort can fire every
 * frame while hovering: keep it cheap, and throttle any logging.
 */
@Slf4j
class MenuHandler
{
	private static final Set<String> FREEZE_SPELL_NAMES = Set.of(
		"bind", "snare", "entangle", "ice rush", "ice burst", "ice blitz", "ice barrage");

	/**
	 * The binding spells - unlike ice spells, the game refuses them on a held or immune target
	 * instead of still hitting it, so a cast there is a wasted click.
	 */
	private static final Set<String> BINDING_SPELL_NAMES = Set.of("bind", "snare", "entangle");

	/**
	 * Lunar spells meant for teammates - never blocked by preventFreezingTeammates().
	 */
	private static final Set<String> TEAM_SUPPORT_SPELL_NAMES = Set.of(
		"vengeance other", "heal other", "energy transfer", "cure other", "stat spy");

	private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");
	private static final long DEBUG_LOG_INTERVAL_MILLIS = 1000;

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;
	private final HighlightTracker highlights;
	private final AttackRangeFilter range;
	private final PlayerNicknameTracker nicknames;
	private final FreezeTracker freezes;

	private long lastDebugLogMillis;

	@Inject
	MenuHandler(Client client, PrepToolsConfig config, TeamTracker team, HighlightTracker highlights, AttackRangeFilter range,
		PlayerNicknameTracker nicknames, FreezeTracker freezes)
	{
		this.client = client;
		this.config = config;
		this.team = team;
		this.highlights = highlights;
		this.range = range;
		this.nicknames = nicknames;
		this.freezes = freezes;
	}

	static String stripTags(String text)
	{
		return text == null ? "" : TAG_PATTERN.matcher(text).replaceAll("").trim();
	}

	/**
	 * Shift-click Walk here on attackable players - Menu Entry Swapper has it for NPCs, objects and
	 * ground items, not players.
	 */
	void onClientTick()
	{
		if (!isShiftWalkActive() || client.isMenuOpen())
		{
			return;
		}

		for (MenuEntry entry : client.getMenuEntries())
		{
			if (entry.getPlayer() != null && "Attack".equalsIgnoreCase(stripTags(entry.getOption())))
			{
				entry.setDeprioritized(true);
			}
		}
	}

	/**
	 * One pass over the sorted menu: recolour, drop teammate Attack/spell entries, then pick the
	 * left-click (the last entry). Left-click preference: a prioritised target/pile/snipe/attacker
	 * Attack (tie-break: the Menu section's Attack priority), then the primary caller's Follow, a
	 * fall-in leader's Follow, a backup caller's Follow, then - only if
	 * we removed the entry that WAS the left-click - some other enemy's Attack. Otherwise the game's
	 * own order is kept. Shift-click Walk here beats all of it while shift is held.
	 */
	void onPostMenuSort()
	{
		if (client.isMenuOpen())
		{
			return;
		}

		MenuEntry[] entries = client.getMenuEntries();
		if (entries.length == 0)
		{
			return;
		}

		Map<Player, HighlightStyle> styles = config.colourAttackOption() ? highlights.getStyles() : null;
		Map<Player, String> playerNicknames = nicknames.getNicknames();
		boolean blockTeammateSpells = config.preventFreezingTeammates();
		boolean blockBindsOnHeld = config.blockBindsOnHeldPlayers();
		boolean blockBindsOnImmune = config.blockBindsOnImmunePlayers();
		boolean hideTeammateAttack = config.hideAttackOnTeammates();
		boolean shiftWalk = isShiftWalkActive();
		boolean prioritiseFollow = config.prioritiseCallerFollow() && !shiftWalk;
		boolean prioritiseTarget = config.prioritiseTargetAttack();
		boolean prioritisePile = config.prioritiseAttackForPiles();
		boolean prioritiseSnipe = config.prioritiseAttackForSnipes();
		boolean prioritiseAttacker = config.prioritiseAttackerAttack();
		Player local = client.getLocalPlayer();
		Player primaryCaller = highlights.getPrimaryCaller();
		Set<Player> backupCallers = highlights.getBackupCallers();
		Set<Player> fallInLeaders = highlights.getFallInLeaders();
		String selectedSpell = getSelectedSpellName();

		boolean debug = config.debugLogging() && System.currentTimeMillis() - lastDebugLogMillis >= DEBUG_LOG_INTERVAL_MILLIS;
		if (debug)
		{
			lastDebugLogMillis = System.currentTimeMillis();
		}

		List<MenuEntry> kept = new ArrayList<>(entries.length);
		Map<AttackCategory, Integer> priorityIndices = new EnumMap<>(AttackCategory.class);
		int primaryFollowIndex = -1;
		int backupFollowIndex = -1;
		int fallInFollowIndex = -1;
		int anyEnemyAttackIndex = -1;
		boolean removedAny = false;
		boolean removedLeftClick = false;

		for (int i = 0; i < entries.length; i++)
		{
			MenuEntry entry = entries[i];
			Player player = entry.getPlayer();
			if (player == null)
			{
				kept.add(entry);
				continue;
			}

			String nickname = playerNicknames.get(player);
			if (nickname != null)
			{
				appendToTarget(entry, "[" + nickname + "]", config.playerNicknameColor());
			}

			String option = stripTags(entry.getOption());
			if (debug)
			{
				log.info("PvP Tools debug: menu entry option='{}' target='{}' type={} player='{}'",
					entry.getOption(), entry.getTarget(), entry.getType(), player.getName());
			}

			boolean teammate = player != local && team.isOwnTeam(player);
			boolean caller = player == primaryCaller || backupCallers.contains(player);
			HighlightStyle style = styles != null ? styles.get(player) : null;

			if (caller && "Follow".equalsIgnoreCase(option))
			{
				if (style != null)
				{
					entry.setOption(ColorUtil.wrapWithColorTag("Follow", style.color));
				}
				if (prioritiseFollow)
				{
					if (player == primaryCaller)
					{
						primaryFollowIndex = kept.size();
					}
					else
					{
						backupFollowIndex = kept.size();
					}
				}
				kept.add(entry);
			}
			else if (!shiftWalk && fallInLeaders.contains(player) && "Follow".equalsIgnoreCase(option))
			{
				fallInFollowIndex = kept.size();
				kept.add(entry);
			}
			else if ("Attack".equalsIgnoreCase(option))
			{
				if (teammate)
				{
					// The game can offer Attack on FC members in the wilderness; a fall-in stacks the
					// team on one member, so a mis-click attacks them. Never coloured or promoted.
					if (hideTeammateAttack)
					{
						removedAny = true;
						removedLeftClick |= i == entries.length - 1;
						continue;
					}
					kept.add(entry);
					continue;
				}

				// Experimental: out of your combat bracket - greyed, never promoted.
				if (range.isOutOfRange(player))
				{
					entry.setOption(ColorUtil.wrapWithColorTag("Attack", config.outOfRangeColor()));
					kept.add(entry);
					continue;
				}

				// A caller's Follow gets the colour, not their Attack.
				if (style != null && !caller)
				{
					entry.setOption(ColorUtil.wrapWithColorTag("Attack", style.color));
				}

				int index = kept.size();
				boolean priorityHit = false;
				if (prioritiseTarget && highlights.getTargets().contains(player))
				{
					priorityIndices.put(AttackCategory.TARGET, index);
					priorityHit = true;
				}
				if (prioritisePile && highlights.getPiledPlayers().contains(player))
				{
					priorityIndices.put(AttackCategory.PILE, index);
					priorityHit = true;
				}
				if (prioritiseSnipe && highlights.getSnipedPlayers().contains(player))
				{
					priorityIndices.put(AttackCategory.SNIPE, index);
					priorityHit = true;
				}
				if (prioritiseAttacker && highlights.getAttackers().contains(player))
				{
					priorityIndices.put(AttackCategory.ATTACKER, index);
					priorityHit = true;
				}
				if (!priorityHit && player != local)
				{
					anyEnemyAttackIndex = index;
				}
				kept.add(entry);
			}
			else if (isSpellCastEntry(entry, option, selectedSpell))
			{
				// The spell name is in TARGET ("Snare -> Name (level-92)"); OPTION is just "Cast".
				String spell = isFreezeSpellName(option) ? option
					: selectedSpell != null ? selectedSpell : spellNameFromTarget(entry.getTarget());
				if (blockTeammateSpells && teammate && !isTeamSupportSpellName(spell)
					|| isBindingSpellName(spell) && (blockBindsOnHeld && freezes.isHeld(player)
					|| blockBindsOnImmune && freezes.isImmune(player)))
				{
					removedAny = true;
					removedLeftClick |= i == entries.length - 1;
					continue;
				}

				if (style != null && isFreezeSpellName(spell) && !recolourSpellTarget(entry, style))
				{
					entry.setOption(ColorUtil.wrapWithColorTag(entry.getOption(), style.color));
				}
				kept.add(entry);
			}
			else
			{
				kept.add(entry);
			}
		}

		int leftClick = -1;
		if (!shiftWalk)
		{
			leftClick = AttackCategory.resolve(Arrays.asList(config.attackPriority1(), config.attackPriority2(),
				config.attackPriority3(), config.attackPriority4()), priorityIndices);
			if (leftClick < 0)
			{
				leftClick = primaryFollowIndex >= 0 ? primaryFollowIndex
					: fallInFollowIndex >= 0 ? fallInFollowIndex : backupFollowIndex;
			}
			if (leftClick < 0 && removedLeftClick)
			{
				leftClick = anyEnemyAttackIndex;
			}
		}

		// Recolouring is already applied; only a reorder or removal needs setMenuEntries().
		if (leftClick < 0 && !removedAny)
		{
			return;
		}

		MenuEntry[] result = kept.toArray(new MenuEntry[0]);
		moveToEnd(result, leftClick);
		client.setMenuEntries(result);
	}

	private boolean isShiftWalkActive()
	{
		return config.shiftClickWalkUnderAttackablePlayers() && client.isKeyPressed(KeyCode.KC_SHIFT);
	}

	/**
	 * A manually cast spell on a player. Its option is "Cast" (the spell name is the part of TARGET
	 * before "->"); the selected spellbook widget is a second, independent signal. Using an item on
	 * a player is the same menu action, but its option is "Use" and no spell is selected.
	 */
	private static boolean isSpellCastEntry(MenuEntry entry, String option, String selectedSpell)
	{
		if (isFreezeSpellName(option))
		{
			return true;
		}
		return entry.getType() == MenuAction.WIDGET_TARGET_ON_PLAYER
			&& ("Cast".equalsIgnoreCase(option) || selectedSpell != null);
	}

	static boolean isFreezeSpellName(String name)
	{
		return name != null && FREEZE_SPELL_NAMES.contains(name.toLowerCase());
	}

	static boolean isBindingSpellName(String name)
	{
		return name != null && BINDING_SPELL_NAMES.contains(name.toLowerCase());
	}

	static boolean isTeamSupportSpellName(String name)
	{
		return name != null && TEAM_SUPPORT_SPELL_NAMES.contains(name.toLowerCase());
	}

	/**
	 * "{@code <col=00ff00>Snare</col><col=ffffff> -> <col=ffffff>Name (level-92)}" to "Snare", or
	 * null without a "->".
	 */
	static String spellNameFromTarget(String target)
	{
		int arrow = target == null ? -1 : target.indexOf("->");
		return arrow < 0 ? null : stripTags(target.substring(0, arrow));
	}

	/**
	 * Recolours just the spell name in a spell-cast TARGET. False if TARGET isn't in that format.
	 */
	private static boolean recolourSpellTarget(MenuEntry entry, HighlightStyle style)
	{
		String target = entry.getTarget();
		String spell = spellNameFromTarget(target);
		if (spell == null)
		{
			return false;
		}
		entry.setTarget(ColorUtil.wrapWithColorTag(spell, style.color) + " " + target.substring(target.indexOf("->")));
		return true;
	}

	/**
	 * The selected spellbook spell's name, e.g. "Ice Barrage", or null if no spell is selected.
	 */
	private String getSelectedSpellName()
	{
		Widget selected = client.getSelectedWidget();
		if (selected == null || WidgetUtil.componentToInterface(selected.getId()) != InterfaceID.MAGIC_SPELLBOOK)
		{
			return null;
		}
		String[] actions = selected.getActions();
		return actions == null || actions.length == 0 || actions[0] == null ? null : stripTags(actions[0]);
	}

	/**
	 * Adds a coloured tag after the target's name, once - PostMenuSort can see the same entry on
	 * several frames.
	 */
	private static void appendToTarget(MenuEntry entry, String tag, Color color)
	{
		String target = entry.getTarget();
		if (target == null || target.contains(tag))
		{
			return;
		}
		entry.setTarget(target + " " + ColorUtil.wrapWithColorTag(tag, color));
	}

	/**
	 * Moves an entry to the end of the array - the left-click slot.
	 */
	static void moveToEnd(MenuEntry[] entries, int index)
	{
		int last = entries.length - 1;
		if (index < 0 || index >= last)
		{
			return;
		}
		MenuEntry entry = entries[index];
		System.arraycopy(entries, index + 1, entries, index, last - index);
		entries[last] = entry;
	}
}
