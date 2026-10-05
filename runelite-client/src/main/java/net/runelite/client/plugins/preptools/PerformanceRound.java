package net.runelite.client.plugins.preptools;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/**
 * One round's numbers and fight log for the Stats tab - from entering the Clan Wars portal (or a
 * Reset) until the next time. Immutable - handed from the client thread to the EDT.
 */
@AllArgsConstructor
@EqualsAndHashCode
class PerformanceRound
{
	final long startMillis;
	final List<PerformanceRow> rows;
	/**
	 * Every death this round, in order.
	 */
	final List<FightLogEntry> log;
}
