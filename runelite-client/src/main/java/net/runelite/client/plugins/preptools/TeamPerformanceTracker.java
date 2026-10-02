package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.Player;
import net.runelite.api.gameval.AnimationID;
import net.runelite.client.util.Text;

/**
 * Experimental: per-teammate fight numbers for the sidebar's Stats tab, to spot who's pulling
 * their weight. Only what the client can actually see about other players:
 * <ul>
 * <li>On target - share of the time the primary caller had a target that they were interacting
 * with it. The most reliable number here.</li>
 * <li>Switch time - ticks from a new target being called to them interacting with it, however
 * long it takes. A target re-called before they got there just isn't measured - no time limit
 * and no "missed" count.</li>
 * <li>Attacks - attack animations (anything but eating, blocking or dying) while interacting with
 * an enemy, and how many of those were on the called target.</li>
 * <li>Damage taken (hitsplats on them), eats, deaths, and kills involved (an enemy died while they'd
 * been attacking them in the last KILL_CREDIT_TICKS).</li>
 * </ul>
 * Damage dealt by teammates isn't visible - a hitsplat doesn't say who caused it - so only your
 * own is counted. Everything is keyed by name and kept until reset (survives hops), and only
 * counts while the player is on your screen. Client thread only.
 */
class TeamPerformanceTracker
{
	/**
	 * How recently a teammate must have been on an enemy to share in their death.
	 */
	static final int KILL_CREDIT_TICKS = 10;

	/**
	 * Defensive (block) animations a player plays when hit - not attacks.
	 */
	private static final Set<Integer> BLOCK_ANIMATION_IDS = Set.of(
		AnimationID.HUMAN_DDAGGER_BLOCK, AnimationID.HUMAN_DSPEAR_BLOCK, AnimationID.HUMAN_SWORD_BLOCK,
		AnimationID.HUMAN_SWORD_DEF, AnimationID.HUMAN_AXE_BLOCK, AnimationID.HUMAN_BLUNT_BLOCK,
		AnimationID.HUMAN_DHSWORD_BLOCK, AnimationID.HUMAN_STAFF_BLOCK, AnimationID.HUMAN_STAFFORB_BLOCK,
		AnimationID.HUMAN_UNARMEDBLOCK, AnimationID.HUMAN_SPEAR_BLOCK, AnimationID.HUMAN_SCYTHE_BLOCK,
		AnimationID.HUMAN_SHIELD_DEFENCE, AnimationID.HUMAN_BANNER_BLOCK, AnimationID.HUMAN_ZAMORAKSPEAR_BLOCK,
		AnimationID.HUMAN_DINHS_BULWARK_BLOCK, AnimationID.HUMAN_ELDER_MAUL_BLOCK, AnimationID.HUMAN_HALLOWFELL_BLOCK,
		AnimationID.HUMAN_STAFF_BLOCK_LEATHER_HIT_3, AnimationID.HUMAN_UNARMEDBLOCK_LEATHER_HIT_3,
		AnimationID.HUMAN_SPEAR_BLOCK_LEATHER_HIT_3);

	private static final Set<Integer> DEATH_ANIMATION_IDS = Set.of(AnimationID.HUMAN_DEATH, AnimationID.HUMAN_DEATH_BACKWARDS);

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	private static final class Stats
	{
		String displayName;
		boolean isYou;
		boolean isCaller;
		int ticksSeen;
		int targetTicks;
		int onTargetTicks;
		int attacks;
		int attacksOnTarget;
		int switches;
		int switchTicks;
		int damageTaken;
		int damageDealt;
		int eats;
		int deaths;
		int killsInvolved;
	}

	private final Map<String, Stats> stats = new HashMap<>();

	private Player currentTarget;
	private int targetSinceTick;
	/**
	 * Teammates on screen when the current target was called who haven't reached it yet.
	 */
	private final Set<String> pendingSwitches = new HashSet<>();

	/**
	 * Enemy to the teammates interacting with them, and the last tick each did - kill credit.
	 */
	private final Map<Player, Map<String, Integer>> recentAttackers = new HashMap<>();

	/**
	 * Last death tick per name - the death animation can be applied more than once.
	 */
	private final Map<String, Integer> lastDeathTick = new HashMap<>();

	@Inject
	TeamPerformanceTracker(Client client, PrepToolsConfig config, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.team = team;
	}

	/**
	 * Call after HighlightTracker, which decides the primary caller and their target.
	 */
	void onGameTick(Player primaryCaller, Set<Player> targets)
	{
		if (!config.enablePerformanceTracker())
		{
			return;
		}

		int tick = client.getTickCount();
		Player target = targets.isEmpty() ? null : targets.iterator().next();
		Player local = client.getLocalPlayer();

		if (target != currentTarget)
		{
			pendingSwitches.clear();
			currentTarget = target;
			targetSinceTick = tick;
		}

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null || !team.isOwnTeam(player))
			{
				continue;
			}

			String name = team.standardName(player);
			Stats s = statsFor(name, player);
			s.isYou = player == local;
			s.isCaller = team.isConfiguredCaller(player);
			s.ticksSeen++;

			Actor interacting = player.getInteracting();
			if (interacting instanceof Player && !team.isOwnTeam((Player) interacting))
			{
				recentAttackers.computeIfAbsent((Player) interacting, p -> new HashMap<>()).put(name, tick);
			}

			if (target == null || player == primaryCaller)
			{
				continue;
			}

			// Already on it when it's called counts as a 0-tick switch.
			if (tick == targetSinceTick)
			{
				pendingSwitches.add(name);
			}
			s.targetTicks++;
			if (interacting == target)
			{
				s.onTargetTicks++;
				if (pendingSwitches.remove(name))
				{
					s.switches++;
					s.switchTicks += tick - targetSinceTick;
				}
			}
		}

		// Drop kill-credit entries too old to matter, and enemies nobody's been on lately.
		for (Iterator<Map<String, Integer>> it = recentAttackers.values().iterator(); it.hasNext(); )
		{
			Map<String, Integer> byName = it.next();
			byName.values().removeIf(t -> tick - t > KILL_CREDIT_TICKS);
			if (byName.isEmpty())
			{
				it.remove();
			}
		}
	}

	void onAnimationChanged(Player player)
	{
		if (!config.enablePerformanceTracker() || !team.isOwnTeam(player))
		{
			return;
		}

		int animation = player.getAnimation();
		if (animation == -1 || DEATH_ANIMATION_IDS.contains(animation) || BLOCK_ANIMATION_IDS.contains(animation))
		{
			return;
		}

		Stats s = statsFor(team.standardName(player), player);
		if (FoodTracker.EAT_ANIMATION_IDS.contains(animation))
		{
			s.eats++;
			return;
		}

		Actor interacting = player.getInteracting();
		if (interacting instanceof Player && !team.isOwnTeam((Player) interacting))
		{
			s.attacks++;
			if (interacting == currentTarget)
			{
				s.attacksOnTarget++;
			}
		}
	}

	/**
	 * A player's death animation (or your own ActorDeath) - counted once per death.
	 */
	void onDeath(Player player)
	{
		String name = team.standardName(player);
		if (!config.enablePerformanceTracker() || name == null)
		{
			return;
		}

		int tick = client.getTickCount();
		Integer last = lastDeathTick.put(name, tick);
		if (last != null && tick - last < KILL_CREDIT_TICKS)
		{
			return;
		}

		if (team.isOwnTeam(player))
		{
			statsFor(name, player).deaths++;
			return;
		}

		Map<String, Integer> attackers = recentAttackers.remove(player);
		if (attackers != null)
		{
			for (String attacker : attackers.keySet())
			{
				Stats s = stats.get(attacker);
				if (s != null)
				{
					s.killsInvolved++;
				}
			}
		}
	}

	void onHitsplatApplied(Actor actor, Hitsplat hitsplat)
	{
		if (!config.enablePerformanceTracker() || !(actor instanceof Player) || hitsplat.getAmount() <= 0
			|| !(hitsplat.isMine() || hitsplat.isOthers()))
		{
			return;
		}

		Player player = (Player) actor;
		if (team.isOwnTeam(player))
		{
			statsFor(team.standardName(player), player).damageTaken += hitsplat.getAmount();
		}
		else if (hitsplat.isMine() && client.getLocalPlayer() != null)
		{
			Player local = client.getLocalPlayer();
			statsFor(team.standardName(local), local).damageDealt += hitsplat.getAmount();
		}
	}

	/**
	 * A player left view - stop holding on to them as an enemy.
	 */
	void forget(Player player)
	{
		recentAttackers.remove(player);
		if (player == currentTarget)
		{
			currentTarget = null;
			pendingSwitches.clear();
		}
	}

	/**
	 * Hop/logout: the Player objects are gone, but the numbers are kept.
	 */
	void clearPlayers()
	{
		recentAttackers.clear();
		currentTarget = null;
		pendingSwitches.clear();
	}

	/**
	 * The sidebar's Reset button.
	 */
	void reset()
	{
		stats.clear();
		lastDeathTick.clear();
		clearPlayers();
	}

	List<PerformanceRow> snapshot()
	{
		List<PerformanceRow> rows = new ArrayList<>();
		for (Stats s : stats.values())
		{
			rows.add(new PerformanceRow(s.displayName, s.isYou, s.isCaller, s.ticksSeen, s.targetTicks, s.onTargetTicks,
				s.attacks, s.attacksOnTarget, s.switches, s.switchTicks, s.damageTaken,
				s.isYou ? s.damageDealt : -1, s.eats, s.deaths, s.killsInvolved, PerformanceRow.Rating.UNRATED));
		}
		return PerformanceRow.rate(rows, config.performanceMinTargetSeconds() * 10 / 6);
	}

	private Stats statsFor(String name, Player player)
	{
		Stats s = stats.computeIfAbsent(name, n -> new Stats());
		s.displayName = Text.removeTags(player.getName());
		return s;
	}
}
