package net.runelite.client.plugins.preptools;

import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	public void locationSpamFormat()
	{
		assertEquals("Location: W318, Ferox Enclave, lvl 15 wild", ChatHotkeys.formatLocation(318, "Ferox Enclave", 15));
		assertEquals("Location: W318, Wilderness, lvl 3 wild", ChatHotkeys.formatLocation(318, null, 3));
		assertEquals("Location: W318, Lumbridge", ChatHotkeys.formatLocation(318, "Lumbridge", null));
	}
}
