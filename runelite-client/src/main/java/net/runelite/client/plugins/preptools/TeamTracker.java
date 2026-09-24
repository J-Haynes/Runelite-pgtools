package net.runelite.client.plugins.preptools;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.Player;
import net.runelite.client.util.Text;

/**
 * "Your team" for the whole plugin: you, friends chat members, clan members, and every configured
 * caller (enabled or not). Computed once per game tick by refresh(), so the many per-tick and
 * per-menu-frame team checks are set lookups rather than repeated Text.standardize() calls.
 *
 * Names are compared Text.standardize()-d everywhere: FriendsChatMember and Player can spell the
 * same name with a regular space vs a non-breaking space. Client thread only.
 */
class TeamTracker
{
	private final Client client;

	/**
	 * Standardized names of every configured caller - see setConfiguredCallers().
	 */
	private volatile Set<String> callerNames = Collections.emptySet();
	private Set<String> friendsChatNames = Collections.emptySet();

	/**
	 * Every player seen by the last refresh(), with their standardized name.
	 */
	private Map<Player, String> standardNames = new HashMap<>();
	private Set<Player> ownTeam = new HashSet<>();
	private Set<Player> chatMembers = new HashSet<>();

	@Inject
	TeamTracker(Client client)
	{
		this.client = client;
	}

	void setConfiguredCallers(Set<String> standardizedNames)
	{
		callerNames = standardizedNames;
	}

	void refresh()
	{
		Set<String> fcNames = new HashSet<>();
		FriendsChatManager friendsChat = client.getFriendsChatManager();
		FriendsChatMember[] members = friendsChat != null ? friendsChat.getMembers() : null;
		if (members != null)
		{
			for (FriendsChatMember member : members)
			{
				if (member != null && member.getName() != null)
				{
					fcNames.add(Text.standardize(member.getName()));
				}
			}
		}
		friendsChatNames = fcNames;

		Map<Player, String> names = new HashMap<>();
		Set<Player> team = new HashSet<>();
		Set<Player> chat = new HashSet<>();
		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}

			String standard = Text.standardize(player.getName());
			names.put(player, standard);
			boolean chatMember = computeChatMember(player, standard);
			if (chatMember)
			{
				chat.add(player);
			}
			if (chatMember || callerNames.contains(standard))
			{
				team.add(player);
			}
		}
		standardNames = names;
		ownTeam = team;
		chatMembers = chat;
	}

	void clear()
	{
		friendsChatNames = Collections.emptySet();
		standardNames = new HashMap<>();
		ownTeam = new HashSet<>();
		chatMembers = new HashSet<>();
	}

	/**
	 * You, a friends chat/clan member, or a configured caller. A player who appeared since the last
	 * refresh() (the menu runs between ticks) is checked directly.
	 */
	boolean isOwnTeam(Player player)
	{
		if (player == null || player.getName() == null)
		{
			return false;
		}
		if (standardNames.containsKey(player))
		{
			return ownTeam.contains(player);
		}
		String standard = Text.standardize(player.getName());
		return computeChatMember(player, standard) || callerNames.contains(standard);
	}

	/**
	 * You, or a friends chat/clan member - own team minus callers who share no chat with you.
	 */
	boolean isChatMember(Player player)
	{
		if (player == null || player.getName() == null)
		{
			return false;
		}
		if (standardNames.containsKey(player))
		{
			return chatMembers.contains(player);
		}
		return computeChatMember(player, Text.standardize(player.getName()));
	}

	boolean isConfiguredCaller(Player player)
	{
		String standard = standardName(player);
		return standard != null && callerNames.contains(standard);
	}

	/**
	 * Text.standardize() of the player's name, cached for players seen by the last refresh().
	 */
	String standardName(Player player)
	{
		if (player == null || player.getName() == null)
		{
			return null;
		}
		String cached = standardNames.get(player);
		return cached != null ? cached : Text.standardize(player.getName());
	}

	/**
	 * Standardized names of the current friends chat's members (not you, unless you're listed).
	 */
	Set<String> getFriendsChatNames()
	{
		return friendsChatNames;
	}

	/**
	 * Player#isFriendsChatMember()/isClanMember() are the per-player flags the vanilla Team plugin
	 * counts with; the FC name set backs them up, as it was this plugin's original proven check.
	 */
	private boolean computeChatMember(Player player, String standardName)
	{
		return player == client.getLocalPlayer() || player.isClanMember() || player.isFriendsChatMember()
			|| friendsChatNames.contains(standardName);
	}
}
