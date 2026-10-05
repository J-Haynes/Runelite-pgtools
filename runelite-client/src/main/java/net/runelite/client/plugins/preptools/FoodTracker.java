package net.runelite.client.plugins.preptools;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Player;
import net.runelite.api.gameval.AnimationID;

/**
 * Experimental: estimates how much food each enemy has eaten, to spot who's running low. Built for
 * Clan Wars, where fights are fed with anchovy pizza (OSRS wiki: 2 bites of 9 hp a pizza).
 *
 * Food is measured as healing, not counted from animations: potions play the same eat animation,
 * and several pizza bites can go down in one. Healing is what the health bar can't explain by the
 * damage they took: (hp after - hp before) + damage hitsplatted in between.
 *
 * <b>Segments, not ticks.</b> Readings are taken between changes of the health bar (or hits), not
 * every tick: the bar may only show a heal with their next hit, so a heal has to be matched to the
 * eat across however long that takes. A segment counts as eating if they were eating in it (or the
 * tick before it began - the animation and the bar update can land a tick apart), so blood spells
 * and other heals without eating are left out. "Eating" is the eat animation starting
 * (AnimationChanged) OR still playing at a tick: pizza bites go down 1-2 ticks apart (wiki: first
 * bite 1 tick, second 2), so a chain of bites can be one long animation with no new event - the
 * second version only used the event, and lost the rest of every chain.
 *
 * <b>Summed, then rounded once.</b> Each counted segment's heal is added as-is, small or negative,
 * and only the total is turned into bites (9 hp each). Health bar rounding (30 steps, 3.3 hp at
 * 99) evens out over many readings instead of losing every heal that rounds below a threshold -
 * the first version did that, and counted under half of what was eaten.
 *
 * <b>Max hp is measured</b>, not just assumed: the server only sends the bar, but in segments
 * without eating the damage taken and the fraction of the bar lost give max hp. Until half a bar
 * of hits has been seen, foodEnemyMaxHp is assumed.
 *
 * <b>Heals cut short at full hp.</b> A bite eaten near full hp heals less than 9 - the food's
 * gone, but the bar can't show it (eating a pizza when only 9 down wastes most of its second bite;
 * in testing this lost a fifth of all bites). An eating segment in which their hp could have
 * reached max (start + healed) counts at least BITES_PER_EAT_ANIMATION bites per eat animation
 * started in it - but only within CAPPED_BITE_TICKS of a real heal, so a potion drunk at full hp
 * isn't taken for food. CONFIRMED in game (2026-10-02): eating plays ONE animation per pizza and
 * none for the second bite, so each animation stands for a whole pizza.
 *
 * Can't see: heals while out of view. A brew counts as about two bites, and a potion drunk in the
 * same capped segment as food as one. Reset on death (a dead player is out of the Clan Wars round
 * and comes back with full food), at the start of each round (entering the Clan Wars portal -
 * startRound()), and by hand. No forgetting over time: in Clan Wars nobody restocks. Client thread
 * only.
 */
@Slf4j
class FoodTracker
{
	static final Set<Integer> EAT_ANIMATION_IDS = Set.of(AnimationID.HUMAN_EAT, AnimationID.HUMAN_EAT_WITHSOUND);

	/**
	 * Hitsplats that take hitpoints - everything that isn't a heal or a drain of something else.
	 */
	private static final Set<Integer> NON_DAMAGE_TYPES = Set.of(HitsplatID.HEAL, HitsplatID.PRAYER_DRAIN,
		HitsplatID.SANITY_DRAIN, HitsplatID.SANITY_RESTORE, HitsplatID.CYAN_UP, HitsplatID.CYAN_DOWN,
		HitsplatID.CORRUPTION, HitsplatID.DOOM, HitsplatID.DISEASE, HitsplatID.DISEASE_BLOCKED);

	static final int HP_PER_BITE = 9;

	/**
	 * Bites one eat animation stands for: a whole anchovy pizza - the half has no animation of its own.
	 */
	static final int BITES_PER_EAT_ANIMATION = 2;

	/**
	 * A bite cut short at full hp only counts this close (ticks) to a heal that showed.
	 */
	static final int CAPPED_BITE_TICKS = 3;

	/**
	 * Fraction of a full health bar of hits needed before the measured max hp is used.
	 */
	static final double MIN_CALIBRATION_FRACTION = 0.5;

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	/**
	 * Food eaten, by standardized name.
	 */
	private final Map<String, Record> records = new HashMap<>();

	/**
	 * The health bar reading in progress for each enemy on screen.
	 */
	private final Map<Player, Segment> segments = new HashMap<>();

	/**
	 * Measured max hp by standardized name - kept through deaths and resets, it doesn't change.
	 */
	private final Map<String, Calibration> calibrations = new HashMap<>();

	private static final class Record
	{
		/**
		 * Over eating segments: health bar fraction gained (signed), and damage taken.
		 */
		double healFraction;
		int healDamage;
		/**
		 * Bites the bar couldn't show - see the class doc on full hp.
		 */
		int cappedBites;
		int eatAnimations;
		// Far enough back that "tick - lastHealTick" can't overflow into "recent".
		int lastHealTick = Integer.MIN_VALUE / 2;
	}

	private static final class Segment
	{
		double lastFraction = Double.NaN;
		int startTick;
		int damage;
		int lastEatTick = Integer.MIN_VALUE;
		/**
		 * Eat animations started (events) in this segment.
		 */
		int eatStarts;
	}

	private static final class Calibration
	{
		int damage;
		double fraction;
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

		Record record = records.computeIfAbsent(name, n -> new Record());
		record.eatAnimations++;
		Segment segment = segments.computeIfAbsent(player, p -> new Segment());
		segment.lastEatTick = client.getTickCount();
		segment.eatStarts++;
	}

	void onHitsplatApplied(Actor actor, Hitsplat hitsplat)
	{
		if (config.showFoodCounter() && actor instanceof Player && hitsplat.getAmount() > 0
			&& !NON_DAMAGE_TYPES.contains(hitsplat.getHitsplatType()))
		{
			segments.computeIfAbsent((Player) actor, p -> new Segment()).damage += hitsplat.getAmount();
		}
	}

	void onGameTick()
	{
		if (!config.showFoodCounter())
		{
			clear();
			return;
		}

		int tick = client.getTickCount();
		for (Player player : client.getPlayers())
		{
			if (player == null || team.isOwnTeam(player))
			{
				continue;
			}
			String name = team.standardName(player);
			if (name == null)
			{
				continue;
			}
			Record record = records.get(name);
			if (EAT_ANIMATION_IDS.contains(player.getAnimation()))
			{
				// Still eating - a chain of bites needn't start a new animation each time.
				segments.computeIfAbsent(player, p -> new Segment()).lastEatTick = tick;
				if (record == null)
				{
					record = new Record();
					records.put(name, record);
				}
			}
			read(player, name, record, tick);
		}
	}

	/**
	 * A new Clan Wars round (you went through the portal): everyone has full food again.
	 */
	void startRound()
	{
		records.clear();
	}

	/**
	 * Ends the player's segment if their health bar changed or they were hit, and books it: to
	 * food if they ate in it, to max hp calibration if they didn't.
	 */
	private void read(Player player, String name, Record record, int tick)
	{
		double fraction = barFraction(player.getHealthRatio(), player.getHealthScale());
		Segment segment = segments.computeIfAbsent(player, p -> new Segment());
		if (Double.isNaN(fraction))
		{
			// No bar showing: the segment (and any damage in it) carries on until there is one.
			return;
		}
		if (Double.isNaN(segment.lastFraction))
		{
			segment.lastFraction = fraction;
			segment.startTick = tick;
			segment.damage = 0;
			return;
		}
		if (fraction == segment.lastFraction && segment.damage == 0)
		{
			return;
		}

		double gained = fraction - segment.lastFraction;
		boolean ate = segment.lastEatTick >= segment.startTick - 1;
		if (ate && record != null)
		{
			record.healFraction += gained;
			record.healDamage += segment.damage;
			int maxHp = maxHp(name);
			double healed = gained * maxHp + segment.damage;
			if (healed >= HP_PER_BITE / 2.0)
			{
				record.lastHealTick = tick;
			}
			// Their hp could have reached max in this segment: some bite may have been cut short.
			boolean full = segment.lastFraction * maxHp + Math.max(0, healed) >= maxHp - HP_PER_BITE / 2.0;
			int capped = full && tick - record.lastHealTick <= CAPPED_BITE_TICKS
				? Math.max(0, segment.eatStarts * BITES_PER_EAT_ANIMATION - biteCount(healed)) : 0;
			record.cappedBites += capped;
			if (config.debugLogging())
			{
				log.info("PvP Tools food: {} bar {} to {} (+{} dmg, {} eat anims{}) - {} hp healed so far, {} bites (max hp {})",
					name, segment.lastFraction, fraction, segment.damage, segment.eatStarts,
					capped > 0 ? ", " + capped + " cut short at full hp" : "", Math.round(healedHp(record, name)),
					bites(record, name), maxHp(name));
			}
		}
		else if (!ate && record != null && config.debugLogging() && gained * maxHp(name) + segment.damage >= HP_PER_BITE / 2.0)
		{
			log.info("PvP Tools food: {} healed {} hp with no eating seen (bar {} to {}, +{} dmg) - not counted",
				name, Math.round(gained * maxHp(name) + segment.damage), segment.lastFraction, fraction, segment.damage);
		}
		else if (!ate && segment.damage > 0 && gained < 0)
		{
			Calibration calibration = calibrations.computeIfAbsent(name, n -> new Calibration());
			calibration.damage += segment.damage;
			calibration.fraction -= gained;
		}

		segment.lastFraction = fraction;
		segment.startTick = tick;
		segment.damage = 0;
		segment.eatStarts = 0;
	}

	void clear()
	{
		records.clear();
		segments.clear();
	}

	/**
	 * Hop/logout: the Player objects are gone, the counts aren't.
	 */
	void clearPlayers()
	{
		segments.clear();
	}

	/**
	 * Left view: their reading starts over when they're back.
	 */
	void forget(Player player)
	{
		segments.remove(player);
	}

	/**
	 * Died, or reset by hand: back to an unknown, full inventory. Max hp is kept.
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
		String name = team.standardName(player);
		Record record = records.get(name);
		return record != null ? bites(record, name) : 0;
	}

	boolean isLowOnFood(Player player)
	{
		return isLow(getBites(player), config.foodAssumedPizzas(), config.foodLowPizzas());
	}

	private int bites(Record record, String name)
	{
		return biteCount(healedHp(record, name)) + record.cappedBites;
	}

	private double healedHp(Record record, String name)
	{
		return record.healFraction * maxHp(name) + record.healDamage;
	}

	/**
	 * The measured max hp once there's enough to go on, else the configured guess.
	 */
	int maxHp(String name)
	{
		Calibration calibration = calibrations.get(name);
		if (calibration != null)
		{
			Integer measured = measuredMaxHp(calibration.damage, calibration.fraction);
			if (measured != null)
			{
				return measured;
			}
		}
		return Math.max(1, config.foodEnemyMaxHp());
	}

	/**
	 * Max hp from damage taken and the fraction of the bar it took, or null with under
	 * MIN_CALIBRATION_FRACTION of a bar to go on. Clamped to real hitpoints levels.
	 */
	static Integer measuredMaxHp(int damage, double fraction)
	{
		if (fraction < MIN_CALIBRATION_FRACTION)
		{
			return null;
		}
		return (int) Math.max(10, Math.min(99, Math.round(damage / fraction)));
	}

	/**
	 * The health bar as a fraction of full, or NaN with no bar showing.
	 */
	static double barFraction(int ratio, int scale)
	{
		return ratio < 0 || scale <= 0 ? Double.NaN : (double) ratio / scale;
	}

	/**
	 * Whole bites in this much healing, never negative.
	 */
	static int biteCount(double healedHp)
	{
		return (int) Math.max(0, Math.round(healedHp / HP_PER_BITE));
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
