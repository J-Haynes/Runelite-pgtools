package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The kinds of highlighted enemy whose "Attack" can be promoted to left-click, for the Menu
 * section's 1st to 4th Attack priority tie-break (PrepToolsConfig#attackPriority1() etc., see
 * PrepToolsPlugin#resolvePriorityIndex()).
 */
public enum AttackCategory
{
	TARGET("Target"),
	PILE("Pile"),
	SNIPE("Snipe"),
	ATTACKER("Attacker");

	private final String label;

	AttackCategory(String label)
	{
		this.label = label;
	}

	/**
	 * The menu index whose category comes first in the configured order, or -1 if none matched.
	 * Categories missing from the order (one was picked twice) are checked last, in declaration
	 * order, so a duplicate pick can't silently drop one.
	 *
	 * @param order   the configured 1st to 4th priority
	 * @param indices menu index per category, -1 (or absent) when not in this menu
	 */
	static int resolve(List<AttackCategory> order, Map<AttackCategory, Integer> indices)
	{
		List<AttackCategory> checked = new ArrayList<>(order);
		checked.addAll(Arrays.asList(values()));
		for (AttackCategory category : checked)
		{
			int index = indices.getOrDefault(category, -1);
			if (index >= 0)
			{
				return index;
			}
		}
		return -1;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
