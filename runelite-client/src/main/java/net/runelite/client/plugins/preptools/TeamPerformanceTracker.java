package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Player;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * Experimental: the sidebar's Stats tab, per round (entering the Clan Wars portal starts a new one -
 * startRound(); earlier rounds stay viewable until Reset). For each teammate, and only that (asked
 * for - the first version had ratings, switch times, damage and a fight log, which was too much to
 * read mid-war):
 * <ul>
 * <li>when they died,</li>
 * <li>how many pizzas they ate - eat animations, one a pizza (confirmed in game; a potion plays it
 * too, so nothing is counted during the Clan Wars countdown, when the team drinks its boosts),</li>
 * <li>how many of the enemies that died they were on the pile for: interacting with them, or an
 * attack animation at them, in the last PILE_CREDIT_TICKS before the death (generous: a pile on
 * someone eating runs long between hits, and interacting drops the moment the target dies) - out
 * of the kills they were around for (seen on your screen in that window).</li>
 * <li>pile order: for each kill they were around for, the place they got onto that pile in - 1st
 * is the first teammate on it (ties share a place). Getting on means the start of their latest
 * stretch on the enemy: a break longer than PILE_CREDIT_TICKS starts a new one, so a chip a
 * minute earlier doesn't make you 1st. Around for a kill but never on it counts as last place -
 * the number of teammates around - so missing a pile can't improve the average.</li>
 * </ul>
 * A Clan Wars round starts with a 2 minute countdown: death times count from its end, and eats
 * before it aren't counted. The end comes from the CLANWARS_COUNTDOWN varbit - the ticks left, as
 * read from the game's own HUD script (cs2 596 shows the countdown widget while it's above 0 and
 * times it from varbit * 30 client cycles = varbit ticks), so it's right however late you go
 * through the portal. Only what happens on your screen counts. Kept by name. Client thread only.
 */
@Slf4j
class TeamPerformanceTracker
{

	/**
	 * How recently (ticks, about 10s) a teammate must have been on an enemy to count as on the pile
	 * when they died - and seen at all to count as around for it. Also how long a repeated death
	 * event for the same player is ignored.
	 */
	static final int PILE_CREDIT_TICKS = 16;

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
	private final PlayerNicknameTracker nicknames;

	/**
	 * Last CLANWARS_COUNTDOWN varbit seen (ticks until the fight starts, 0 = none running).
	 */
	private int lastCountdown;

	private static final class Stats
	{
		String displayName;
		boolean isYou;
		boolean isCaller;
		int lastSeenTick;
		final List<Long> deathMillis = new ArrayList<>();
		int pizzas;
		int onPile;
		int pilesAround;
		int pileOrderSum;
	}

	private static final class Round
	{
		/**
		 * When the fighting starts - the end of the Clan Wars countdown, if there is one.
		 */
		long startMillis;
		int startTick;
		final Map<String, Stats> stats = new HashMap<>();
		int kills;

		Round(long startMillis, int startTick)
		{
			this.startMillis = startMillis;
			this.startTick = startTick;
		}
	}

	private final List<Round> rounds = new ArrayList<>();
	private Round round;

	/**
	 * A teammate's current stretch on an enemy.
	 */
	private static final class Stretch
	{
		final int startTick;
		int lastTick;

		Stretch(int tick)
		{
			startTick = tick;
			lastTick = tick;
		}
	}

	/**
	 * Enemy to the teammates on them - on pile credit and pile order.
	 */
	private final Map<Player, Map<String, Stretch>> recentAttackers = new HashMap<>();

	/**
	 * Last death tick per name - the death animation and ActorDeath can both report one death.
	 */
	private final Map<String, Integer> lastDeathTick = new HashMap<>();

	@Inject
	TeamPerformanceTracker(Client client, PrepToolsConfig config, TeamTracker team, PlayerNicknameTracker nicknames)
	{
		this.client = client;
		this.config = config;
		this.team = team;
		this.nicknames = nicknames;
		reset();
	}

	/**
	 * You went through the Clan Wars portal: a new round, its own numbers. A round nothing was
	 * recorded in is just restarted rather than kept.
	 */
	void startRound()
	{
		if (round.stats.isEmpty() && round.kills == 0)
		{
			rounds.remove(round);
		}
		round = new Round(System.currentTimeMillis(), client.getTickCount());
		lastCountdown = client.getVarbitValue(VarbitID.CLANWARS_COUNTDOWN);
		startAfterCountdown(lastCountdown);
		rounds.add(round);
		while (rounds.size() > MAX_ROUNDS)
		{
			rounds.remove(0);
		}
		lastDeathTick.clear();
		clearPlayers();
	}

	/**
	 * The sidebar's Reset button: every round gone, a fresh one started - straight away, no
	 * countdown (the wilderness has none).
	 */
	void reset()
	{
		rounds.clear();
		round = new Round(System.currentTimeMillis(), client.getTickCount());
		rounds.add(round);
		lastDeathTick.clear();
		clearPlayers();
	}

	void onGameTick()
	{
		int tick = client.getTickCount();
		// A countdown started (or was set again), or just ended - the fight starts when it runs out.
		// Whether the server counts the varbit down or sets it once, its last value says when.
		int countdown = client.getVarbitValue(VarbitID.CLANWARS_COUNTDOWN);
		if (countdown != lastCountdown)
		{
			startAfterCountdown(countdown);
			if (config.debugLogging())
			{
				log.info("PvP Tools stats: Clan Wars countdown {} to {} at tick {} - fight starts at tick {}",
					lastCountdown, countdown, tick, round.startTick);
			}
			lastCountdown = countdown;
		}

		if (!config.enablePerformanceTracker())
		{
			return;
		}

		Player local = client.getLocalPlayer();
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
			s.lastSeenTick = tick;

			Actor interacting = player.getInteracting();
			if (interacting instanceof Player && !team.isOwnTeam((Player) interacting))
			{
				onEnemy((Player) interacting, name, tick);
			}
		}

		// Drop stretches that have ended, and enemies nobody's been on lately.
		for (Iterator<Map<String, Stretch>> it = recentAttackers.values().iterator(); it.hasNext(); )
		{
			Map<String, Stretch> byName = it.next();
			byName.values().removeIf(stretch -> tick - stretch.lastTick > PILE_CREDIT_TICKS);
			if (byName.isEmpty())
			{
				it.remove();
			}
		}
	}

	void onAnimationChanged(Player player)
	{
		int animation = player.getAnimation();
		if (!config.enablePerformanceTracker() || !team.isOwnTeam(player) || animation == -1
			|| DEATH_ANIMATION_IDS.contains(animation) || BLOCK_ANIMATION_IDS.contains(animation))
		{
			return;
		}

		String name = team.standardName(player);
		if (FoodTracker.EAT_ANIMATION_IDS.contains(animation))
		{
			// Before the fight starts it's a boost potion, not food.
			if (client.getTickCount() >= round.startTick)
			{
				statsFor(name, player).pizzas++;
			}
			return;
		}

		Actor interacting = player.getInteracting();
		if (interacting instanceof Player && !team.isOwnTeam((Player) interacting))
		{
			onEnemy((Player) interacting, name, client.getTickCount());
		}
	}

	/**
	 * The current round's fight starts this many ticks from now.
	 */
	private void startAfterCountdown(int ticksLeft)
	{
		round.startTick = client.getTickCount() + ticksLeft;
		round.startMillis = System.currentTimeMillis() + (long) ticksLeft * Constants.GAME_TICK_LENGTH;
	}

	private void onEnemy(Player enemy, String name, int tick)
	{
		Map<String, Stretch> byName = recentAttackers.computeIfAbsent(enemy, p -> new HashMap<>());
		Stretch stretch = byName.get(name);
		if (stretch == null || tick - stretch.lastTick > PILE_CREDIT_TICKS)
		{
			byName.put(name, new Stretch(tick));
		}
		else
		{
			stretch.lastTick = tick;
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
			statsFor(name, player).deathMillis.add(System.currentTimeMillis());
			return;
		}

		round.kills++;
		Map<String, Stretch> pile = recentAttackers.remove(player);
		Map<String, Integer> starts = new HashMap<>();
		if (pile != null)
		{
			pile.forEach((mate, stretch) -> starts.put(mate, stretch.startTick));
		}
		List<Stats> around = new ArrayList<>();
		for (Stats s : round.stats.values())
		{
			if (tick - s.lastSeenTick <= PILE_CREDIT_TICKS)
			{
				around.add(s);
			}
		}
		Map<String, Integer> places = pileOrder(starts);
		int lastPlace = Math.max(around.size(), starts.size());
		for (Map.Entry<String, Stats> entry : round.stats.entrySet())
		{
			Stats s = entry.getValue();
			if (!around.contains(s))
			{
				continue;
			}
			s.pilesAround++;
			Integer place = places.get(entry.getKey());
			if (place != null)
			{
				s.onPile++;
			}
			s.pileOrderSum += place != null ? place : lastPlace;
		}
	}

	/**
	 * The place each teammate got onto a pile in, by the tick they got on: 1 for the first, and
	 * teammates who got on in the same tick share a place (two on at once, then one more: 1, 1, 3).
	 */
	static Map<String, Integer> pileOrder(Map<String, Integer> startTicks)
	{
		Map<String, Integer> places = new HashMap<>();
		startTicks.forEach((mate, start) ->
		{
			int earlier = 0;
			for (int other : startTicks.values())
			{
				if (other < start)
				{
					earlier++;
				}
			}
			places.put(mate, earlier + 1);
		});
		return places;
	}

	/**
	 * A player left view - stop holding on to them as an enemy.
	 */
	void forget(Player player)
	{
		recentAttackers.remove(player);
	}

	/**
	 * Hop/logout/new round: the Player objects are gone, the numbers aren't.
	 */
	void clearPlayers()
	{
		recentAttackers.clear();
	}

	/**
	 * Every round, oldest first - the last is the one being recorded.
	 */
	List<PerformanceRound> snapshot()
	{
		List<PerformanceRound> result = new ArrayList<>();
		for (Round r : rounds)
		{
			List<PerformanceRow> rows = new ArrayList<>();
			for (Stats s : r.stats.values())
			{
				rows.add(new PerformanceRow(s.displayName, nicknames.nicknameFor(s.displayName), s.isYou, s.isCaller, new ArrayList<>(s.deathMillis), s.pizzas,
					s.onPile, s.pilesAround, s.pileOrderSum));
			}
			result.add(new PerformanceRound(r.startMillis, r.kills, PerformanceRow.byDeath(rows)));
		}
		return result;
	}

	/**
	 * The round being recorded now.
	 */
	List<PerformanceRow> currentRows()
	{
		List<PerformanceRound> all = snapshot();
		return all.get(all.size() - 1).rows;
	}

	private Stats statsFor(String name, Player player)
	{
		Stats s = round.stats.computeIfAbsent(name, n -> new Stats());
		s.displayName = Text.removeTags(player.getName());
		return s;
	}
}
