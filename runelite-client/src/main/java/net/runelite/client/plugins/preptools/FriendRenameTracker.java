package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Nameable;
import net.runelite.api.NameableContainer;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Experimental: keeps nicknames (and Friend Notes notes) on friends/ignore list players who renamed
 * while you were logged out - including more than once, which the game's own "previous name"
 * can't follow (it only ever names the one before the current, see CLAUDE.md).
 *
 * Each list is saved to your RuneScape profile (per account) while you're logged in. At the next
 * login, once the list has finished loading, it's compared with the saved one (RenameMatch): sure
 * renames move the nickname and note straight away; anything unclear about a nicknamed player is
 * offered in the sidebar's Names tab to pair by hand. Client thread, except the pending list.
 */
class FriendRenameTracker
{
	/**
	 * Don't trust the list before this many ticks after login, nor until its size has held still
	 * for STABLE_TICKS - it arrives from the server after you're in.
	 */
	private static final int MIN_TICKS_AFTER_LOGIN = 10;
	private static final int STABLE_TICKS = 5;
	private static final int SAVE_INTERVAL_TICKS = 5;

	private static final String FRIENDS_KEY = "friendsListSnapshot";
	private static final String IGNORE_KEY = "ignoreListSnapshot";

	/**
	 * A nicknamed player who left a list with no sure match - for the Names tab.
	 */
	@AllArgsConstructor
	@EqualsAndHashCode
	static final class PendingRename
	{
		final String oldName;
		final String nickname;
		final List<String> candidates;
	}

	private final Client client;
	private final PrepToolsConfig config;
	private final ConfigManager configManager;
	private final ConfigListEditor lists;
	private final PlayerNicknameTracker nicknames;
	private final ChatMessageManager chatMessageManager;

	private int ticksSinceLogin;
	private boolean checked;
	private int lastFriendCount = -1;
	private int stableTicks;
	private final Map<String, List<String>> lastSaved = new HashMap<>();

	private volatile List<PendingRename> pending = Collections.emptyList();

	@Inject
	FriendRenameTracker(Client client, PrepToolsConfig config, ConfigManager configManager, ConfigListEditor lists,
		PlayerNicknameTracker nicknames, ChatMessageManager chatMessageManager)
	{
		this.client = client;
		this.config = config;
		this.configManager = configManager;
		this.lists = lists;
		this.nicknames = nicknames;
		this.chatMessageManager = chatMessageManager;
	}

	List<PendingRename> getPending()
	{
		return pending;
	}

	/**
	 * Login, hop or logout - the next list to arrive is a fresh one to compare.
	 */
	void onSessionChanged()
	{
		ticksSinceLogin = 0;
		checked = false;
		lastFriendCount = -1;
		stableTicks = 0;
		lastSaved.clear();
	}

	/**
	 * True if the pending list changed (the sidebar needs a refresh).
	 */
	boolean onGameTick()
	{
		if (!config.showPlayerNicknames() || !config.followFriendRenames() || configManager.getRSProfileKey() == null)
		{
			return false;
		}

		ticksSinceLogin++;
		NameableContainer<?> friends = client.getFriendContainer();
		NameableContainer<?> ignores = client.getIgnoreContainer();
		int count = friends != null ? friends.getCount() : 0;
		if (count != lastFriendCount)
		{
			lastFriendCount = count;
			stableTicks = 0;
		}
		else
		{
			stableTicks++;
		}

		if (!checked)
		{
			if (ticksSinceLogin < MIN_TICKS_AFTER_LOGIN || stableTicks < STABLE_TICKS)
			{
				return false;
			}
			checked = true;
			List<PendingRename> found = new ArrayList<>();
			found.addAll(compare(FRIENDS_KEY, friends));
			found.addAll(compare(IGNORE_KEY, ignores));
			if (found.isEmpty())
			{
				return false;
			}
			pending = Collections.unmodifiableList(found);
			printConsole(found.size() + " nicknamed player(s) left your friends/ignore list since you last logged in"
				+ " and may have renamed - pair them in the PvP Tools Names tab.");
			return true;
		}

		if (ticksSinceLogin % SAVE_INTERVAL_TICKS == 0)
		{
			save(FRIENDS_KEY, friends);
			save(IGNORE_KEY, ignores);
		}
		return false;
	}

	/**
	 * The sidebar paired a pending player with one of the new names.
	 */
	void resolve(String oldName, String newName)
	{
		move(oldName, newName);
		dismiss(oldName);
	}

	void dismiss(String oldName)
	{
		List<PendingRename> remaining = new ArrayList<>(pending);
		remaining.removeIf(p -> p.oldName.equals(oldName));
		pending = Collections.unmodifiableList(remaining);
	}

	private List<PendingRename> compare(String key, NameableContainer<?> container)
	{
		List<String> now = names(container);
		String saved = configManager.getRSProfileConfiguration(PrepToolsConfig.GROUP, key);
		List<PendingRename> found = new ArrayList<>();
		if (saved != null && !saved.isEmpty() && !now.isEmpty())
		{
			RenameMatch match = RenameMatch.match(Arrays.asList(saved.split("\n")), now, previousNames(container));
			match.renames.forEach(this::move);
			for (String left : match.unmatchedLeft)
			{
				String nickname = nicknames.nicknameFor(left);
				if (nickname != null && !match.unmatchedArrived.isEmpty())
				{
					found.add(new PendingRename(left, nickname, match.unmatchedArrived));
				}
			}
		}
		save(key, container);
		return found;
	}

	private void move(String oldName, String newName)
	{
		String nickname = nicknames.nicknameFor(oldName);
		boolean moved = lists.movePlayerNickname(oldName, newName);
		moved |= lists.moveFriendNote(oldName, newName);
		if (moved && nickname != null)
		{
			printConsole(oldName + " (" + nickname + ") is now called " + newName + " - nickname moved.");
		}
	}

	/**
	 * Never saves an empty list over a saved one - an empty list is far more likely not loaded yet
	 * than everyone removed.
	 */
	private void save(String key, NameableContainer<?> container)
	{
		List<String> now = names(container);
		if (now.isEmpty() || now.equals(lastSaved.get(key)))
		{
			return;
		}
		lastSaved.put(key, now);
		configManager.setRSProfileConfiguration(PrepToolsConfig.GROUP, key, String.join("\n", now));
	}

	private static List<String> names(NameableContainer<?> container)
	{
		List<String> names = new ArrayList<>();
		Nameable[] members = container != null ? container.getMembers() : null;
		if (members != null)
		{
			for (Nameable member : members)
			{
				if (member != null && member.getName() != null)
				{
					names.add(Text.toJagexName(member.getName()));
				}
			}
		}
		return names;
	}

	private static Map<String, String> previousNames(NameableContainer<?> container)
	{
		Map<String, String> previous = new HashMap<>();
		Nameable[] members = container != null ? container.getMembers() : null;
		if (members != null)
		{
			for (Nameable member : members)
			{
				if (member != null && member.getName() != null && member.getPrevName() != null)
				{
					previous.put(Text.toJagexName(member.getName()), Text.toJagexName(member.getPrevName()));
				}
			}
		}
		return previous;
	}

	private void printConsole(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value("PvP Tools: " + message)
			.build());
	}
}
