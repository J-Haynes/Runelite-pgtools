package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Experience;
import net.runelite.client.RuneLite;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.Skill;
import net.runelite.client.util.Text;

/**
 * Manual hiscore lookups of the players in view - the team (Clan Info tab) or the enemies (Capes
 * tab). Never automatic: a button press snapshots who's on screen, then looks them up one at a
 * time, 1.5-3s apart, through core's HiscoreClient. Results are cached for CACHE_HOURS on disk
 * so a second press only looks up new names. A batch stops on its own if the hiscores start
 * refusing requests.
 */
@Slf4j
class HiscoreLookups
{
	static final int MAX_LIMIT = 100;
	static final long MIN_DELAY_MILLIS = 1500;
	static final long MAX_DELAY_MILLIS = 3000;
	static final long CACHE_HOURS = 6;
	private static final int FAILURES_TO_STOP = 2;
	/**
	 * Saved to disk this often during a batch too, so closing the client mid-batch keeps most of it.
	 */
	private static final int SAVE_EVERY_LOOKUPS = 10;
	private static final File CACHE_FILE = new File(RuneLite.RUNELITE_DIR, "pvptools-hiscores.json");

	enum Kind
	{
		TEAM,
		ENEMIES
	}

	/**
	 * Who a team lookup covers: teammates on screen, everyone in your friends chat or clan chat
	 * channel (online, on screen or not), or every member of your clan, offline too.
	 */
	enum Source
	{
		ON_SCREEN("Team on screen"),
		FRIENDS_CHAT("Friends chat"),
		CLAN_CHAT("Clan chat"),
		ALL_CLAN_MEMBERS("All clan members");

		private final String label;

		Source(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Status
	{
		QUEUED,
		FOUND,
		NOT_FOUND,
		FAILED,
		/**
		 * Over the limit, or the batch was cancelled/stopped first.
		 */
		NOT_LOOKED_UP
	}

	/**
	 * Levels from the hiscores; 0 = unranked in that skill.
	 */
	@Value
	static class Stats
	{
		int attack;
		int strength;
		int defence;
		int hitpoints;
		int ranged;
		int magic;
		int prayer;
	}

	/**
	 * Who to look up, as seen when the button was pressed.
	 */
	@Value
	static class Target
	{
		String name;
		/**
		 * 0 if not on screen (worked out from the hiscores instead - combatLevel()).
		 */
		int combat;
		/**
		 * Team cape number, 0 for none.
		 */
		int team;
		/**
		 * Clan rank title, "" if unknown.
		 */
		String rank;
	}

	@Value
	static class Row
	{
		String name;
		int combat;
		int team;
		String rank;
		Status status;
		/**
		 * Null unless FOUND.
		 */
		Stats stats;

		Row with(Status status, Stats stats)
		{
			return new Row(name, combat, team, rank, status, stats);
		}
	}

	@Value
	static class Batch
	{
		static final Batch EMPTY = new Batch("", Collections.emptyList(), false, "");

		/**
		 * What was looked up, e.g. "Friends chat" - the team group's title.
		 */
		String title;
		List<Row> rows;
		boolean running;
		String message;

		long count(Status status)
		{
			return rows.stream().filter(r -> r.status == status).count();
		}
	}

	/**
	 * A display/export group: the team, one team cape, or Others.
	 */
	@Value
	static class Group
	{
		int team;
		String title;
		List<Row> rows;
	}

	private static final class CacheEntry
	{
		long time;
		/**
		 * Null = not on the hiscores.
		 */
		Stats stats;
	}

	private final HiscoreClient hiscoreClient;
	private final ScheduledExecutorService executor;
	private final Gson gson;

	private final Map<Kind, Batch> batches = new HashMap<>();
	private final Map<Kind, Integer> generations = new HashMap<>();
	private Map<String, CacheEntry> cache;
	private int unsavedLookups;
	/**
	 * Tests point this at a temporary file.
	 */
	File cacheFile = CACHE_FILE;
	private Consumer<Kind> listener = kind ->
	{
	};

	@Inject
	HiscoreLookups(HiscoreClient hiscoreClient, ScheduledExecutorService executor, Gson gson)
	{
		this.hiscoreClient = hiscoreClient;
		this.executor = executor;
		this.gson = gson;
	}

	/**
	 * Told the kind whenever its batch changes (any thread).
	 */
	void setListener(Consumer<Kind> listener)
	{
		this.listener = listener;
	}

	synchronized Batch getBatch(Kind kind)
	{
		return batches.getOrDefault(kind, Batch.EMPTY);
	}

	boolean isRunning(Kind kind)
	{
		return getBatch(kind).running;
	}

	/**
	 * Starts a batch for these players (replacing any running one of the same kind). Cached
	 * results are used straight away; at most limit uncached names are looked up.
	 */
	void start(Kind kind, String title, List<Target> targets, int limit)
	{
		int generation;
		Batch batch;
		synchronized (this)
		{
			loadCache();
			int cap = Math.max(1, Math.min(MAX_LIMIT, limit));
			long now = System.currentTimeMillis();
			List<Row> rows = new ArrayList<>();
			int queued = 0;
			for (Target target : targets)
			{
				Row row = new Row(target.name, target.combat, target.team, target.rank, Status.QUEUED, null);
				CacheEntry cached = cache.get(key(target.name));
				if (cached != null && now - cached.time < TimeUnit.HOURS.toMillis(CACHE_HOURS))
				{
					rows.add(cached.stats != null ? row.with(Status.FOUND, cached.stats) : row.with(Status.NOT_FOUND, null));
				}
				else if (queued < cap)
				{
					rows.add(row);
					queued++;
				}
				else
				{
					rows.add(row.with(Status.NOT_LOOKED_UP, null));
				}
			}
			generation = generations.merge(kind, 1, Integer::sum);
			long overLimit = rows.stream().filter(r -> r.status == Status.NOT_LOOKED_UP).count();
			String message = rows.isEmpty() ? "Nobody to look up."
				: queued == 0 ? "All from the cache."
				: "Looking up " + queued + (overLimit > 0 ? " (" + overLimit + " more next press)" : "") + "...";
			batch = new Batch(title, Collections.unmodifiableList(rows), queued > 0, message);
			batches.put(kind, batch);
		}
		listener.accept(kind);
		if (batch.running)
		{
			executor.execute(() -> next(kind, generation, 0));
		}
	}

	void cancel(Kind kind)
	{
		synchronized (this)
		{
			generations.merge(kind, 1, Integer::sum);
			Batch batch = getBatch(kind);
			if (!batch.running)
			{
				return;
			}
			finish(kind, batch, "Cancelled.");
			saveCache();
		}
		listener.accept(kind);
	}

	private void next(Kind kind, int generation, int failures)
	{
		Row row;
		synchronized (this)
		{
			if (generations.get(kind) != generation)
			{
				return;
			}
			row = getBatch(kind).rows.stream().filter(r -> r.status == Status.QUEUED).findFirst().orElse(null);
			if (row == null)
			{
				long left = getBatch(kind).count(Status.NOT_LOOKED_UP);
				finish(kind, getBatch(kind), left > 0 ? "Done - " + left + " more over the limit: press Look up again." : "Done.");
				saveCache();
			}
		}
		if (row == null)
		{
			listener.accept(kind);
			return;
		}

		hiscoreClient.lookupAsync(row.name.replace('\u00A0', ' '), HiscoreEndpoint.NORMAL).whenComplete((result, error) ->
		{
			boolean stop;
			synchronized (this)
			{
				if (generations.get(kind) != generation)
				{
					return;
				}
				Row done = error != null ? row.with(Status.FAILED, null)
					: result == null ? row.with(Status.NOT_FOUND, null)
					: row.with(Status.FOUND, stats(result));
				if (error == null)
				{
					CacheEntry entry = new CacheEntry();
					entry.time = System.currentTimeMillis();
					entry.stats = done.stats;
					cache.put(key(row.name), entry);
					if (++unsavedLookups >= SAVE_EVERY_LOOKUPS)
					{
						saveCache();
					}
				}
				else
				{
					log.debug("PvP Tools: hiscore lookup of {} failed", row.name, error);
				}
				Batch batch = replace(getBatch(kind), row, done);
				int failuresNow = error != null ? failures + 1 : 0;
				stop = failuresNow >= FAILURES_TO_STOP || error != null && String.valueOf(error.getMessage()).contains("429");
				if (stop)
				{
					finish(kind, batch, "Stopped - the hiscores refused requests. Try again later.");
					saveCache();
				}
				else
				{
					long left = batch.count(Status.QUEUED);
					batches.put(kind, new Batch(batch.title, batch.rows, true, left == 0 ? "Finishing..." : left + " left..."));
					long delay = ThreadLocalRandom.current().nextLong(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS + 1);
					executor.schedule(() -> next(kind, generation, failuresNow), delay, TimeUnit.MILLISECONDS);
				}
			}
			listener.accept(kind);
		});
	}

	/**
	 * Ends a batch: anything still queued is marked not looked up. Caller holds the lock.
	 */
	private void finish(Kind kind, Batch batch, String message)
	{
		List<Row> rows = new ArrayList<>();
		for (Row row : batch.rows)
		{
			rows.add(row.status == Status.QUEUED ? row.with(Status.NOT_LOOKED_UP, null) : row);
		}
		batches.put(kind, new Batch(batch.title, Collections.unmodifiableList(rows), false, message));
	}

	private static Batch replace(Batch batch, Row old, Row replacement)
	{
		List<Row> rows = new ArrayList<>(batch.rows);
		rows.set(rows.indexOf(old), replacement);
		return new Batch(batch.title, Collections.unmodifiableList(rows), batch.running, batch.message);
	}

	static Stats stats(HiscoreResult result)
	{
		return new Stats(level(result, HiscoreSkill.ATTACK), level(result, HiscoreSkill.STRENGTH),
			level(result, HiscoreSkill.DEFENCE), level(result, HiscoreSkill.HITPOINTS), level(result, HiscoreSkill.RANGED),
			level(result, HiscoreSkill.MAGIC), level(result, HiscoreSkill.PRAYER));
	}

	/**
	 * The skill's level, 0 if unranked - same rule as the Hiscore panel (a level only counts with
	 * experience to back it).
	 */
	private static int level(HiscoreResult result, HiscoreSkill skill)
	{
		Skill s = result.getSkill(skill);
		return s != null && s.getExperience() > -1 ? s.getLevel() : 0;
	}

	/**
	 * The combat level seen on screen, else worked out from the hiscores (unranked counted as 1,
	 * Hitpoints as 10) - 0 if neither.
	 */
	static int combatLevel(Row row)
	{
		if (row.combat > 0 || row.stats == null)
		{
			return row.combat;
		}
		Stats s = row.stats;
		return Experience.getCombatLevel(Math.max(1, s.attack), Math.max(1, s.strength), Math.max(1, s.defence),
			Math.max(10, s.hitpoints), Math.max(1, s.magic), Math.max(1, s.ranged), Math.max(1, s.prayer));
	}

	private static String key(String name)
	{
		return Text.standardize(name);
	}

	// ============================================================================================
	// Pure helpers - grouping, Defence split, CSV
	// ============================================================================================

	/**
	 * The team as one group; enemies like the Enemy Levels charts - a group per cape with at least
	 * minimumCapeCount players (most first), then Others.
	 */
	static List<Group> groups(Kind kind, String title, List<Row> rows, int minimumCapeCount, Map<Integer, String> capeNames)
	{
		List<Group> groups = new ArrayList<>();
		if (rows.isEmpty())
		{
			return groups;
		}
		if (kind == Kind.TEAM)
		{
			groups.add(new Group(0, title.isEmpty() ? "Team" : title, rows));
			return groups;
		}

		Map<Integer, List<Row>> byTeam = new LinkedHashMap<>();
		List<Row> others = new ArrayList<>();
		for (Row row : rows)
		{
			if (row.team > 0)
			{
				byTeam.computeIfAbsent(row.team, t -> new ArrayList<>()).add(row);
			}
			else
			{
				others.add(row);
			}
		}
		List<Map.Entry<Integer, List<Row>>> capes = new ArrayList<>();
		for (Map.Entry<Integer, List<Row>> team : byTeam.entrySet())
		{
			if (team.getValue().size() >= minimumCapeCount)
			{
				capes.add(team);
			}
			else
			{
				others.addAll(team.getValue());
			}
		}
		capes.sort((a, b) -> a.getValue().size() != b.getValue().size()
			? b.getValue().size() - a.getValue().size() : a.getKey() - b.getKey());
		for (Map.Entry<Integer, List<Row>> cape : capes)
		{
			String name = capeNames.get(cape.getKey());
			groups.add(new Group(cape.getKey(), name != null ? name + " (team " + cape.getKey() + ")" : "Team " + cape.getKey(),
				cape.getValue()));
		}
		if (!others.isEmpty())
		{
			groups.add(new Group(0, capes.isEmpty() ? "Enemies" : "Others", others));
		}
		return groups;
	}

	/**
	 * Found players by Defence: 1-5, 6-39, 40+, unranked in Defence.
	 */
	static int[] defenceSplit(List<Row> rows)
	{
		int[] split = new int[4];
		for (Row row : rows)
		{
			if (row.status != Status.FOUND)
			{
				continue;
			}
			int defence = row.stats.defence;
			split[defence <= 0 ? 3 : defence <= 5 ? 0 : defence < 40 ? 1 : 2]++;
		}
		return split;
	}

	/**
	 * Spreadsheet-friendly: one line per player, unranked skills left blank.
	 */
	static String csv(List<Group> groups)
	{
		StringBuilder sb = new StringBuilder("Group,Name,Rank,Combat,Attack,Strength,Defence,Hitpoints,Ranged,Magic,Prayer,Status\n");
		for (Group group : groups)
		{
			for (Row row : group.rows)
			{
				sb.append(csvField(group.title)).append(',').append(csvField(row.name.replace('\u00A0', ' ')))
					.append(',').append(csvField(row.rank)).append(',').append(combatLevel(row) > 0 ? String.valueOf(combatLevel(row)) : "");
				Stats s = row.stats;
				for (int level : s != null
					? new int[]{s.attack, s.strength, s.defence, s.hitpoints, s.ranged, s.magic, s.prayer}
					: new int[7])
				{
					sb.append(',').append(level > 0 ? String.valueOf(level) : "");
				}
				sb.append(',').append(statusText(row.status)).append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * Discord-friendly: per group a bold title line with the Defence split, then a code block
	 * table of the players found (aligned columns, unranked as "-"), then who wasn't on the
	 * hiscores or wasn't looked up.
	 */
	static String discord(List<Group> groups)
	{
		StringBuilder sb = new StringBuilder();
		for (Group group : groups)
		{
			List<Row> found = new ArrayList<>();
			List<String> notFound = new ArrayList<>();
			int notLookedUp = 0;
			for (Row row : group.rows)
			{
				if (row.status == Status.FOUND)
				{
					found.add(row);
				}
				else if (row.status == Status.NOT_FOUND)
				{
					notFound.add(row.name.replace('\u00A0', ' '));
				}
				else
				{
					notLookedUp++;
				}
			}

			int[] split = defenceSplit(group.rows);
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append("**").append(group.title).append(" - ").append(group.rows.size()).append("**")
				.append(" | Def 1-5: ").append(split[0]).append(", 6-39: ").append(split[1]).append(", 40+: ").append(split[2]);
			if (split[3] > 0)
			{
				sb.append(", unranked: ").append(split[3]);
			}
			sb.append('\n');

			if (!found.isEmpty())
			{
				List<String[]> lines = new ArrayList<>();
				lines.add(DISCORD_HEADERS);
				for (Row row : found)
				{
					Stats s = row.stats;
					lines.add(new String[]{row.name.replace('\u00A0', ' '), dash(combatLevel(row)), dash(s.attack), dash(s.strength),
						dash(s.defence), dash(s.hitpoints), dash(s.ranged), dash(s.magic), dash(s.prayer)});
				}
				int[] widths = new int[DISCORD_HEADERS.length];
				for (String[] line : lines)
				{
					for (int i = 0; i < line.length; i++)
					{
						widths[i] = Math.max(widths[i], line[i].length());
					}
				}
				sb.append("```\n");
				for (int l = 0; l < lines.size(); l++)
				{
					sb.append(tableLine(lines.get(l), widths)).append('\n');
					if (l == 0)
					{
						sb.append(separatorLine(widths)).append('\n');
					}
				}
				sb.append("```\n");
			}
			if (!notFound.isEmpty())
			{
				sb.append("Not on the hiscores: ").append(String.join(", ", notFound)).append('\n');
			}
			if (notLookedUp > 0)
			{
				sb.append("Not looked up: ").append(notLookedUp).append('\n');
			}
		}
		return sb.toString();
	}

	private static final String[] DISCORD_HEADERS = {"Name", "Cmbt", "Atk", "Str", "Def", "HP", "Rng", "Mag", "Pray"};

	private static String dash(int level)
	{
		return level > 0 ? String.valueOf(level) : "-";
	}

	/**
	 * "Name         | 58   | 40  | ..." - each cell padded to its column, trailing spaces trimmed.
	 */
	private static String tableLine(String[] cells, int[] widths)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < cells.length; i++)
		{
			if (i > 0)
			{
				sb.append(" | ");
			}
			sb.append(cells[i]);
			for (int pad = cells[i].length(); pad < widths[i]; pad++)
			{
				sb.append(' ');
			}
		}
		return sb.toString().replaceAll("\\s+$", "");
	}

	/**
	 * "-------------|------|-----" lined up with tableLine's pipes.
	 */
	private static String separatorLine(int[] widths)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < widths.length; i++)
		{
			if (i > 0)
			{
				sb.append('|');
			}
			int dashes = widths[i] + (i == 0 || i == widths.length - 1 ? 1 : 2);
			for (int d = 0; d < dashes; d++)
			{
				sb.append('-');
			}
		}
		return sb.toString();
	}

	static String statusText(Status status)
	{
		switch (status)
		{
			case QUEUED:
				return "queued";
			case FOUND:
				return "found";
			case NOT_FOUND:
				return "not on hiscores";
			case FAILED:
				return "failed";
			default:
				return "not looked up";
		}
	}

	private static String csvField(String text)
	{
		return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
	}

	// ============================================================================================
	// Disk cache
	// ============================================================================================

	/**
	 * Caller holds the lock.
	 */
	private void loadCache()
	{
		if (cache != null)
		{
			return;
		}
		cache = new HashMap<>();
		if (!cacheFile.isFile())
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(cacheFile.toPath(), StandardCharsets.UTF_8))
		{
			Map<String, CacheEntry> loaded = gson.fromJson(reader, new TypeToken<HashMap<String, CacheEntry>>()
			{
			}.getType());
			if (loaded != null)
			{
				long oldest = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(CACHE_HOURS);
				loaded.forEach((name, entry) ->
				{
					if (entry != null && entry.time > oldest)
					{
						cache.put(name, entry);
					}
				});
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("PvP Tools: couldn't read the hiscore cache", e);
		}
	}

	/**
	 * Caller holds the lock. Writes a copy off the lock's thread.
	 */
	private void saveCache()
	{
		if (cache == null)
		{
			return;
		}
		unsavedLookups = 0;
		String json = gson.toJson(new HashMap<>(cache));
		File file = cacheFile;
		executor.execute(() ->
		{
			try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
			{
				writer.write(json);
			}
			catch (IOException e)
			{
				log.warn("PvP Tools: couldn't save the hiscore cache", e);
			}
		});
	}
}
