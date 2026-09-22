package net.runelite.client.plugins.preptools;

/**
 * One row of the sidebar's "Clan IDs" tab - a friends chat member (or yourself) currently on
 * screen, plus their client-side player id (Player#getId()) and whether they're "you"/a configured
 * caller, for PrepToolsPanel to highlight. See MENU_ORDER_PID_INVESTIGATION.md at the repo root for
 * what this id is and why it matters for right-click menu Attack-option exposure - the min and max
 * id in a stack are the two candidates for landing on the literal default (left-click) menu entry.
 */
class ClanIdRow
{
	final String name;
	final int id;
	final boolean isYou;
	final boolean isCaller;

	ClanIdRow(String name, int id, boolean isYou, boolean isCaller)
	{
		this.name = name;
		this.id = id;
		this.isYou = isYou;
		this.isCaller = isCaller;
	}
}
