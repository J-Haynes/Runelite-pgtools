package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/**
 * One teammate's numbers on the sidebar's Stats tab (TeamPerformanceTracker), with a rating
 * relative to the rest of the team. Immutable - handed from the client thread to the EDT.
 */
@AllArgsConstructor
@EqualsAndHashCode
class PerformanceRow
{
	enum Rating
	{
		HIGH,
		NORMAL,
		LOW,
		/**
		 * Not enough called-target time yet to judge, or a caller.
		 */
		UNRATED
	}

	/**
	 * How far (percentage points) from the team median on-target share counts as high/low.
	 */
	static final int RATING_SPREAD = 15;

	final String name;
	final boolean isYou;
	final boolean isCaller;
	/**
	 * Ticks on screen.
	 */
	final int ticksSeen;
	/**
	 * Ticks on screen while the primary caller had a target that went on to die (the caller
	 * themself excluded) - chip calls don't count.
	 */
	final int targetTicks;
	/**
	 * Of targetTicks, ticks spent interacting with that target.
	 */
	final int onTargetTicks;
	/**
	 * Attack animations on any enemy, and on the called target.
	 */
	final int attacks;
	final int attacksOnTarget;
	/**
	 * Called targets they switched to, and the ticks it took in total.
	 */
	final int switches;
	final int switchTicks;
	final int damageTaken;
	/**
	 * Your own hits only - nobody else's damage can be attributed. -1 for everyone else.
	 */
	final int damageDealt;
	final int eats;
	final int deaths;
	/**
	 * Deaths flagged easy (TeamPerformanceTracker#isEasyDeath()).
	 */
	final int easyDeaths;
	/**
	 * Enemies who died while they were on them (TeamPerformanceTracker.PILE_CREDIT_TICKS).
	 */
	final int onPile;
	final Rating rating;

	/**
	 * Share of called-target ticks spent on the target, 0-100, or -1 with no target time.
	 */
	int onTargetPercent()
	{
		return targetTicks == 0 ? -1 : Math.round(100f * onTargetTicks / targetTicks);
	}

	/**
	 * Average ticks to switch onto a new called target, or -1 if they never have.
	 */
	double averageSwitchTicks()
	{
		return switches == 0 ? -1 : (double) switchTicks / switches;
	}

	PerformanceRow withRating(Rating newRating)
	{
		return new PerformanceRow(name, isYou, isCaller, ticksSeen, targetTicks, onTargetTicks, attacks, attacksOnTarget,
			switches, switchTicks, damageTaken, damageDealt, eats, deaths, easyDeaths, onPile, newRating);
	}

	/**
	 * Rates every non-caller with at least minTargetTicks of called-target time against the median
	 * on-target share of those players: RATING_SPREAD points above is HIGH, below is LOW. Needs at
	 * least three rated players - with fewer, "the team median" means nothing. Returns the rows
	 * sorted best on-target share first, unrated last.
	 */
	static List<PerformanceRow> rate(List<PerformanceRow> rows, int minTargetTicks)
	{
		List<Integer> shares = new ArrayList<>();
		for (PerformanceRow row : rows)
		{
			if (isRateable(row, minTargetTicks))
			{
				shares.add(row.onTargetPercent());
			}
		}
		Collections.sort(shares);
		Integer median = shares.size() >= 3 ? shares.get(shares.size() / 2) : null;

		List<PerformanceRow> rated = new ArrayList<>();
		for (PerformanceRow row : rows)
		{
			Rating rating = Rating.UNRATED;
			if (median != null && isRateable(row, minTargetTicks))
			{
				int share = row.onTargetPercent();
				rating = share >= median + RATING_SPREAD ? Rating.HIGH : share <= median - RATING_SPREAD ? Rating.LOW : Rating.NORMAL;
			}
			rated.add(row.withRating(rating));
		}
		rated.sort((a, b) ->
		{
			boolean aRated = a.rating != Rating.UNRATED;
			boolean bRated = b.rating != Rating.UNRATED;
			if (aRated != bRated)
			{
				return aRated ? -1 : 1;
			}
			int byShare = Integer.compare(b.onTargetPercent(), a.onTargetPercent());
			return byShare != 0 ? byShare : String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
		});
		return rated;
	}

	private static boolean isRateable(PerformanceRow row, int minTargetTicks)
	{
		return !row.isCaller && row.targetTicks >= Math.max(1, minTargetTicks);
	}
}
