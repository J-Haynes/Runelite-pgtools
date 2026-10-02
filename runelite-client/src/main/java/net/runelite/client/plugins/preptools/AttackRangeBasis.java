package net.runelite.client.plugins.preptools;

import lombok.RequiredArgsConstructor;

/**
 * Whose combat bracket decides that an enemy is out of range - see AttackRangeFilter.
 */
@RequiredArgsConstructor
public enum AttackRangeBasis
{
	YOU("Your level only"),
	TEAM("Anyone on your team");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
