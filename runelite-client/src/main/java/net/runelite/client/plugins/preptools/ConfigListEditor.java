package net.runelite.client.plugins.preptools;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Edits the hidden list-valued config (callers, snipe list, spam messages, team cape names,
 * player nicknames) for the sidebar and the shift-right-click menu options. Every change is written straight to config;
 * PrepToolsPlugin#onConfigChanged then reloads the trackers and rebuilds the sidebar, so config
 * stays the single source of truth. Safe from any thread.
 */
class ConfigListEditor
{
	/**
	 * OSRS's chat input limit - a longer spam message would be cut off by the game.
	 */
	static final int SPAM_MESSAGE_MAX_LENGTH = 80;

	private final PrepToolsConfig config;
	private final ConfigManager configManager;
	private final ChatMessageManager chatMessageManager;
	private final HighlightTracker highlights;

	@Inject
	ConfigListEditor(PrepToolsConfig config, ConfigManager configManager, ChatMessageManager chatMessageManager, HighlightTracker highlights)
	{
		this.config = config;
		this.configManager = configManager;
		this.chatMessageManager = chatMessageManager;
		this.highlights = highlights;
	}

	// --- Callers ---

	/**
	 * Shift-right-click "Add caller".
	 */
	void addCaller(String playerName)
	{
		if (playerName != null && appendCallerIfNew(playerName, playerName))
		{
			printConsole("Added " + playerName + " as a caller.");
		}
	}

	boolean isCaller(String playerName)
	{
		return indexOfCaller(callers(), playerName) >= 0;
	}

	/**
	 * The sidebar's Add field: "Name" or "Name:Nickname".
	 */
	void addCallerFromPanel(String rawInput)
	{
		String trimmed = stripDisabledPrefix(rawInput == null ? "" : rawInput.trim());
		int colon = trimmed.indexOf(':');
		String name = colon >= 0 ? trimmed.substring(0, colon).trim() : trimmed;
		if (!name.isEmpty())
		{
			appendCallerIfNew(name, trimmed);
		}
	}

	/**
	 * @param direction -1 to move up (taking over sooner), +1 to move down
	 */
	void moveCaller(String realName, int direction)
	{
		List<CallerEntry> entries = callers();
		int index = indexOfCaller(entries, realName);
		int newIndex = index + direction;
		if (index >= 0 && newIndex >= 0 && newIndex < entries.size())
		{
			Collections.swap(entries, index, newIndex);
			saveCallers(entries);
		}
	}

	void removeCaller(String realName)
	{
		List<CallerEntry> entries = callers();
		String standard = Text.standardize(realName);
		if (entries.removeIf(entry -> Text.standardize(entry.name).equals(standard)))
		{
			saveCallers(entries);
		}
	}

	/**
	 * Renames a caller and/or changes their nickname (blank removes it), keeping their list
	 * position, enabled state and any primary pin. False (nothing saved) if the new name is empty,
	 * invalid, or already another caller's.
	 */
	boolean editCaller(String oldRealName, String newRawName, String newRawNickname)
	{
		String newName = stripDisabledPrefix(newRawName == null ? "" : newRawName.trim());
		if (newName.isEmpty() || newName.contains(":") || newName.contains(","))
		{
			return false;
		}
		String nickname = newRawNickname == null ? "" : newRawNickname.replace(",", " ").replace(":", " ").trim();

		List<CallerEntry> entries = callers();
		int index = indexOfCaller(entries, oldRealName);
		int clash = indexOfCaller(entries, newName);
		if (index < 0 || (clash >= 0 && clash != index))
		{
			return false;
		}

		entries.set(index, new CallerEntry(newName, nickname.isEmpty() ? null : nickname, entries.get(index).enabled));
		highlights.renamePinnedCaller(oldRealName, newName);
		saveCallers(entries);
		return true;
	}

	/**
	 * A disabled caller stays in the list and on your team, but is never primary/backup.
	 */
	void setCallerEnabled(String realName, boolean enabled)
	{
		List<CallerEntry> entries = callers();
		int index = indexOfCaller(entries, realName);
		if (index >= 0 && entries.get(index).enabled != enabled)
		{
			CallerEntry entry = entries.get(index);
			entries.set(index, new CallerEntry(entry.name, entry.nickname, enabled));
			saveCallers(entries);
		}
	}

	void setPinnedCaller(String realName)
	{
		highlights.setPinnedCaller(realName);
	}

	// --- Snipe list ---

	/**
	 * Shift-right-click "Add to snipe list".
	 */
	void addToSnipeList(String playerName)
	{
		if (playerName != null && appendSnipeIfNew(playerName))
		{
			printConsole("Added " + playerName + " to the snipe list.");
		}
	}

	boolean isSniped(String playerName)
	{
		String standard = Text.standardize(playerName);
		return ConfigLists.parseNameList(config.snipeList()).stream().anyMatch(n -> Text.standardize(n).equals(standard));
	}

	void addSnipeFromPanel(String rawInput)
	{
		if (rawInput != null && !rawInput.trim().isEmpty())
		{
			appendSnipeIfNew(rawInput.trim());
		}
	}

	void removeSnipedName(String name)
	{
		List<String> names = ConfigLists.parseNameList(config.snipeList());
		String standard = Text.standardize(name);
		if (names.removeIf(existing -> Text.standardize(existing).equals(standard)))
		{
			configManager.setConfiguration(PrepToolsConfig.GROUP, "snipeList", String.join("\n", names));
		}
	}

	// --- Spam messages ---

	/**
	 * The first message ever added also becomes the selected one, so the hotkey works straight away.
	 */
	void addSpamMessageFromPanel(String rawInput)
	{
		String message = rawInput == null ? "" : rawInput.replace('\n', ' ').trim();
		List<String> messages = ConfigLists.parseSpamMessages(config.spamMessages());
		if (message.isEmpty() || message.length() > SPAM_MESSAGE_MAX_LENGTH || messages.contains(message))
		{
			return;
		}

		messages.add(message);
		configManager.setConfiguration(PrepToolsConfig.GROUP, "spamMessages", String.join("\n", messages));
		if (messages.size() == 1)
		{
			setSelectedSpamMessage(message);
		}
	}

	void removeSpamMessage(String message)
	{
		List<String> messages = ConfigLists.parseSpamMessages(config.spamMessages());
		if (!messages.remove(message))
		{
			return;
		}

		configManager.setConfiguration(PrepToolsConfig.GROUP, "spamMessages", String.join("\n", messages));
		if (message.equals(config.selectedSpamMessage()))
		{
			setSelectedSpamMessage(messages.isEmpty() ? "" : messages.get(0));
		}
	}

	void setSelectedSpamMessage(String message)
	{
		configManager.setConfiguration(PrepToolsConfig.GROUP, "selectedSpamMessage", message == null ? "" : message);
	}

	void setSpamRandom(boolean random)
	{
		configManager.setConfiguration(PrepToolsConfig.GROUP, "spamRandom", random);
	}

	// --- Team cape names ---

	/**
	 * Adds or replaces one team number's name; a blank name removes it.
	 */
	void setTeamCapeName(int teamNumber, String rawName)
	{
		String name = rawName == null ? "" : rawName.trim();
		if (name.length() > ConfigLists.TEAM_CAPE_NAME_MAX_LENGTH)
		{
			name = name.substring(0, ConfigLists.TEAM_CAPE_NAME_MAX_LENGTH);
		}

		Map<Integer, String> names = ConfigLists.parseTeamCapeNames(config.teamCapeNames());
		if (name.isEmpty())
		{
			names.remove(teamNumber);
		}
		else
		{
			names.put(teamNumber, name);
		}
		configManager.setConfiguration(PrepToolsConfig.GROUP, "teamCapeNames", ConfigLists.serializeTeamCapeNames(names));
	}

	void removeTeamCapeName(int teamNumber)
	{
		Map<Integer, String> names = ConfigLists.parseTeamCapeNames(config.teamCapeNames());
		if (names.remove(teamNumber) != null)
		{
			configManager.setConfiguration(PrepToolsConfig.GROUP, "teamCapeNames", ConfigLists.serializeTeamCapeNames(names));
		}
	}

	// --- Helpers ---

	private List<CallerEntry> callers()
	{
		return CallerEntry.parseAll(config.callers());
	}

	private void saveCallers(List<CallerEntry> entries)
	{
		configManager.setConfiguration(PrepToolsConfig.GROUP, "callers", CallerEntry.serializeAll(entries));
	}

	private static int indexOfCaller(List<CallerEntry> entries, String name)
	{
		String standard = Text.standardize(name);
		for (int i = 0; i < entries.size(); i++)
		{
			if (Text.standardize(entries.get(i).name).equals(standard))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * A leading '!' is the stored "disabled" marker - never let a typed one into a name.
	 */
	private static String stripDisabledPrefix(String name)
	{
		while (name.startsWith(CallerEntry.DISABLED_PREFIX))
		{
			name = name.substring(CallerEntry.DISABLED_PREFIX.length()).trim();
		}
		return name;
	}

	/**
	 * @param rawLine "Name" or "Name:Nickname", appended as-is
	 */
	private boolean appendCallerIfNew(String name, String rawLine)
	{
		if (isCaller(name))
		{
			return false;
		}
		String existing = config.callers();
		configManager.setConfiguration(PrepToolsConfig.GROUP, "callers",
			existing == null || existing.trim().isEmpty() ? rawLine : existing.trim() + "\n" + rawLine);
		return true;
	}

	private boolean appendSnipeIfNew(String name)
	{
		if (isSniped(name))
		{
			return false;
		}
		String existing = config.snipeList();
		configManager.setConfiguration(PrepToolsConfig.GROUP, "snipeList",
			existing == null || existing.trim().isEmpty() ? name : existing.trim() + "\n" + name);
		return true;
	}

	// --- Player nicknames ---

	String getPlayerNickname(String playerName)
	{
		return nicknames().entrySet().stream()
			.filter(e -> Text.standardize(e.getKey()).equals(Text.standardize(playerName)))
			.map(Map.Entry::getValue)
			.findFirst().orElse(null);
	}

	/**
	 * Sets (or with an empty nickname, removes) a player's nickname. Nicknames can't span lines.
	 */
	void setPlayerNickname(String playerName, String rawNickname)
	{
		String name = playerName == null ? "" : Text.removeTags(playerName).replace('\u00A0', ' ').trim();
		if (name.isEmpty() || name.contains("="))
		{
			return;
		}
		String nickname = rawNickname == null ? "" : rawNickname.replaceAll("[\\r\\n]", " ").trim();

		Map<String, String> all = nicknames();
		all.keySet().removeIf(n -> Text.standardize(n).equals(Text.standardize(name)));
		if (!nickname.isEmpty())
		{
			all.put(name, nickname);
		}
		configManager.setConfiguration(PrepToolsConfig.GROUP, "playerNicknames", ConfigLists.serializePlayerNicknames(all));
	}

	/**
	 * A nicknamed friend/ignore-list player changed their name: the nickname moves with them.
	 */
	void renamePlayerNickname(String oldName, String newName)
	{
		String nickname = getPlayerNickname(oldName);
		if (nickname == null || newName == null)
		{
			return;
		}
		setPlayerNickname(oldName, null);
		setPlayerNickname(newName, nickname);
		printConsole(oldName + " (" + nickname + ") is now called " + newName + " - nickname moved.");
	}

	private Map<String, String> nicknames()
	{
		return ConfigLists.parsePlayerNicknames(config.playerNicknames());
	}

	private void printConsole(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value(message)
			.build());
	}
}
