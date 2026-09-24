package net.runelite.client.plugins.preptools;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ConfigListsTest
{
	@Test
	public void callerEntriesParseNicknamesAndDisabled()
	{
		List<CallerEntry> entries = CallerEntry.parseAll("Bob\nXx Al xX:Al, !Carl:C\n\n  ,Dan:  ");

		assertEquals(4, entries.size());
		assertEquals(new CallerEntry("Bob", null, true), entries.get(0));
		assertEquals(new CallerEntry("Xx Al xX", "Al", true), entries.get(1));
		assertEquals(new CallerEntry("Carl", "C", false), entries.get(2));
		// A blank nickname is no nickname.
		assertNull(entries.get(3).nickname);
	}

	@Test
	public void callerEntriesRoundTrip()
	{
		String raw = "Bob\n!Carl:C\nDan:D";
		assertEquals(raw, CallerEntry.serializeAll(CallerEntry.parseAll(raw)));
	}

	@Test
	public void callerEntriesIgnoreNullAndBlank()
	{
		assertTrue(CallerEntry.parseAll(null).isEmpty());
		assertTrue(CallerEntry.parseAll(" \n , ").isEmpty());
		assertTrue(CallerEntry.parseAll("!").isEmpty());
	}

	@Test
	public void nameListSplitsOnCommasAndNewlines()
	{
		assertEquals(Arrays.asList("a", "b c", "d"), ConfigLists.parseNameList(" a,b c\n\nd, "));
		assertTrue(ConfigLists.parseNameList(null).isEmpty());
	}

	@Test
	public void spamMessagesKeepCommasAndDropDuplicates()
	{
		assertEquals(Arrays.asList("hi, all", "bye"), ConfigLists.parseSpamMessages("hi, all\n\nbye\nhi, all"));
	}

	@Test
	public void teamCapeNamesSkipMalformedAndTruncate()
	{
		Map<Integer, String> names = ConfigLists.parseTeamCapeNames("50:EoP\nx:Bad\n38:Apexxxxx\nnocolon\n12:  ");

		assertEquals(2, names.size());
		assertEquals("EoP", names.get(50));
		assertEquals("Apexx", names.get(38));
		assertFalse(names.containsKey(12));
	}

	@Test
	public void teamCapeNamesRoundTrip()
	{
		String raw = "50:EoP\n38:Apex";
		assertEquals(raw, ConfigLists.serializeTeamCapeNames(ConfigLists.parseTeamCapeNames(raw)));
	}
}
