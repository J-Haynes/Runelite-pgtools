package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.Player;

/**
 * On-screen enemies' combat levels, grouped by team cape, and the level ranges they're counted
 * in - for the Capes tab's Enemy Levels charts. Pure: built once a tick from the player list.
 */
@Value
class EnemyLevels
{
	static final int MIN_LEVEL = 3;
	static final int MAX_LEVEL = 126;

	static final EnemyLevels EMPTY = new EnemyLevels(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

	/**
	 * Every enemy's level, sorted.
	 */
	List<Integer> all;

	/**
	 * One group per team cape with at least the minimum wearers, most wearers first.
	 */
	List<Group> capes;

	/**
	 * Capeless enemies and capes under the minimum, sorted.
	 */
	List<Integer> others;

	@Value
	static class Group
	{
		int team;
		/**
		 * Sorted.
		 */
		List<Integer> levels;
	}

	/**
	 * Combat levels low to high, both inclusive.
	 */
	@Value
	static class LevelRange
	{
		int low;
		int high;

		boolean contains(int level)
		{
			return level >= low && level <= high;
		}

		@Override
		public String toString()
		{
			return low == high ? String.valueOf(low) : low + "-" + high;
		}
	}

	/**
	 * One chart as the Capes tab and Scout show it: a title (cape name), how many enemies, their
	 * lowest-highest and median, and a count per range.
	 */
	@Value
	static class Chart
	{
		/**
		 * 0 for All/Others - no cape icon.
		 */
		int team;
		String title;
		int total;
		String spread;
		List<LevelRange> ranges;
		List<Integer> counts;
		/**
		 * Levels in none of the ranges.
		 */
		int outside;

		int fullest()
		{
			return Math.max(1, counts.stream().mapToInt(Integer::intValue).max().orElse(0));
		}
	}

	/**
	 * All enemies, then a chart per cape, then Others (only when there's a cape chart to set them
	 * apart from). Empty with no enemies.
	 */
	static List<Chart> charts(EnemyLevels levels, List<LevelRange> ranges, Map<Integer, String> capeNames)
	{
		List<Chart> charts = new ArrayList<>();
		if (levels.all.isEmpty())
		{
			return charts;
		}
		charts.add(chart(0, "All enemies", levels.all, ranges));
		for (Group group : levels.capes)
		{
			String name = capeNames.get(group.team);
			charts.add(chart(group.team, name != null ? name + " (team " + group.team + ")" : "Team " + group.team,
				group.levels, ranges));
		}
		if (!levels.capes.isEmpty() && !levels.others.isEmpty())
		{
			charts.add(chart(0, "Others", levels.others, ranges));
		}
		return charts;
	}

	private static Chart chart(int team, String title, List<Integer> levels, List<LevelRange> ranges)
	{
		int low = levels.get(0);
		int high = levels.get(levels.size() - 1);
		String spread = low == high ? String.valueOf(low) : low + "-" + high + ", median " + median(levels);
		List<Integer> counts = ranges.stream().map(r -> count(levels, r)).collect(Collectors.toList());
		return new Chart(team, title, levels.size(), spread, ranges, counts, outside(levels, ranges));
	}

	static EnemyLevels compute(Iterable<Player> players, Predicate<Player> isEnemy, int minimumCapeCount)
	{
		List<Integer> all = new ArrayList<>();
		Map<Integer, List<Integer>> byTeam = new HashMap<>();
		List<Integer> others = new ArrayList<>();
		for (Player player : players)
		{
			if (player == null || !isEnemy.test(player))
			{
				continue;
			}
			int level = player.getCombatLevel();
			all.add(level);
			if (player.getTeam() > 0)
			{
				byTeam.computeIfAbsent(player.getTeam(), t -> new ArrayList<>()).add(level);
			}
			else
			{
				others.add(level);
			}
		}

		List<Group> capes = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> team : byTeam.entrySet())
		{
			List<Integer> levels = team.getValue();
			Collections.sort(levels);
			if (levels.size() >= minimumCapeCount)
			{
				capes.add(new Group(team.getKey(), levels));
			}
			else
			{
				others.addAll(levels);
			}
		}
		capes.sort((a, b) -> a.levels.size() != b.levels.size()
			? b.levels.size() - a.levels.size() : a.team - b.team);
		Collections.sort(all);
		Collections.sort(others);
		return new EnemyLevels(all, capes, others);
	}

	/**
	 * The user's ranges, e.g. "3-49, 50-59, 60" (commas or new lines; a single number is a range of
	 * one level). Unreadable parts are skipped, levels clamped to 3-126, a reversed range turned
	 * round, duplicates dropped; sorted by low end.
	 */
	static List<LevelRange> parseRanges(String text)
	{
		List<LevelRange> ranges = new ArrayList<>();
		if (text == null)
		{
			return ranges;
		}
		for (String part : text.split("[,\\n]"))
		{
			String[] ends = part.trim().split("\\s*-\\s*");
			try
			{
				if (ends.length == 1 && !ends[0].isEmpty())
				{
					int level = clamp(Integer.parseInt(ends[0]));
					ranges.add(new LevelRange(level, level));
				}
				else if (ends.length == 2)
				{
					int a = clamp(Integer.parseInt(ends[0]));
					int b = clamp(Integer.parseInt(ends[1]));
					ranges.add(new LevelRange(Math.min(a, b), Math.max(a, b)));
				}
			}
			catch (NumberFormatException e)
			{
				// Not a range - skipped.
			}
		}
		return ranges.stream()
			.distinct()
			.sorted(Comparator.comparingInt(LevelRange::getLow).thenComparingInt(LevelRange::getHigh))
			.collect(Collectors.toList());
	}

	static String formatRanges(List<LevelRange> ranges)
	{
		return ranges.stream().map(LevelRange::toString).collect(Collectors.joining(", "));
	}

	/**
	 * The ranges to chart: the user's, or - when they haven't set any - every bucketSize levels
	 * (multiples of bucketSize) from the lowest enemy level to the highest.
	 */
	static List<LevelRange> chartRanges(List<LevelRange> custom, int bucketSize, List<Integer> sortedLevels)
	{
		if (!custom.isEmpty())
		{
			return custom;
		}
		List<LevelRange> ranges = new ArrayList<>();
		if (sortedLevels.isEmpty())
		{
			return ranges;
		}
		int size = Math.max(1, bucketSize);
		int highest = sortedLevels.get(sortedLevels.size() - 1);
		for (int low = sortedLevels.get(0) / size * size; low <= highest; low += size)
		{
			ranges.add(new LevelRange(low, low + size - 1));
		}
		return ranges;
	}

	static int count(List<Integer> levels, LevelRange range)
	{
		int count = 0;
		for (int level : levels)
		{
			if (range.contains(level))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * How many levels fall in none of the ranges.
	 */
	static int outside(List<Integer> levels, List<LevelRange> ranges)
	{
		int count = 0;
		for (int level : levels)
		{
			if (ranges.stream().noneMatch(r -> r.contains(level)))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * The middle level of a sorted list (the lower of the two middle ones for an even count).
	 */
	static int median(List<Integer> levels)
	{
		return levels.get((levels.size() - 1) / 2);
	}

	private static int clamp(int level)
	{
		return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
	}
}
