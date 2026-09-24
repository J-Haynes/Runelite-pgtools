package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Formats the Scout (Discord) message. One line of location, then one line per team cape, most
 * worn first:
 * <pre>
 * W308, Ferox Enclave, lvl 15 wild.
 * 10x Team-50 (EoP),
 * 3x Team-38
 * </pre>
 */
final class ScoutReport
{
	static final String NO_CAPES = "No team capes in sight";

	private ScoutReport()
	{
	}

	/**
	 * @param capeCounts    team cape number to how many on-screen players wear it
	 * @param capeNames     the Capes tab's names by team number (may be missing entries)
	 * @param minCapeCount  capes worn by fewer players are left out
	 * @param world         current world
	 * @param locationName  resolved wilderness location, or null
	 * @param wildernessLvl wilderness level, or null outside the wilderness
	 */
	static String format(Map<Integer, Integer> capeCounts, Map<Integer, String> capeNames, int minCapeCount,
		int world, String locationName, Integer wildernessLvl)
	{
		List<String> location = new ArrayList<>();
		location.add("W" + world);
		if (locationName != null)
		{
			location.add(locationName);
		}
		if (wildernessLvl != null)
		{
			location.add("lvl " + wildernessLvl + " wild");
		}

		List<String> capes = new ArrayList<>();
		capeCounts.entrySet().stream()
			.filter(e -> e.getValue() >= minCapeCount)
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
				.thenComparing(Map.Entry.comparingByKey()))
			.forEach(e ->
			{
				String name = capeNames.get(e.getKey());
				capes.add(e.getValue() + "x Team-" + e.getKey()
					+ (name != null && !name.isEmpty() ? " (" + name + ")" : ""));
			});

		return String.join(", ", location) + ".\n"
			+ (capes.isEmpty() ? NO_CAPES : String.join(",\n", capes));
	}
}
