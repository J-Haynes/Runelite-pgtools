package net.runelite.client.plugins.preptools;

import java.util.Objects;

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
	 * Shown as "Name (Nickname)": a caller's caller-list nickname, else the player's nickname from
	 * the Names tab (or Friend Notes); null if neither.
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

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof ClanIdRow))
		{
			return false;
		}
		ClanIdRow other = (ClanIdRow) o;
		return id == other.id && isYou == other.isYou && isCaller == other.isCaller
			&& name.equals(other.name) && Objects.equals(nickname, other.nickname);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(name, nickname, id, isYou, isCaller);
	}
}
