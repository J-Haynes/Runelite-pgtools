package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import okhttp3.HttpUrl;
import org.junit.Test;

public class ScoutReportTest
{
	private static Map<Integer, Integer> counts(int... teamAndCount)
	{
		Map<Integer, Integer> map = new HashMap<>();
		for (int i = 0; i < teamAndCount.length; i += 2)
		{
			map.put(teamAndCount[i], teamAndCount[i + 1]);
		}
		return map;
	}

	@Test
	public void locationLineThenOneCapePerLineMostFirst()
	{
		Map<Integer, String> names = Map.of(50, "EoP");
		String report = ScoutReport.format(counts(38, 3, 50, 10, 12, 3), names, 2, 308, "Ferox Enclave", 15);

		assertEquals("W308, Ferox Enclave, lvl 15 wild.\n"
			+ "10x Team-50 (EoP),\n"
			+ "3x Team-12,\n"
			+ "3x Team-38", report);
	}

	@Test
	public void capesUnderTheMinimumAreLeftOut()
	{
		String report = ScoutReport.format(counts(50, 1, 38, 2), Map.of(), 2, 308, null, null);
		assertEquals("W308.\n2x Team-38", report);
	}

	@Test
	public void noCapesSaysSo()
	{
		String report = ScoutReport.format(counts(50, 1), Map.of(), 2, 301, null, 20);
		assertEquals("W301, lvl 20 wild.\n" + ScoutReport.NO_CAPES, report);
	}

	@Test
	public void onlyDiscordWebhooksAreAccepted()
	{
		assertTrue(ScoutReporter.isDiscordWebhook(HttpUrl.parse("https://discord.com/api/webhooks/1/abc")));
		assertTrue(ScoutReporter.isDiscordWebhook(HttpUrl.parse("https://ptb.discord.com/api/webhooks/1/abc")));
		assertTrue(ScoutReporter.isDiscordWebhook(HttpUrl.parse("https://discordapp.com/api/webhooks/1/abc")));
		assertFalse(ScoutReporter.isDiscordWebhook(HttpUrl.parse("http://discord.com/api/webhooks/1/abc")));
		assertFalse(ScoutReporter.isDiscordWebhook(HttpUrl.parse("https://evil.com/api/webhooks/1/abc")));
		assertFalse(ScoutReporter.isDiscordWebhook(HttpUrl.parse("https://notdiscord.com/api/webhooks/1/abc")));
		assertFalse(ScoutReporter.isDiscordWebhook(HttpUrl.parse("https://discord.com/channels/1")));
	}

	@Test
	public void postsUnderTheConfiguredNameElseYourInGameName()
	{
		assertEquals("Scout Bot", ScoutReporter.posterName("  Scout Bot ", "Me"));
		assertEquals("Me", ScoutReporter.posterName("", "<img=2>Me"));
		assertEquals("Me", ScoutReporter.posterName("   ", "Me"));
		assertNull(ScoutReporter.posterName("", null));
		assertEquals(80, ScoutReporter.posterName("x".repeat(100), "Me").length());
	}

	@Test
	public void minimapIsCutAsAScaledUpCircle()
	{
		// A stretched frame, twice the canvas: the minimap at canvas (10, 10) is at (20, 20) in it.
		BufferedImage frame = new BufferedImage(200, 160, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = frame.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, 200, 160);
		g.setColor(Color.RED);
		g.fillRect(20, 20, 40, 40);
		g.dispose();

		// Not turned (rotation 0), you in the middle.
		BufferedImage minimap = ScoutMap.minimap(frame, new Rectangle(10, 10, 20, 20), 100, 20, 20, 0, 4,
			3200, 3600, 0, List.of());
		assertEquals(80, minimap.getWidth());
		assertEquals(80, minimap.getHeight());
		assertEquals(Color.RED.getRGB(), minimap.getRGB(40, 40));
		// Outside the circle: transparent, not the interface around the minimap.
		assertEquals(0, minimap.getRGB(0, 0) >>> 24);
	}

	@Test
	public void drawnMapIsNorthUpWithYouInTheMiddle()
	{
		// Map square y 2 (world y 128-191) red, y 1 (64-127) blue; you on the line between.
		Function<int[], BufferedImage> tiles = square ->
		{
			BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = tile.createGraphics();
			g.setColor(square[2] == 2 ? Color.RED : Color.BLUE);
			g.fillRect(0, 0, 256, 256);
			g.dispose();
			return tile;
		};
		BufferedImage map = ScoutMap.drawn(tiles, 96, 128, 0, 16,
			List.of(new ScoutMap.Dot(96, 128, ScoutMap.YOU_COLOR)), List.of(), null);

		assertEquals(ScoutMap.DRAWN_SIZE, map.getWidth());
		assertEquals(Color.RED.getRGB(), map.getRGB(ScoutMap.DRAWN_SIZE / 2, 5));
		assertEquals(Color.BLUE.getRGB(), map.getRGB(ScoutMap.DRAWN_SIZE / 2, ScoutMap.DRAWN_SIZE - 5));
		assertEquals(Color.WHITE.getRGB(), map.getRGB(ScoutMap.DRAWN_SIZE / 2, ScoutMap.DRAWN_SIZE / 2));
	}

	@Test
	public void capeCounterGoesOnTheDrawnMapAndBesideTheMinimap()
	{
		List<ScoutMap.Cape> capes = List.of(new ScoutMap.Cape(null, "10x Team-50 (EoP)"), new ScoutMap.Cape(null, "3x Team-12"));

		BufferedImage drawn = new BufferedImage(ScoutMap.DRAWN_SIZE, ScoutMap.DRAWN_SIZE, BufferedImage.TYPE_INT_RGB);
		BufferedImage onMap = ScoutMap.withCapes(drawn, capes);
		assertEquals(ScoutMap.DRAWN_SIZE, onMap.getWidth());
		assertEquals(ScoutMap.DRAWN_SIZE, onMap.getHeight());

		BufferedImage minimap = new BufferedImage(150, 150, BufferedImage.TYPE_INT_ARGB);
		assertTrue(ScoutMap.withCapes(minimap, capes).getWidth() > 150);

		assertSame(minimap, ScoutMap.withCapes(minimap, List.of()));
		assertNull(ScoutMap.withCapes(null, List.of()));
	}

	@Test
	public void enemyLevelChartsMatchTheCapesTabAndGoBesideThePicture()
	{
		EnemyLevels levels = new EnemyLevels(List.of(52, 55, 61, 63, 70),
			List.of(new EnemyLevels.Group(50, List.of(55, 61, 63))), List.of(52, 70));
		List<EnemyLevels.LevelRange> ranges = EnemyLevels.parseRanges("50-59, 60-64");
		List<EnemyLevels.Chart> charts = EnemyLevels.charts(levels, ranges, Map.of(50, "EoP"));

		assertEquals(3, charts.size());
		assertEquals(new EnemyLevels.Chart(0, "All enemies", 5, "52-70, median 61", ranges, List.of(2, 2), 1), charts.get(0));
		assertEquals(new EnemyLevels.Chart(50, "EoP (team 50)", 3, "55-63, median 61", ranges, List.of(1, 2), 0), charts.get(1));
		assertEquals("Others", charts.get(2).getTitle());
		assertTrue(EnemyLevels.charts(EnemyLevels.EMPTY, ranges, Map.of()).isEmpty());

		BufferedImage map = new BufferedImage(ScoutMap.DRAWN_SIZE, ScoutMap.DRAWN_SIZE, BufferedImage.TYPE_INT_ARGB);
		BufferedImage picture = ScoutMap.withEnemyLevels(map, charts, Map.of());
		assertTrue(picture.getWidth() > ScoutMap.DRAWN_SIZE);
		assertEquals(ScoutMap.DRAWN_SIZE, picture.getHeight());
		assertSame(map, ScoutMap.withEnemyLevels(map, List.of(), Map.of()));
		// No map: the charts alone.
		assertTrue(ScoutMap.withEnemyLevels(null, charts, Map.of()).getWidth() < ScoutMap.DRAWN_SIZE);
	}

	@Test
	public void locationSpamFormat()
	{
		assertEquals("Location: W318, Ferox Enclave, lvl 15 wild", ChatHotkeys.formatLocation(318, "Ferox Enclave", 15));
		assertEquals("Location: W318, Wilderness, lvl 3 wild", ChatHotkeys.formatLocation(318, null, 3));
		assertEquals("Location: W318, Lumbridge", ChatHotkeys.formatLocation(318, "Lumbridge", null));
	}
}
