package net.runelite.client.plugins.preptools;

import net.runelite.client.util.Text;

/**
 * Spots names made to pass for someone else's: one letter off ("Zezima" / "Zezlma"), or the same
 * once lookalike characters are folded together - I/l/1, O/0, rn/m, vv/w, and spaces, hyphens and
 * underscores (which OSRS shows almost alike). Pure logic - unit tested.
 */
final class NameLookalike
{
	private NameLookalike()
	{
	}

	/**
	 * The name with every lookalike folded to one form - two names with the same skeleton look
	 * the same on screen.
	 */
	static String skeleton(String name)
	{
		String s = Text.standardize(name).replaceAll("[ _\\-]", "");
		s = s.replace("rn", "m").replace("vv", "w");
		return s.replace('0', 'o').replace('1', 'l').replace('i', 'l').replace('|', 'l');
	}

	/**
	 * Different names that look alike. Both standardized-equal names and very short names (where
	 * one letter off is just a different name) are not lookalikes.
	 */
	static boolean isLookalike(String name, String other)
	{
		String a = Text.standardize(name);
		String b = Text.standardize(other);
		if (a.equals(b))
		{
			return false;
		}
		if (skeleton(a).equals(skeleton(b)))
		{
			return true;
		}
		return Math.min(a.length(), b.length()) >= 4 && withinOneEdit(a, b);
	}

	/**
	 * Levenshtein distance of at most one, without building the whole table.
	 */
	static boolean withinOneEdit(String a, String b)
	{
		if (Math.abs(a.length() - b.length()) > 1)
		{
			return false;
		}
		String shorter = a.length() <= b.length() ? a : b;
		String longer = a.length() <= b.length() ? b : a;
		int i = 0;
		while (i < shorter.length() && shorter.charAt(i) == longer.charAt(i))
		{
			i++;
		}
		if (shorter.length() == longer.length())
		{
			// One substitution: everything after it matches.
			return i >= shorter.length() || shorter.substring(i + 1).equals(longer.substring(i + 1));
		}
		// One insertion into the shorter one.
		return shorter.substring(i).equals(longer.substring(i + 1));
	}
}
