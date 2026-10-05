package net.runelite.client.plugins.preptools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
 * Experimental: per-teammate fight numbers and a fight log for the sidebar's Stats tab, per round
 * (entering the Clan Wars portal starts a new one - startRound(); earlier rounds stay viewable
 * until Reset). Only what the client can actually see about other players, and only while they're
 * on your screen:
 * <ul>
 * <li><b>Called targets count only if they die.</b> Callers chip at people before settling on the
 * real target - that's normal, and shouldn't count against anyone. So everything about a called
 * target (time on it, switch time, attacks on it) is held per target and only added to the stats
 * when that target dies; a target not re-called for TARGET_EXPIRY_TICKS without dying is dropped.
 * On target - share of a killed target's called time a teammate spent interacting with it. Switch
 * time - ticks from the call to reaching it.</li>
 * <li>On pile - enemies who died while they were on them: interacting with them, or an attack
 * animation at them, in the last PILE_CREDIT_TICKS before the death (generous: a pile on someone
 * eating runs long between hits, and interacting drops the moment the target dies).</li>
 * <li>The fight log: every death. An enemy's - who was on the pile, and if called, by whom and
 * how long it took. A teammate's - how many enemies were on them, how long they were under attack,
 * how many pizzas they ate meanwhile, recent damage, frozen or not.</li>
 * <li><b>Easy deaths</b> - flagged only on clear evidence (isEasyDeath()): seen the whole time,
 * few attackers, under attack long enough to eat, and barely ate. Dying to a big pile is never
 * flagged.</li>
 * </ul>
 * Damage dealt by teammates isn't visible - a hitsplat doesn't say who caused it - so only your
 * own is counted. Kept by name. Client thread only.
 */
class TeamPerformanceTracker
{
	/**
	 * How recently (ticks, about 10s) a teammate must have been on an enemy to count as on the pile
	 * when they died. Also how long a repeated death event for the same player is ignored, and how
	 * soon after the last call a death still counts as the called target's.
	 */
	static final int PILE_CREDIT_TICKS = 16;

	/**
	 * A called target not called again for this long (30s) without dying was a chip call - dropped.
	 */
	static final int TARGET_EXPIRY_TICKS = 50;

	/**
	 * The enemies on a teammate in this many ticks (about 10s) before they died are their attackers.
	 */
	static final int PRESSURE_WINDOW_TICKS = 16;

	/**
	 * "Under attack" ends once nobody's been on them for this long (6s).
	 */
	static final int PRESSURE_GAP_TICKS = 10;

	/**
	 * Rounds kept for the Stats tab - the oldest goes first.
	 */
	static final int MAX_ROUNDS = 20;

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
	private final FreezeTracker freezes;

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
		int easyDeaths;
		int onPile;
	}

	private static final class Round
	{
		final long startMillis;
		final Map<String, Stats> stats = new HashMap<>();
		final List<FightLogEntry> log = new ArrayList<>();

		Round(long startMillis)
		{
			this.startMillis = startMillis;
		}
	}

	/**
	 * A teammate's part in one called target, held until the target dies.
	 */
	private static final class TargetPart
	{
		int targetTicks;
		int onTargetTicks;
		int switches;
		int switchTicks;
		int attacksOnTarget;
	}

	/**
	 * One called target, from the first call until they die or the call goes stale.
	 */
	private static final class CalledTarget
	{
		final String caller;
		final int firstCalledTick;
		int lastCalledTick;
		final Map<String, TargetPart> parts = new HashMap<>();

		CalledTarget(String caller, int tick)
		{
			this.caller = caller;
			this.firstCalledTick = tick;
			this.lastCalledTick = tick;
		}
	}

	/**
	 * Who's been on a teammate, and what they did about it.
	 */
	private static final class Pressure
	{
		/**
		 * Enemy display name to the last tick they were on this teammate.
		 */
		final Map<String, Integer> attackers = new HashMap<>();
		/**
		 * First tick of the current spell under attack, or -1.
		 */
		int since = -1;
		int lastAttackedTick = Integer.MIN_VALUE / 2;
		int pizzasEaten;
		boolean offScreen;
		int lastSeenTick;
		/**
		 * {tick, damage} of recent hits.
		 */
		final Deque<int[]> hits = new ArrayDeque<>();
	}

	private final List<Round> rounds = new ArrayList<>();
	private Round round;

	private Player currentTarget;
	private CalledTarget currentCall;
	private int targetSinceTick;
	/**
	 * Teammates on screen when the current target was called who haven't reached it yet.
	 */
	private final Set<String> pendingSwitches = new HashSet<>();

	/**
	 * Called targets by standardized name, until they die or the call goes stale.
	 */
	private final Map<String, CalledTarget> calledTargets = new HashMap<>();

	/**
	 * Enemy to the teammates on them, and the last tick each was - on pile credit.
	 */
	private final Map<Player, Map<String, Integer>> recentAttackers = new HashMap<>();

	/**
	 * Teammate (standardized name) to who's on them.
	 */
	private final Map<String, Pressure> pressure = new HashMap<>();

	/**
	 * Last death tick per name - the death animation and ActorDeath can both report one death.
	 */
	private final Map<String, Integer> lastDeathTick = new HashMap<>();

	@Inject
	TeamPerformanceTracker(Client client, PrepToolsConfig config, TeamTracker team, FreezeTracker freezes)
	{
		this.client = client;
		this.config = config;
		this.team = team;
		this.freezes = freezes;
		reset();
	}

	/**
	 * You went through the Clan Wars portal: a new round, its own numbers and log. A round nothing
	 * was recorded in is just restarted rather than kept.
	 */
	void startRound()
	{
		if (round.stats.isEmpty() && round.log.isEmpty())
		{
			rounds.remove(round);
		}
		round = new Round(System.currentTimeMillis());
		rounds.add(round);
		while (rounds.size() > MAX_ROUNDS)
		{
			rounds.remove(0);
		}
		lastDeathTick.clear();
		clearPlayers();
	}

	/**
	 * The sidebar's Reset button: every round gone, a fresh one started.
	 */
	void reset()
	{
		rounds.clear();
		round = new Round(System.currentTimeMillis());
		rounds.add(round);
		lastDeathTick.clear();
		clearPlayers();
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
			String targetName = team.standardName(target);
			currentCall = targetName == null ? null : calledTargets.computeIfAbsent(targetName,
				n -> new CalledTarget(primaryCaller != null ? Text.removeTags(primaryCaller.getName()) : null, tick));
		}
		if (currentCall != null)
		{
			currentCall.lastCalledTick = tick;
		}

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}
			Actor interacting = player.getInteracting();
			if (team.isOwnTeam(player))
			{
				tickTeammate(player, interacting, local, primaryCaller, target, tick);
			}
			else if (interacting instanceof Player && team.isOwnTeam((Player) interacting))
			{
				underAttack((Player) interacting, player, tick);
			}
		}

		for (Pressure p : pressure.values())
		{
			if (p.since >= 0 && tick - p.lastAttackedTick > PRESSURE_GAP_TICKS)
			{
				p.since = -1;
			}
			if (p.since >= 0 && p.lastSeenTick < tick)
			{
				p.offScreen = true;
			}
			p.attackers.values().removeIf(t -> tick - t > PRESSURE_WINDOW_TICKS);
			while (!p.hits.isEmpty() && tick - p.hits.peekFirst()[0] > PRESSURE_WINDOW_TICKS)
			{
				p.hits.removeFirst();
			}
		}

		// Chip calls: not called for a while and still alive.
		calledTargets.values().removeIf(c -> c != currentCall && tick - c.lastCalledTick > TARGET_EXPIRY_TICKS);

		// Drop pile credit too old to matter, and enemies nobody's been on lately.
		for (Iterator<Map<String, Integer>> it = recentAttackers.values().iterator(); it.hasNext(); )
		{
			Map<String, Integer> byName = it.next();
			byName.values().removeIf(t -> tick - t > PILE_CREDIT_TICKS);
			if (byName.isEmpty())
			{
				it.remove();
			}
		}
	}

	private void tickTeammate(Player player, Actor interacting, Player local, Player primaryCaller, Player target, int tick)
	{
		String name = team.standardName(player);
		Stats s = statsFor(name, player);
		s.isYou = player == local;
		s.isCaller = team.isConfiguredCaller(player);
		s.ticksSeen++;
		pressureFor(name).lastSeenTick = tick;

		if (interacting instanceof Player && !team.isOwnTeam((Player) interacting))
		{
			recentAttackers.computeIfAbsent((Player) interacting, p -> new HashMap<>()).put(name, tick);
		}

		if (target == null || player == primaryCaller || currentCall == null)
		{
			return;
		}

		TargetPart part = currentCall.parts.computeIfAbsent(name, n -> new TargetPart());
		// Already on it when it's called counts as a 0-tick switch.
		if (tick == targetSinceTick)
		{
			pendingSwitches.add(name);
		}
		part.targetTicks++;
		if (interacting == target)
		{
			part.onTargetTicks++;
			if (pendingSwitches.remove(name))
			{
				part.switches++;
				part.switchTicks += tick - targetSinceTick;
			}
		}
	}

	private void underAttack(Player teammate, Player enemy, int tick)
	{
		String name = team.standardName(teammate);
		if (name == null)
		{
			return;
		}
		Pressure p = pressureFor(name);
		if (p.since < 0)
		{
			p.since = tick;
			p.pizzasEaten = 0;
			p.offScreen = false;
		}
		p.lastAttackedTick = tick;
		p.attackers.put(Text.removeTags(enemy.getName()), tick);
	}

	void onAnimationChanged(Player player)
	{
		if (!config.enablePerformanceTracker())
		{
			return;
		}

		int animation = player.getAnimation();
		if (animation == -1 || DEATH_ANIMATION_IDS.contains(animation) || BLOCK_ANIMATION_IDS.contains(animation))
		{
			return;
		}

		Actor interacting = player.getInteracting();
		int tick = client.getTickCount();
		if (!team.isOwnTeam(player))
		{
			// An enemy's attack at a teammate counts as being on them, as interacting does.
			if (interacting instanceof Player && team.isOwnTeam((Player) interacting)
				&& !FoodTracker.EAT_ANIMATION_IDS.contains(animation))
			{
				underAttack((Player) interacting, player, tick);
			}
			return;
		}

		String name = team.standardName(player);
		Stats s = statsFor(name, player);
		if (FoodTracker.EAT_ANIMATION_IDS.contains(animation))
		{
			s.eats++;
			Pressure p = pressureFor(name);
			if (p.since >= 0)
			{
				p.pizzasEaten++;
			}
			return;
		}

		if (interacting instanceof Player && !team.isOwnTeam((Player) interacting))
		{
			recentAttackers.computeIfAbsent((Player) interacting, p -> new HashMap<>()).put(name, tick);
			s.attacks++;
			if (interacting == currentTarget && currentCall != null)
			{
				currentCall.parts.computeIfAbsent(name, n -> new TargetPart()).attacksOnTarget++;
			}
		}
	}

	/**
	 * A player's death animation (or ActorDeath) - counted once per death.
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
		if (last != null && tick - last < PILE_CREDIT_TICKS)
		{
			return;
		}

		if (team.isOwnTeam(player))
		{
			teammateDied(player, name, tick);
		}
		else
		{
			enemyDied(player, name, tick);
		}
	}

	private void teammateDied(Player player, String name, int tick)
	{
		Stats s = statsFor(name, player);
		s.deaths++;

		Pressure p = pressure.remove(name);
		List<String> attackers = new ArrayList<>();
		int underAttackTicks = -1;
		int pizzas = 0;
		int damage = 0;
		boolean offScreen = false;
		if (p != null)
		{
			p.attackers.forEach((enemy, t) ->
			{
				if (tick - t <= PRESSURE_WINDOW_TICKS)
				{
					attackers.add(enemy);
				}
			});
			attackers.sort(String.CASE_INSENSITIVE_ORDER);
			underAttackTicks = p.since >= 0 ? tick - p.since : -1;
			pizzas = p.pizzasEaten;
			offScreen = p.offScreen;
			for (int[] hit : p.hits)
			{
				damage += hit[1];
			}
		}

		boolean easy = isEasyDeath(attackers.size(), underAttackTicks, pizzas, offScreen,
			config.easyDeathMaxAttackers(), config.easyDeathMinSeconds() * 10 / 6, config.easyDeathMaxPizzas());
		if (easy)
		{
			s.easyDeaths++;
		}
		round.log.add(new FightLogEntry(System.currentTimeMillis(), true, s.displayName, false, null, underAttackTicks,
			attackers, pizzas, damage, freezes.isFrozen(player), offScreen, easy));
	}

	private void enemyDied(Player player, String name, int tick)
	{
		Map<String, Integer> pile = recentAttackers.remove(player);
		Set<String> onPile = pile != null ? pile.keySet() : Collections.emptySet();
		for (String mate : onPile)
		{
			Stats s = round.stats.get(mate);
			if (s != null)
			{
				s.onPile++;
			}
		}

		CalledTarget call = calledTargets.remove(name);
		boolean called = call != null && (call == currentCall || tick - call.lastCalledTick <= PILE_CREDIT_TICKS);
		if (called)
		{
			// The target died: now its numbers count.
			call.parts.forEach((mate, part) ->
			{
				Stats s = round.stats.get(mate);
				if (s != null)
				{
					s.targetTicks += part.targetTicks;
					s.onTargetTicks += part.onTargetTicks;
					s.switches += part.switches;
					s.switchTicks += part.switchTicks;
					s.attacksOnTarget += part.attacksOnTarget;
				}
			});
		}
		if (call != null && call == currentCall)
		{
			currentTarget = null;
			currentCall = null;
			pendingSwitches.clear();
		}

		// Most time on target first, then by name.
		List<String> names = new ArrayList<>(onPile);
		names.sort((a, b) ->
		{
			int byTime = called ? Integer.compare(onTargetTicks(call, b), onTargetTicks(call, a)) : 0;
			return byTime != 0 ? byTime : String.CASE_INSENSITIVE_ORDER.compare(displayName(a), displayName(b));
		});
		List<String> display = new ArrayList<>();
		for (String mate : names)
		{
			display.add(displayName(mate));
		}
		round.log.add(new FightLogEntry(System.currentTimeMillis(), false, Text.removeTags(player.getName()), called,
			called ? call.caller : null, called ? tick - call.firstCalledTick : -1, display, 0, 0, false, false, false));
	}

	private static int onTargetTicks(CalledTarget call, String mate)
	{
		TargetPart part = call.parts.get(mate);
		return part != null ? part.onTargetTicks : 0;
	}

	private String displayName(String standardName)
	{
		Stats s = round.stats.get(standardName);
		return s != null && s.displayName != null ? s.displayName : standardName;
	}

	/**
	 * A death that's on them, with the evidence to back it: they were on your screen the whole
	 * time they were under attack (else what you saw is incomplete), no more than maxAttackers were
	 * on them, they were under attack long enough to eat (minTicks), and they ate no more than
	 * maxPizzas. Any doubt - a big pile, a quick burst, gaps in what you saw - and it's not flagged.
	 */
	static boolean isEasyDeath(int attackers, int underAttackTicks, int pizzasEaten, boolean partlyOffScreen,
		int maxAttackers, int minTicks, int maxPizzas)
	{
		return !partlyOffScreen && attackers > 0 && attackers <= maxAttackers && underAttackTicks >= minTicks
			&& pizzasEaten <= maxPizzas;
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
			String name = team.standardName(player);
			statsFor(name, player).damageTaken += hitsplat.getAmount();
			pressureFor(name).hits.addLast(new int[]{client.getTickCount(), hitsplat.getAmount()});
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
			currentCall = null;
			pendingSwitches.clear();
		}
	}

	/**
	 * Hop/logout/new round: the Player objects and the fight in progress are gone, the numbers aren't.
	 */
	void clearPlayers()
	{
		recentAttackers.clear();
		calledTargets.clear();
		pressure.clear();
		currentTarget = null;
		currentCall = null;
		pendingSwitches.clear();
	}

	/**
	 * Every round, oldest first - the last is the one being recorded.
	 */
	List<PerformanceRound> snapshot()
	{
		int minTargetTicks = config.performanceMinTargetSeconds() * 10 / 6;
		List<PerformanceRound> result = new ArrayList<>();
		for (Round r : rounds)
		{
			List<PerformanceRow> rows = new ArrayList<>();
			for (Stats s : r.stats.values())
			{
				rows.add(new PerformanceRow(s.displayName, s.isYou, s.isCaller, s.ticksSeen, s.targetTicks, s.onTargetTicks,
					s.attacks, s.attacksOnTarget, s.switches, s.switchTicks, s.damageTaken,
					s.isYou ? s.damageDealt : -1, s.eats, s.deaths, s.easyDeaths, s.onPile, PerformanceRow.Rating.UNRATED));
			}
			result.add(new PerformanceRound(r.startMillis, PerformanceRow.rate(rows, minTargetTicks), new ArrayList<>(r.log)));
		}
		return result;
	}

	/**
	 * The round being recorded now.
	 */
	List<PerformanceRow> currentRows()
	{
		return currentRound().rows;
	}

	PerformanceRound currentRound()
	{
		List<PerformanceRound> all = snapshot();
		return all.get(all.size() - 1);
	}

	private Stats statsFor(String name, Player player)
	{
		Stats s = round.stats.computeIfAbsent(name, n -> new Stats());
		s.displayName = Text.removeTags(player.getName());
		return s;
	}

	private Pressure pressureFor(String name)
	{
		return pressure.computeIfAbsent(name, n -> new Pressure());
	}
}
