package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/**
 * One teammate on the sidebar's Stats tab (TeamPerformanceTracker): when they died, pizzas eaten,
 * the kills they were on the pile for, and how early they got onto piles. Immutable - handed from
 * the client thread to the EDT.
 */
@AllArgsConstructor
@EqualsAndHashCode
class PerformanceRow
{
	final String name;
	/**
	 * Their nickname (Names tab or Friend Notes), or null.
	 */
	final String nickname;
	final boolean isYou;
	final boolean isCaller;
	/**
	 * When they died this round (wall clock), in order - empty if they didn't.
	 */
	final List<Long> deathMillis;
	/**
	 * Eat animations - one a pizza.
	 */
	final int pizzas;
	/**
	 * Enemies who died while they were on them (TeamPerformanceTracker.PILE_CREDIT_TICKS).
	 */
	final int onPile;
	/**
	 * Enemies who died while they were on your screen - the most they could have been on.
	 */
	final int pilesAround;
	/**
	 * The places they got onto each of those piles in, added up - last place for a pile they
	 * missed (TeamPerformanceTracker's class comment).
	 */
	final int pileOrderSum;

	/**
	 * Hp the pizzas are worth - two 9 hp bites each.
	 */
	int pizzaHp()
	{
		return pizzas * FoodTracker.BITES_PER_EAT_ANIMATION * FoodTracker.HP_PER_BITE;
	}

	/**
	 * Their average place getting onto a pile, 1 = always first; -1 with no kills around them.
	 */
	double averagePileOrder()
	{
		return pilesAround == 0 ? -1 : (double) pileOrderSum / pilesAround;
	}

	/**
	 * The Stats tab's order: who died first at the top, the living after them by name.
	 */
	static List<PerformanceRow> byDeath(List<PerformanceRow> rows)
	{
		List<PerformanceRow> sorted = new ArrayList<>(rows);
		sorted.sort((a, b) ->
		{
			boolean aDied = !a.deathMillis.isEmpty();
			boolean bDied = !b.deathMillis.isEmpty();
			if (aDied != bDied)
			{
				return aDied ? -1 : 1;
			}
			int byTime = aDied ? Long.compare(a.deathMillis.get(0), b.deathMillis.get(0)) : 0;
			return byTime != 0 ? byTime : String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
		});
		return sorted;
	}
}
