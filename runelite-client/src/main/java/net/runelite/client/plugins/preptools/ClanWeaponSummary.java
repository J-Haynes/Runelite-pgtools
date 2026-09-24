package net.runelite.client.plugins.preptools;

import java.util.List;
import java.util.Objects;

/**
 * The Clan Info tab's weapon breakdown - which on-screen teammates (you included) are wielding a
 * melee, ranged or magic weapon, from the same weapon ID lists as the infobox's "Attacking you"
 * row. Names are sorted; unknown counts teammates whose weapon is in none of the three lists.
 */
class ClanWeaponSummary
{
	final List<String> melee;
	final List<String> ranged;
	final List<String> magic;
	final int unknown;

	ClanWeaponSummary(List<String> melee, List<String> ranged, List<String> magic, int unknown)
	{
		this.melee = melee;
		this.ranged = ranged;
		this.magic = magic;
		this.unknown = unknown;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof ClanWeaponSummary))
		{
			return false;
		}
		ClanWeaponSummary other = (ClanWeaponSummary) o;
		return unknown == other.unknown && melee.equals(other.melee) && ranged.equals(other.ranged) && magic.equals(other.magic);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(melee, ranged, magic, unknown);
	}
}
