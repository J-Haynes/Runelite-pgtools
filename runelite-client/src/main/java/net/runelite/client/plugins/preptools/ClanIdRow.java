package net.runelite.client.plugins.preptools;

/**
 * One row of the sidebar's "Clan Info" tab - a friends chat/clan member (or yourself) currently on
 * screen, plus their client-side player id (Player#getId()) and whether they're "you"/a configured
 * caller, for PrepToolsPanel to highlight. See MENU_ORDER_PID_INVESTIGATION.md at the repo root for
 * what this id is and why it matters for right-click menu Attack-option exposure - the min and max
 * id in a stack are the two candidates for landing on the literal default (left-click) menu entry.
 */
class ClanIdRow
{
	final String name;
	/**
	 * The caller's configured nickname, shown as "Name (Nickname)" - only set for rows that are
	 * also an on-screen caller with a nickname, null otherwise.
	 */
	final String nickname;
	final int id;
	final boolean isYou;
	final boolean isCaller;

	ClanIdRow(String name, String nickname, int id, boolean isYou, boolean isCaller)
	{
		this.name = name;
		this.nickname = nickname;
		this.id = id;
		this.isYou = isYou;
		this.isCaller = isCaller;
	}
}
