package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.util.Text;

/**
 * Who gets highlighted, recomputed every game tick: the primary and backup callers, the primary
 * caller's target, snipe list players, piles (Pile Finder), fall-in leaders (yours and, optionally,
 * the enemy's) and players attacking you. Also caches
 * each highlighted player's HighlightStyle, which the outline/name overlays and the menu
 * recolouring read. Client thread only, except setPinnedCaller().
 */
class HighlightTracker
{
	/**
	 * How fast a new target's flash blinks. Shared by every overlay so they blink in sync.
	 */
	private static final long TARGET_FLASH_BLINK_PERIOD_MILLIS = 200;

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;
	private final FreezeTracker freezes;
	private final AttackRangeFilter range;

	/**
	 * Enabled callers from config, in list order - see setCallers().
	 */
	private volatile List<CallerEntry> enabledCallers = Collections.emptyList();
	private volatile Map<String, String> callerNicknamesByStandardName = Collections.emptyMap();
	private volatile Set<String> snipeStandardNames = Collections.emptySet();

	/**
	 * Name of the caller pinned as primary from the sidebar (EDT), or null for list order.
	 * Session-only.
	 */
	private volatile String pinnedCallerName;

	/**
	 * The pinned caller if on screen, otherwise the first on-screen enabled caller in list order.
	 */
	@Getter
	private Player primaryCaller;

	/**
	 * Every other on-screen enabled caller.
	 */
	@Getter
	private final Set<Player> backupCallers = new HashSet<>();

	/**
	 * On-screen callers' nicknames, for callers that have one.
	 */
	@Getter
	private final Map<Player, String> callerNicknames = new HashMap<>();

	/**
	 * Whoever the primary caller is interacting with, unless it's a teammate (e.g. the caller
	 * following whoever leads the fall-in).
	 */
	@Getter
	private final Set<Player> targets = new HashSet<>();

	@Getter
	private final Set<Player> snipedPlayers = new HashSet<>();

	/**
	 * Enemies at least targetedPlayerThreshold teammates are interacting with (Pile Finder).
	 */
	@Getter
	private final Set<Player> piledPlayers = new HashSet<>();

	/**
	 * Teammates at least fallInThreshold other teammates are interacting with - leading a fall-in
	 * (MenuHandler makes their Follow the left-click if prioritiseFallInFollow is on).
	 */
	@Getter
	private final Set<Player> fallInLeaders = new HashSet<>();

	/**
	 * Enemies at least enemyFallInThreshold other enemies are following - their fall-in leader.
	 */
	@Getter
	private final Set<Player> enemyFallInLeaders = new HashSet<>();

	/**
	 * Enemies interacting with you, in the order they started, capped at maxAttackers so the cap
	 * keeps whoever engaged you first.
	 */
	@Getter
	private final Set<Player> attackers = new LinkedHashSet<>();

	private final Map<Player, Long> targetFlashEndTimes = new HashMap<>();
	private Set<Player> previousTargets = new HashSet<>();

	private Map<Player, HighlightStyle> cachedStyles = Collections.emptyMap();

	@Inject
	HighlightTracker(Client client, PrepToolsConfig config, TeamTracker team, FreezeTracker freezes, AttackRangeFilter range)
	{
		this.client = client;
		this.config = config;
		this.team = team;
		this.freezes = freezes;
		this.range = range;
	}

	/**
	 * Called when the callers config changes (and at startup).
	 */
	void setCallers(List<CallerEntry> callers)
	{
		List<CallerEntry> enabled = new ArrayList<>();
		Map<String, String> nicknames = new HashMap<>();
		for (CallerEntry entry : callers)
		{
			if (entry.nickname != null)
			{
				nicknames.put(Text.standardize(entry.name), entry.nickname);
			}
			if (entry.enabled)
			{
				enabled.add(entry);
			}
		}
		enabledCallers = enabled;
		callerNicknamesByStandardName = nicknames;

		String pinned = pinnedCallerName;
		if (pinned != null && enabled.stream().noneMatch(e -> Text.standardize(e.name).equals(Text.standardize(pinned))))
		{
			// Removed or disabled.
			pinnedCallerName = null;
		}
	}

	void setSnipeNames(List<String> names)
	{
		Set<String> standard = new HashSet<>();
		for (String name : names)
		{
			standard.add(Text.standardize(name));
		}
		snipeStandardNames = standard;
	}

	void setPinnedCaller(String name)
	{
		pinnedCallerName = name;
	}

	/**
	 * Keeps a pin on a caller who was just renamed in the sidebar.
	 */
	void renamePinnedCaller(String oldName, String newName)
	{
		String pinned = pinnedCallerName;
		if (pinned != null && Text.standardize(pinned).equals(Text.standardize(oldName)))
		{
			pinnedCallerName = newName;
		}
	}

	/**
	 * A configured caller's nickname by standardized name, whether or not they're on screen.
	 */
	String nicknameFor(String standardName)
	{
		return callerNicknamesByStandardName.get(standardName);
	}

	void onGameTick()
	{
		updateCallersAndTargets();
		updateSnipes();
		updatePiles();
		updateFallIn();
		updateAttackers();
		rebuildStyles();
	}

	void clear()
	{
		primaryCaller = null;
		backupCallers.clear();
		callerNicknames.clear();
		targets.clear();
		snipedPlayers.clear();
		piledPlayers.clear();
		fallInLeaders.clear();
		enemyFallInLeaders.clear();
		attackers.clear();
		targetFlashEndTimes.clear();
		previousTargets = new HashSet<>();
		cachedStyles = Collections.emptyMap();
	}

	/**
	 * Every highlighted player's style for this frame: the tick-cached styles, plus the new-target
	 * flash blink. Allocates only on a frame where a target is mid-blink.
	 */
	Map<Player, HighlightStyle> getStyles()
	{
		if (targets.isEmpty() || (System.currentTimeMillis() / TARGET_FLASH_BLINK_PERIOD_MILLIS) % 2 != 0)
		{
			return cachedStyles;
		}

		Map<Player, HighlightStyle> styles = null;
		for (Player target : targets)
		{
			HighlightStyle style = cachedStyles.get(target);
			if (style != null && isFlashingTarget(target))
			{
				if (styles == null)
				{
					styles = new HashMap<>(cachedStyles);
				}
				styles.put(target, style.withColor(config.targetFlashColor()));
			}
		}
		return styles != null ? styles : cachedStyles;
	}

	private boolean isFlashingTarget(Player player)
	{
		if (!config.showTargetFlash())
		{
			return false;
		}
		Long end = targetFlashEndTimes.get(player);
		return end != null && System.currentTimeMillis() < end;
	}

	/**
	 * A pinned caller who's on screen is always primary; otherwise the first on-screen caller in
	 * list order. So a pin holds while they're around, falls back while they're away, and resumes
	 * when they return.
	 */
	private void updateCallersAndTargets()
	{
		primaryCaller = null;
		backupCallers.clear();
		callerNicknames.clear();
		targets.clear();

		if (!enabledCallers.isEmpty())
		{
			Map<String, Player> onScreen = new HashMap<>();
			for (Player player : client.getPlayers())
			{
				String standard = team.standardName(player);
				if (standard != null)
				{
					onScreen.put(standard, player);
				}
			}

			String pinned = pinnedCallerName;
			if (pinned != null)
			{
				primaryCaller = onScreen.get(Text.standardize(pinned));
			}

			for (CallerEntry entry : enabledCallers)
			{
				Player player = onScreen.get(Text.standardize(entry.name));
				if (player == null)
				{
					continue;
				}
				if (primaryCaller == null)
				{
					primaryCaller = player;
				}
				if (player != primaryCaller)
				{
					backupCallers.add(player);
				}
				if (entry.nickname != null)
				{
					callerNicknames.put(player, entry.nickname);
				}
			}

			if (primaryCaller != null)
			{
				Actor interacting = primaryCaller.getInteracting();
				if (interacting instanceof Player && interacting != primaryCaller && !team.isOwnTeam((Player) interacting))
				{
					targets.add((Player) interacting);
				}
			}
		}

		long now = System.currentTimeMillis();
		long flashEnd = now + Math.max(0, config.targetFlashDurationMillis());
		for (Player target : targets)
		{
			if (!previousTargets.contains(target))
			{
				targetFlashEndTimes.put(target, flashEnd);
			}
		}
		targetFlashEndTimes.entrySet().removeIf(e -> e.getValue() < now);
		previousTargets = new HashSet<>(targets);
	}

	private void updateSnipes()
	{
		snipedPlayers.clear();
		if (snipeStandardNames.isEmpty())
		{
			return;
		}

		for (Player player : client.getPlayers())
		{
			String standard = team.standardName(player);
			if (standard != null && snipeStandardNames.contains(standard))
			{
				snipedPlayers.add(player);
			}
		}
	}

	/**
	 * A teammate is leading a fall-in when enough teammates follow them. Teammates don't attack
	 * each other, so a teammate interacting with a teammate is following them. Same for the enemy:
	 * an enemy interacting with another enemy is taken as following, unless both wear team capes
	 * and they differ - then it's two other clans fighting.
	 */
	private void updateFallIn()
	{
		fallInLeaders.clear();
		enemyFallInLeaders.clear();
		boolean own = config.prioritiseFallInFollow() || config.highlightFallInLeader();
		boolean enemy = config.enemyFallInFinder();
		if (!own && !enemy)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		Map<Player, Integer> ownCounts = new HashMap<>();
		Map<Player, Integer> enemyCounts = new HashMap<>();
		for (Player player : client.getPlayers())
		{
			if (player == null)
			{
				continue;
			}
			// You count as a follower, never as a leader.
			Actor interacting = player.getInteracting();
			if (!(interacting instanceof Player) || interacting == local || interacting == player)
			{
				continue;
			}
			Player leader = (Player) interacting;
			boolean followerOnTeam = team.isOwnTeam(player);
			if (followerOnTeam != team.isOwnTeam(leader))
			{
				continue;
			}
			if (followerOnTeam)
			{
				if (own)
				{
					ownCounts.merge(leader, 1, Integer::sum);
				}
			}
			else if (enemy && !(player.getTeam() > 0 && leader.getTeam() > 0 && player.getTeam() != leader.getTeam()))
			{
				enemyCounts.merge(leader, 1, Integer::sum);
			}
		}

		addOverThreshold(ownCounts, config.fallInThreshold(), fallInLeaders);
		addOverThreshold(enemyCounts, config.enemyFallInThreshold(), enemyFallInLeaders);
	}

	private static void addOverThreshold(Map<Player, Integer> counts, int threshold, Set<Player> into)
	{
		int min = Math.max(1, threshold);
		counts.forEach((player, count) ->
		{
			if (count >= min)
			{
				into.add(player);
			}
		});
	}

	/**
	 * An uncalled pile: an enemy that at least targetedPlayerThreshold of your team (you included)
	 * are interacting with. Team-on-team interactions (sparring, following) never count.
	 */
	private void updatePiles()
	{
		piledPlayers.clear();
		if (!config.enableTargetedPlayerDetection())
		{
			return;
		}

		Map<Player, Integer> counts = new HashMap<>();
		for (Player player : client.getPlayers())
		{
			if (!team.isOwnTeam(player))
			{
				continue;
			}

			Actor interacting = player.getInteracting();
			if (interacting instanceof Player && !team.isOwnTeam((Player) interacting))
			{
				counts.merge((Player) interacting, 1, Integer::sum);
			}
		}

		int threshold = Math.max(1, config.targetedPlayerThreshold());
		counts.forEach((player, count) ->
		{
			if (count >= threshold)
			{
				piledPlayers.add(player);
			}
		});
	}

	/**
	 * Players already in the set keep their place, newcomers go to the back, and the set is cut to
	 * maxAttackers (0 = no limit) from the back.
	 */
	private void updateAttackers()
	{
		Player local = client.getLocalPlayer();
		if (!config.highlightAttackers() || local == null)
		{
			attackers.clear();
			return;
		}

		Set<Player> current = new HashSet<>();
		for (Player player : client.getPlayers())
		{
			if (player != null && player != local && player.getInteracting() == local && !team.isOwnTeam(player))
			{
				current.add(player);
			}
		}

		attackers.retainAll(current);
		attackers.addAll(current);

		int max = config.maxAttackers();
		if (max > 0)
		{
			int kept = 0;
			for (Iterator<Player> it = attackers.iterator(); it.hasNext(); )
			{
				it.next();
				if (++kept > max)
				{
					it.remove();
				}
			}
		}
	}

	/**
	 * Highlight priority when a player is in more than one category, lowest to highest: backup
	 * caller, fall-in leader, enemy fall-in leader, attacker, snipe, pile, primary caller,
	 * target - later puts win. A frozen pile or
	 * target gets its frozen colour (FreezeTracker has already moved on to this tick), and an
	 * out-of-range snipe/pile/target the out-of-range colour over all of that (AttackRangeFilter
	 * has also moved on).
	 */
	private void rebuildStyles()
	{
		Map<Player, HighlightStyle> styles = new HashMap<>();
		HighlightStyle base = new HighlightStyle(null, config.outlineWidth(), config.nameFontStyle(),
			config.nameFontSize(), config.highlightShape(), config.dotSize());

		putAll(styles, backupCallers, base.withColor(config.backupCallerColor()));
		if (config.highlightFallInLeader())
		{
			putAll(styles, fallInLeaders, base.withColor(config.fallInLeaderColor()));
		}
		putAll(styles, enemyFallInLeaders, base.withColor(config.enemyFallInColor()));
		putAll(styles, attackers, base.withColor(config.attackerColor()));
		putAll(styles, snipedPlayers, base.withColor(config.snipeColor()));
		putAll(styles, piledPlayers, base.withColor(config.targetedPlayerColor()), base.withColor(config.frozenPileColor()));
		if (primaryCaller != null)
		{
			styles.put(primaryCaller, base.withColor(config.callerColor()));
		}
		putAll(styles, targets, base.withColor(config.targetColor()), base.withColor(config.frozenTargetColor()));

		// Experimental: a target/pile/snipe you can't attack is greyed out, whatever it was.
		if (range.isEnabled())
		{
			HighlightStyle outOfRange = base.withColor(config.outOfRangeColor());
			for (Set<Player> players : List.of(enemyFallInLeaders, snipedPlayers, piledPlayers, targets))
			{
				for (Player player : players)
				{
					if (range.isOutOfRange(player))
					{
						styles.put(player, outOfRange);
					}
				}
			}
		}

		cachedStyles = styles;
	}

	private void putAll(Map<Player, HighlightStyle> styles, Set<Player> players, HighlightStyle style,
		HighlightStyle frozenStyle)
	{
		for (Player player : players)
		{
			styles.put(player, freezes.isFrozen(player) ? frozenStyle : style);
		}
	}

	private static void putAll(Map<Player, HighlightStyle> styles, Set<Player> players, HighlightStyle style)
	{
		for (Player player : players)
		{
			styles.put(player, style);
		}
	}
}
