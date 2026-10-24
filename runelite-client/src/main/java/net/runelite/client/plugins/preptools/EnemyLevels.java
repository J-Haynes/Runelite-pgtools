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
