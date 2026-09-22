package net.runelite.client.plugins.preptools;

/**
 * Which of Target/Pile/Snipe wins the default (left-click) Attack slot when more than one applies
 * to the same menu - see PrepToolsPlugin#resolvePriorityIndex(). Each of the three category checks
 * (prioritiseTargetAttack/prioritiseAttackForPiles/prioritiseAttackForSnipes) is independently
 * toggleable; this only decides the tie-break when two or more are simultaneously true and match
 * different players in the same menu.
 */
public enum AttackPriorityOrder
{
	TARGET_PILE_SNIPE("Target, Pile, Snipe"),
	TARGET_SNIPE_PILE("Target, Snipe, Pile"),
	PILE_TARGET_SNIPE("Pile, Target, Snipe"),
	PILE_SNIPE_TARGET("Pile, Snipe, Target"),
	SNIPE_TARGET_PILE("Snipe, Target, Pile"),
	SNIPE_PILE_TARGET("Snipe, Pile, Target");

	private final String label;

	AttackPriorityOrder(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
