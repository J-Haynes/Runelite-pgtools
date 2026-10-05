package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.util.Text;

/**
 * Pairs the names that left a friends/ignore list since you last saw it with the names that
 * arrived, to find renames - however many times someone renamed while you were away. Pure logic -
 * unit tested.
 *
 * A pair is certain when the game reports the new name's previous name as one that left. After
 * those, if exactly one name left and exactly one arrived, that's a rename too - the list is kept
 * up to date while you're logged in, so the only other cause is adding one friend and removing
 * another on a different client (mobile) between logins. Anything else is left for you to pair.
 */
final class RenameMatch
{
	/**
	 * Old name to new name.
	 */
	final Map<String, String> renames;
	final List<String> unmatchedLeft;
	final List<String> unmatchedArrived;

	private RenameMatch(Map<String, String> renames, List<String> unmatchedLeft, List<String> unmatchedArrived)
	{
		this.renames = renames;
		this.unmatchedLeft = unmatchedLeft;
		this.unmatchedArrived = unmatchedArrived;
	}

	/**
	 * @param before the list as last saved
	 * @param after the list now
	 * @param previousNames each current name's previous name as the game reports it (may be
	 * missing)
	 */
	static RenameMatch match(Collection<String> before, Collection<String> after, Map<String, String> previousNames)
	{
		List<String> left = new ArrayList<>(minus(before, after));
		List<String> arrived = new ArrayList<>(minus(after, before));
		Map<String, String> renames = new LinkedHashMap<>();

		for (String name : new ArrayList<>(arrived))
		{
			String previous = previousNames.get(name);
			String old = previous == null ? null : find(left, previous);
			if (old != null)
			{
				renames.put(old, name);
				left.remove(old);
				arrived.remove(name);
			}
		}

		if (left.size() == 1 && arrived.size() == 1)
		{
			renames.put(left.get(0), arrived.get(0));
			left.clear();
			arrived.clear();
		}
		return new RenameMatch(renames, left, arrived);
	}

	private static List<String> minus(Collection<String> names, Collection<String> without)
	{
		List<String> result = new ArrayList<>();
		for (String name : names)
		{
			if (find(without, name) == null)
			{
				result.add(name);
			}
		}
		return result;
	}

	/**
	 * The entry with the same standardized name, or null.
	 */
	private static String find(Collection<String> names, String name)
	{
		String standard = Text.standardize(name);
		for (String candidate : names)
		{
			if (Text.standardize(candidate).equals(standard))
			{
				return candidate;
			}
		}
		return null;
	}

	static RenameMatch none()
	{
		return new RenameMatch(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
	}
}
