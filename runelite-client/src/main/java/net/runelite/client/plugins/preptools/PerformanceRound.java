package net.runelite.client.plugins.preptools;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/**
 * One round on the Stats tab - from entering the Clan Wars portal (or a Reset) until the next
 * time. Immutable - handed from the client thread to the EDT.
 */
@AllArgsConstructor
@EqualsAndHashCode
class PerformanceRound
{
	/**
	 * When the fighting started (after a portal round's countdown) - death times count from here.
	 */
	final long startMillis;
	/**
	 * Enemies seen dying this round.
	 */
	final int kills;
	/**
	 * In order of death (PerformanceRow#byDeath()).
	 */
	final List<PerformanceRow> rows;
}
