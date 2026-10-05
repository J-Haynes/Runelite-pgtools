package net.runelite.client.plugins.preptools;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/**
 * One death in a round's fight log (TeamPerformanceTracker) - a teammate's, or an enemy's.
 * Immutable - handed from the client thread to the EDT.
 */
@AllArgsConstructor
@EqualsAndHashCode
class FightLogEntry
{
	final long timeMillis;
	/**
	 * Ours (a teammate died) or theirs (an enemy died).
	 */
	final boolean teammate;
	final String name;

	/**
	 * Enemy deaths: whether the primary caller had called them, by whom, and the ticks from first
	 * call to death. Not called: a pile nobody called, durationTicks -1.
	 */
	final boolean called;
	final String caller;

	/**
	 * Enemy deaths: the ticks from first being called to dying (-1 if not called). Teammate deaths:
	 * the ticks they'd been under attack without a break (-1 if nobody was on them).
	 */
	final int durationTicks;

	/**
	 * Enemy deaths: the teammates on the pile, most time on target first. Teammate deaths: the
	 * enemies on them in the last few seconds.
	 */
	final List<String> names;

	/**
	 * Teammate deaths only: pizzas eaten (eat animations - one a pizza) while under attack, damage
	 * taken in the last few seconds, whether they were frozen, whether they were off your screen
	 * at any point while under attack (then the numbers are incomplete and never flagged), and
	 * whether this counts as an easy death (see TeamPerformanceTracker#isEasyDeath()).
	 */
	final int pizzasEaten;
	final int recentDamage;
	final boolean frozen;
	final boolean partlyOffScreen;
	final boolean easy;
}
