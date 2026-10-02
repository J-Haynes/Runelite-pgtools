package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsers/serializers for the plugin's hidden list-valued config text (snipe list, spam messages,
 * team cape names). The caller list has its own format, see CallerEntry.
 */
final class ConfigLists
{
	/**
	 * Hard cap on a team cape's custom name. Also enforced while typing, by the Capes tab's field.
	 */
	static final int TEAM_CAPE_NAME_MAX_LENGTH = 5;

	private ConfigLists()
	{
	}

	/**
	 * Comma or newline separated names (the snipe list), trimmed, blanks dropped.
	 */
	static List<String> parseNameList(String raw)
	{
		List<String> names = new ArrayList<>();
		if (raw == null)
		{
			return names;
		}

		for (String part : raw.split("[,\\n]"))
		{
			String trimmed = part.trim();
			if (!trimmed.isEmpty())
			{
				names.add(trimmed);
			}
		}
		return names;
	}

	/**
	 * Newline separated ONLY - a spam message can contain commas. Blank lines and exact duplicates
	 * dropped.
	 */
	static List<String> parseSpamMessages(String raw)
	{
		List<String> messages = new ArrayList<>();
		if (raw == null)
		{
			return messages;
		}

		for (String line : raw.split("\n"))
		{
			String trimmed = line.trim();
			if (!trimmed.isEmpty() && !messages.contains(trimmed))
			{
				messages.add(trimmed);
			}
		}
		return messages;
	}

	/**
	 * "TeamNumber:Name" per line (or comma) into team number to name, in file order. Malformed
	 * entries are skipped rather than failing the whole list; names are cut to
	 * TEAM_CAPE_NAME_MAX_LENGTH, since the raw text can be edited outside the sidebar.
	 */
	static Map<Integer, String> parseTeamCapeNames(String raw)
	{
		Map<Integer, String> names = new LinkedHashMap<>();
		if (raw == null)
		{
			return names;
		}

		for (String part : raw.split("[,\\n]"))
		{
			int colonIndex = part.indexOf(':');
			if (colonIndex < 0)
			{
				continue;
			}

			String name = part.substring(colonIndex + 1).trim();
			if (name.isEmpty())
			{
				continue;
			}
			if (name.length() > TEAM_CAPE_NAME_MAX_LENGTH)
			{
				name = name.substring(0, TEAM_CAPE_NAME_MAX_LENGTH);
			}

			try
			{
				names.put(Integer.parseInt(part.substring(0, colonIndex).trim()), name);
			}
			catch (NumberFormatException e)
			{
				// skip the typo, keep the rest
			}
		}
		return names;
	}

	static String serializeTeamCapeNames(Map<Integer, String> names)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, String> entry : names.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(entry.getKey()).append(':').append(entry.getValue());
		}
		return sb.toString();
	}

	/**
	 * Player nicknames, "Name=Nickname" per line, in file order. OSRS names can't contain '=', a
	 * nickname can. Lines without both halves are skipped; a name listed twice keeps the last.
	 */
	static Map<String, String> parsePlayerNicknames(String raw)
	{
		Map<String, String> nicknames = new LinkedHashMap<>();
		if (raw == null)
		{
			return nicknames;
		}

		for (String line : raw.split("\n"))
		{
			int equals = line.indexOf('=');
			if (equals < 0)
			{
				continue;
			}
			String name = line.substring(0, equals).trim();
			String nickname = line.substring(equals + 1).trim();
			if (!name.isEmpty() && !nickname.isEmpty())
			{
				nicknames.put(name, nickname);
			}
		}
		return nicknames;
	}

	static String serializePlayerNicknames(Map<String, String> nicknames)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : nicknames.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(entry.getKey()).append('=').append(entry.getValue());
		}
		return sb.toString();
	}
}
