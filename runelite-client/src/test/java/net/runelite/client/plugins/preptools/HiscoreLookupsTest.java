package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.Skill;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HiscoreLookupsTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private HiscoreClient hiscoreClient;
	private ScheduledExecutorService executor;
	private HiscoreLookups lookups;

	@Before
	public void before()
	{
		hiscoreClient = mock(HiscoreClient.class);
		executor = mock(ScheduledExecutorService.class);
		// Run everything straight away, delays included.
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(executor).execute(any());
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(executor).schedule(any(Runnable.class), anyLong(), any());
		lookups = newLookups();
	}

	private HiscoreLookups newLookups()
	{
		HiscoreLookups l = new HiscoreLookups(hiscoreClient, executor, new Gson());
		l.cacheFile = new java.io.File(folder.getRoot(), "hiscores.json");
		return l;
	}

	private static HiscoreResult result(String name, int defence)
	{
		Skill unranked = new Skill(-1, 1, -1);
		return new HiscoreResult(name, Map.of(
			HiscoreSkill.ATTACK, new Skill(5, 60, 300000),
			HiscoreSkill.STRENGTH, new Skill(5, 70, 800000),
			HiscoreSkill.DEFENCE, defence > 0 ? new Skill(5, defence, 1000) : unranked,
			HiscoreSkill.HITPOINTS, new Skill(5, 65, 500000),
			HiscoreSkill.RANGED, unranked,
			HiscoreSkill.MAGIC, new Skill(5, 50, 100000),
			HiscoreSkill.PRAYER, new Skill(5, 31, 15000)));
	}

	private void found(String name, int defence)
	{
		when(hiscoreClient.lookupAsync(name, HiscoreEndpoint.NORMAL)).thenReturn(CompletableFuture.completedFuture(result(name, defence)));
	}

	private void notFound(String name)
	{
		when(hiscoreClient.lookupAsync(name, HiscoreEndpoint.NORMAL)).thenReturn(CompletableFuture.completedFuture(null));
	}

	private static HiscoreLookups.Target target(String name, int team)
	{
		return new HiscoreLookups.Target(name, 70, team, "");
	}

	@Test
	public void looksUpOneAtATimeThenUsesTheCache()
	{
		found("Alpha", 1);
		notFound("Bravo");
		List<HiscoreLookups.Target> targets = List.of(target("Alpha", 50), target("Bravo", 0));

		lookups.start(HiscoreLookups.Kind.ENEMIES, "Enemies", targets, 100);
		HiscoreLookups.Batch batch = lookups.getBatch(HiscoreLookups.Kind.ENEMIES);
		assertFalse(batch.isRunning());
		assertEquals("Done.", batch.getMessage());
		assertEquals(HiscoreLookups.Status.FOUND, batch.getRows().get(0).getStatus());
		assertEquals(new HiscoreLookups.Stats(60, 70, 1, 65, 0, 50, 31), batch.getRows().get(0).getStats());
		assertEquals(HiscoreLookups.Status.NOT_FOUND, batch.getRows().get(1).getStatus());
		// Spaced out: one delay between the two lookups.
		verify(executor, times(2)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));

		// Again, and from a fresh start (the disk cache): nothing new is looked up.
		lookups.start(HiscoreLookups.Kind.ENEMIES, "Enemies", targets, 100);
		newLookups().start(HiscoreLookups.Kind.ENEMIES, "Enemies", targets, 100);
		verify(hiscoreClient, times(1)).lookupAsync("Alpha", HiscoreEndpoint.NORMAL);
		verify(hiscoreClient, times(1)).lookupAsync("Bravo", HiscoreEndpoint.NORMAL);
		assertEquals("All from the cache.", lookups.getBatch(HiscoreLookups.Kind.ENEMIES).getMessage());
	}

	@Test
	public void theLimitCapsLookupsPerPress()
	{
		found("Alpha", 40);
		found("Bravo", 40);

		lookups.start(HiscoreLookups.Kind.TEAM, "Team on screen", List.of(target("Alpha", 0), target("Bravo", 0), target("Charlie", 0)), 2);

		HiscoreLookups.Batch batch = lookups.getBatch(HiscoreLookups.Kind.TEAM);
		assertEquals(HiscoreLookups.Status.NOT_LOOKED_UP, batch.getRows().get(2).getStatus());
		verify(hiscoreClient, never()).lookupAsync(eq("Charlie"), any());
		// A big clan: the rest come with the next press.
		assertEquals("Done - 1 more over the limit: press Look up again.", batch.getMessage());
		found("Charlie", 40);
		lookups.start(HiscoreLookups.Kind.TEAM, "All clan members", List.of(target("Alpha", 0), target("Bravo", 0), target("Charlie", 0)), 2);
		verify(hiscoreClient, times(1)).lookupAsync("Alpha", HiscoreEndpoint.NORMAL);
		assertEquals(3, lookups.getBatch(HiscoreLookups.Kind.TEAM).count(HiscoreLookups.Status.FOUND));
	}

	@Test
	public void channelMembersOffScreenGetTheirCombatFromTheHiscores()
	{
		found("Alpha", 1);
		// Off screen: no combat level seen.
		lookups.start(HiscoreLookups.Kind.TEAM, "Friends chat", List.of(new HiscoreLookups.Target("Alpha", 0, 0, "Recruit")), 100);

		HiscoreLookups.Batch batch = lookups.getBatch(HiscoreLookups.Kind.TEAM);
		assertEquals("Friends chat", batch.getTitle());
		HiscoreLookups.Row row = batch.getRows().get(0);
		// 60 att, 70 str, 1 def, 65 hp, unranked ranged, 50 magic, 31 prayer.
		assertEquals(net.runelite.api.Experience.getCombatLevel(60, 70, 1, 65, 50, 1, 31), HiscoreLookups.combatLevel(row));
		assertEquals("Friends chat", HiscoreLookups.groups(HiscoreLookups.Kind.TEAM, batch.getTitle(), batch.getRows(), 2, Map.of())
			.get(0).getTitle());
		// Seen on screen wins.
		assertEquals(70, HiscoreLookups.combatLevel(new HiscoreLookups.Row("Bravo", 70, 0, "", HiscoreLookups.Status.FOUND, row.getStats())));

		assertTrue(HiscoreLookups.csv(HiscoreLookups.groups(HiscoreLookups.Kind.TEAM, batch.getTitle(), batch.getRows(), 2, Map.of()))
			.contains("Friends chat,Alpha,Recruit,"));

		lookups.start(HiscoreLookups.Kind.TEAM, "Clan chat", List.of(), 100);
		assertEquals("Nobody to look up.", lookups.getBatch(HiscoreLookups.Kind.TEAM).getMessage());
	}

	@Test
	public void discordCopyIsAnAlignedTablePerGroup()
	{
		List<HiscoreLookups.Row> rows = List.of(
			new HiscoreLookups.Row("000OOOOO000P", 58, 0, "", HiscoreLookups.Status.FOUND, new HiscoreLookups.Stats(40, 80, 1, 75, 80, 70, 1)),
			new HiscoreLookups.Row("Plowet", 52, 0, "", HiscoreLookups.Status.FOUND, new HiscoreLookups.Stats(1, 80, 0, 72, 70, 13, 1)),
			new HiscoreLookups.Row("Gone", 60, 0, "", HiscoreLookups.Status.NOT_FOUND, null),
			new HiscoreLookups.Row("Later", 60, 0, "", HiscoreLookups.Status.NOT_LOOKED_UP, null));

		String discord = HiscoreLookups.discord(HiscoreLookups.groups(HiscoreLookups.Kind.TEAM, "Clan chat", rows, 2, Map.of()));

		assertEquals("**Clan chat - 4** | Def 1-5: 1, 6-39: 0, 40+: 0, unranked: 1\n"
			+ "```\n"
			+ "Name         | Cmbt | Atk | Str | Def | HP | Rng | Mag | Pray\n"
			+ "-------------|------|-----|-----|-----|----|-----|-----|-----\n"
			+ "000OOOOO000P | 58   | 40  | 80  | 1   | 75 | 80  | 70  | 1\n"
			+ "Plowet       | 52   | 1   | 80  | -   | 72 | 70  | 13  | 1\n"
			+ "```\n"
			+ "Not on the hiscores: Gone\n"
			+ "Not looked up: 1\n", discord);
	}

	@Test
	public void resultsAreSavedDuringABatchNotOnlyAtTheEnd()
	{
		// Cancelled as soon as the first lookup lands - nothing reaches "Done".
		when(hiscoreClient.lookupAsync(anyString(), any())).thenAnswer(i ->
		{
			lookups.cancel(HiscoreLookups.Kind.TEAM);
			return CompletableFuture.completedFuture(result(i.getArgument(0), 40));
		});
		lookups.start(HiscoreLookups.Kind.TEAM, "Team on screen", List.of(target("Alpha", 0), target("Bravo", 0)), 100);
		assertEquals("Cancelled.", lookups.getBatch(HiscoreLookups.Kind.TEAM).getMessage());
		assertTrue(lookups.cacheFile.isFile());
	}

	@Test
	public void stopsWhenTheHiscoresKeepFailing()
	{
		CompletableFuture<HiscoreResult> failed = new CompletableFuture<>();
		failed.completeExceptionally(new IOException("Error retrieving data from hiscores: 503"));
		when(hiscoreClient.lookupAsync(anyString(), any())).thenReturn(failed);

		lookups.start(HiscoreLookups.Kind.ENEMIES, "Enemies", List.of(target("Alpha", 0), target("Bravo", 0), target("Charlie", 0)), 100);

		HiscoreLookups.Batch batch = lookups.getBatch(HiscoreLookups.Kind.ENEMIES);
		assertFalse(batch.isRunning());
		assertTrue(batch.getMessage().startsWith("Stopped"));
		assertEquals(2, batch.count(HiscoreLookups.Status.FAILED));
		assertEquals(HiscoreLookups.Status.NOT_LOOKED_UP, batch.getRows().get(2).getStatus());
	}

	@Test
	public void enemiesAreGroupedByCapeWithADefenceSplitAndCsv()
	{
		HiscoreLookups.Stats pure = new HiscoreLookups.Stats(60, 70, 1, 65, 0, 50, 31);
		HiscoreLookups.Stats mid = new HiscoreLookups.Stats(60, 70, 20, 65, 0, 50, 31);
		HiscoreLookups.Stats tank = new HiscoreLookups.Stats(60, 70, 45, 65, 0, 50, 31);
		HiscoreLookups.Stats unranked = new HiscoreLookups.Stats(60, 70, 0, 65, 0, 50, 31);
		List<HiscoreLookups.Row> rows = List.of(
			new HiscoreLookups.Row("Alpha", 70, 50, "", HiscoreLookups.Status.FOUND, pure),
			new HiscoreLookups.Row("Bravo", 70, 50, "", HiscoreLookups.Status.FOUND, tank),
			new HiscoreLookups.Row("Charlie", 70, 50, "", HiscoreLookups.Status.NOT_FOUND, null),
			new HiscoreLookups.Row("Delta", 70, 12, "", HiscoreLookups.Status.FOUND, mid),
			new HiscoreLookups.Row("Echo, Jr", 70, 0, "", HiscoreLookups.Status.FOUND, unranked));

		List<HiscoreLookups.Group> groups = HiscoreLookups.groups(HiscoreLookups.Kind.ENEMIES, "Enemies", rows, 2, Map.of(50, "EoP"));
		assertEquals(2, groups.size());
		assertEquals("EoP (team 50)", groups.get(0).getTitle());
		assertEquals(3, groups.get(0).getRows().size());
		// Team 12 has one wearer - under the minimum, so with the capeless under Others.
		assertEquals("Others", groups.get(1).getTitle());
		assertEquals(2, groups.get(1).getRows().size());

		assertArrayEquals(new int[]{1, 0, 1, 0}, HiscoreLookups.defenceSplit(groups.get(0).getRows()));
		assertArrayEquals(new int[]{0, 1, 0, 1}, HiscoreLookups.defenceSplit(groups.get(1).getRows()));

		assertEquals(1, HiscoreLookups.groups(HiscoreLookups.Kind.TEAM, "Friends chat", rows, 2, Map.of()).size());

		String csv = HiscoreLookups.csv(groups);
		assertTrue(csv.startsWith("Group,Name,Rank,Combat,Attack,Strength,Defence,Hitpoints,Ranged,Magic,Prayer,Status\n"));
		assertTrue(csv.contains("EoP (team 50),Alpha,,70,60,70,1,65,,50,31,found\n"));
		assertTrue(csv.contains("EoP (team 50),Charlie,,70,,,,,,,,not on hiscores\n"));
		assertTrue(csv.contains("Others,\"Echo, Jr\",,70,60,70,,65,,50,31,found\n"));
	}
}
