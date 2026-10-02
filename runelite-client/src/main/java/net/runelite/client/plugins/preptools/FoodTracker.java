package net.runelite.client.plugins.preptools;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.AnimationID;

/**
 * Experimental: estimates how much food each enemy has eaten, to spot who's running low. Built for
 * Clan Wars, where fights are fed with anchovy pizza (OSRS wiki: 2 bites of 9 hp a pizza).
 *
 * The eat animation alone can't count food: potions play the same animation, and several pizza
 * bites can go down in one animation. So it's only the cue - what's counted is the healing seen on
 * the player's health bar in the ticks around it: hp now, minus hp last tick, plus the damage
 * hitsplatted on them in between. Every 9 hp healed is a bite. An eat with no heal (a potion, or
 * eating at full hp) counts nothing; an eat while their health bar isn't showing can't be
 * measured and is counted separately as "unmeasured". Healing without an eat animation (blood
 * spells) is ignored.
 *
 * The health bar is 30 steps (3.3 hp at 99 hp), so a single measurement is +-3 hp - plenty to tell
 * one bite (9) from two (18). Max hp isn't sent for other players; it's assumed (foodEnemyMaxHp).
 *
 * Reset when a player dies - in Clan Wars a dead player is out of the round, and comes back next
 * round through the portal with a full inventory. Everything resets when you enter an instance
 * (the Clan Wars portal - everyone enters with full food), and by hand: shift-right-click a player
 * "Reset food count", or the foodResetHotkey for everyone. Otherwise kept by name until the
 * player hasn't been seen for foodForgetMinutes. Client thread only.
 */
class FoodTracker
{
	static final Set<Integer> EAT_ANIMATION_IDS = Set.of(AnimationID.HUMAN_EAT, AnimationID.HUMAN_EAT_WITHSOUND);

	static final int HP_PER_BITE = 9;

	/**
	 * Less healing than this is health bar rounding noise, not food.
	 */
	static final int MIN_HEAL = 5;

	/**
	 * The heal can land on the eat's own tick or shortly after - count heals until this many ticks
	 * after the animation.
	 */
	static final int EAT_WINDOW_TICKS = 2;

	private static final int TICKS_PER_MINUTE = 100;

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	private final Map<String, Record> records = new HashMap<>();

	/**
	 * Every on-screen enemy's estimated hp last tick (NaN while their health bar isn't showing),
	 * and the damage hitsplatted on them since.
	 */
	private final Map<Player, Double> lastHp = new HashMap<>();
	private final Map<Player, Integer> damageSinceLastTick = new HashMap<>();

	private boolean wasInInstance;

	private static final class Record
	{
		int bites;
		int unmeasuredEats;
		int lastSeenTick;
		/**
		 * Last tick of the current eat's window, or -1 with no eat in progress.
		 */
		int eatWindowEnd = -1;
		boolean eatMeasured;
	}

	@Inject
	FoodTracker(Client client, PrepToolsConfig config, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.team = team;
	}

	void onAnimationChanged(Player player)
	{
		if (!config.showFoodCounter() || !EAT_ANIMATION_IDS.contains(player.getAnimation()) || team.isOwnTeam(player))
		{
			return;
		}
		String name = team.standardName(player);
		if (name == null)
		{
			return;
		}

		int tick = client.getTickCount();
		Record record = records.computeIfAbsent(name, n -> new Record());
		record.lastSeenTick = tick;
		if (record.eatWindowEnd < tick)
		{
			record.eatMeasured = false;
		}
		record.eatWindowEnd = tick + EAT_WINDOW_TICKS;
	}

	void onHitsplatApplied(Actor actor, Hitsplat hitsplat)
	{
		if (config.showFoodCounter() && actor instanceof Player && hitsplat.getAmount() > 0
			&& (hitsplat.isMine() || hitsplat.isOthers()))
		{
			damageSinceLastTick.merge((Player) actor, hitsplat.getAmount(), Integer::sum);
		}
	}

	void onGameTick()
	{
		if (!config.showFoodCounter())
		{
			clear();
			return;
		}

		// Through the Clan Wars portal: everyone starts the round with full food.
		WorldView worldView = client.getTopLevelWorldView();
		boolean inInstance = worldView != null && worldView.isInstance();
		if (inInstance && !wasInInstance)
		{
			clear();
		}
		wasInInstance = inInstance;

		int tick = client.getTickCount();
		int maxHp = Math.max(1, config.foodEnemyMaxHp());
		Map<Player, Double> hpNow = new HashMap<>();
		for (Player player : client.getPlayers())
		{
			if (player == null || team.isOwnTeam(player))
			{
				continue;
			}
			double hp = estimateHp(player.getHealthRatio(), player.getHealthScale(), maxHp);
			hpNow.put(player, hp);

			Record record = records.get(team.standardName(player));
			if (record == null)
			{
				continue;
			}
			record.lastSeenTick = tick;
			if (record.eatWindowEnd < 0)
			{
				continue;
			}

			Double previous = lastHp.get(player);
			int bites = biteCount(previous != null ? previous : Double.NaN, hp, damageSinceLastTick.getOrDefault(player, 0));
			if (bites > 0)
			{
				record.bites += bites;
				record.eatMeasured = true;
			}
			if (tick >= record.eatWindowEnd)
			{
				// A health bar seen through the whole window with no heal was a potion (or eating at
				// full hp) - nothing to count. No health bar at all: food may have gone, unknown.
				if (!record.eatMeasured && (previous == null || previous.isNaN() || Double.isNaN(hp)))
				{
					record.unmeasuredEats++;
				}
				record.eatWindowEnd = -1;
			}
		}

		lastHp.clear();
		lastHp.putAll(hpNow);
		damageSinceLastTick.clear();

		int forgetTicks = Math.max(1, config.foodForgetMinutes()) * TICKS_PER_MINUTE;
		records.values().removeIf(r -> tick - r.lastSeenTick > forgetTicks);
	}

	void clear()
	{
		records.clear();
		lastHp.clear();
		damageSinceLastTick.clear();
	}

	/**
	 * Hop/logout: the Player objects are gone, the counts aren't.
	 */
	void clearPlayers()
	{
		lastHp.clear();
		damageSinceLastTick.clear();
	}

	/**
	 * Left view: only their hp reading goes - the count is kept by name.
	 */
	void forget(Player player)
	{
		lastHp.remove(player);
		damageSinceLastTick.remove(player);
	}

	/**
	 * Died, or reset by hand: back to an unknown, full inventory.
	 */
	void reset(Player player)
	{
		forget(player);
		String name = team.standardName(player);
		if (name != null)
		{
			records.remove(name);
		}
	}

	boolean hasCount(Player player)
	{
		return records.containsKey(team.standardName(player));
	}

	int getBites(Player player)
	{
		Record record = records.get(team.standardName(player));
		return record != null ? record.bites : 0;
	}

	int getUnmeasuredEats(Player player)
	{
		Record record = records.get(team.standardName(player));
		return record != null ? record.unmeasuredEats : 0;
	}

	boolean isLowOnFood(Player player)
	{
		return isLow(getBites(player), config.foodAssumedPizzas(), config.foodLowPizzas());
	}

	/**
	 * Midpoint of the hp range a health bar ratio stands for, or NaN with no health bar.
	 */
	static double estimateHp(int ratio, int scale, int maxHp)
	{
		if (ratio < 0 || scale <= 0)
		{
			return Double.NaN;
		}
		return (double) ratio * maxHp / scale;
	}

	/**
	 * Bites eaten between two hp readings with damage taken in between: every HP_PER_BITE healed,
	 * at least one once the heal clears MIN_HEAL. 0 if either reading is missing.
	 */
	static int biteCount(double hpBefore, double hpAfter, int damage)
	{
		if (Double.isNaN(hpBefore) || Double.isNaN(hpAfter))
		{
			return 0;
		}
		double healed = hpAfter - hpBefore + damage;
		if (healed < MIN_HEAL)
		{
			return 0;
		}
		return Math.max(1, (int) Math.round(healed / HP_PER_BITE));
	}

	/**
	 * Low once the pizzas left (assumed inventory minus bites eaten, 2 a pizza) are at or below
	 * lowPizzas. No bites seen means no information, never low.
	 */
	static boolean isLow(int bites, int assumedPizzas, int lowPizzas)
	{
		return bites > 0 && pizzasLeft(bites, assumedPizzas) <= lowPizzas;
	}

	/**
	 * Pizzas left, a half pizza counting as one (it's still an item to eat).
	 */
	static int pizzasLeft(int bites, int assumedPizzas)
	{
		return Math.max(0, (assumedPizzas * 2 - bites + 1) / 2);
	}
}
