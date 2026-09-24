package net.runelite.client.plugins.preptools;

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

	@Override
	public String toString()
	{
		return label;
	}
}
