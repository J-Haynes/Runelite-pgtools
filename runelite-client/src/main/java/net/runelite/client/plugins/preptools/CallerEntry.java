package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One line of the hidden "callers" config text, and the parser/serializer for that text.
 *
 * Format, one entry per line (commas also accepted):
 * <pre>
 *   Name              plain caller
 *   Name:Nickname     shown as "Nickname"
 *   !Name[:Nickname]  disabled - stays in the list and on your team, never primary/backup
 * </pre>
 * OSRS names can't contain '!', ':' or ',', so those are safe as markers.
 */
final class CallerEntry
{
	static final String DISABLED_PREFIX = "!";

	final String name;
	final String nickname;
	final boolean enabled;

	CallerEntry(String name, String nickname, boolean enabled)
	{
		this.name = name;
		this.nickname = nickname;
		this.enabled = enabled;
	}

	static List<CallerEntry> parseAll(String raw)
	{
		List<CallerEntry> entries = new ArrayList<>();
		if (raw == null)
		{
			return entries;
		}

		for (String part : raw.split("[,\\n]"))
		{
			String trimmed = part.trim();
			boolean enabled = true;
			if (trimmed.startsWith(DISABLED_PREFIX))
			{
				enabled = false;
				trimmed = trimmed.substring(DISABLED_PREFIX.length()).trim();
			}

			int colonIndex = trimmed.indexOf(':');
			String name = colonIndex >= 0 ? trimmed.substring(0, colonIndex).trim() : trimmed;
			String nickname = colonIndex >= 0 ? trimmed.substring(colonIndex + 1).trim() : "";
			if (!name.isEmpty())
			{
				entries.add(new CallerEntry(name, nickname.isEmpty() ? null : nickname, enabled));
			}
		}
		return entries;
	}

	static String serializeAll(List<CallerEntry> entries)
	{
		StringBuilder sb = new StringBuilder();
		for (CallerEntry entry : entries)
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			if (!entry.enabled)
			{
				sb.append(DISABLED_PREFIX);
			}
			sb.append(entry.name);
			if (entry.nickname != null)
			{
				sb.append(':').append(entry.nickname);
			}
		}
		return sb.toString();
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof CallerEntry))
		{
			return false;
		}
		CallerEntry other = (CallerEntry) o;
		return enabled == other.enabled && name.equals(other.name) && Objects.equals(nickname, other.nickname);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(name, nickname, enabled);
	}

	@Override
	public String toString()
	{
		return serializeAll(List.of(this));
	}
}
