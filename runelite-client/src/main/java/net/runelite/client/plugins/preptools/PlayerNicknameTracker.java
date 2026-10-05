package net.runelite.client.plugins.preptools;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.util.Text;

/**
 * Experimental: your own nicknames for players (the sidebar's Names tab), TeamSpeak-style.
 *
 * There is no account id for other players in the game client - a name is all there is. The one
 * thing that follows an account through a name change is your friends/ignore list, so a nicknamed
 * player on either list has their nickname moved to the new name: by the previous name the game
 * reports (PrepToolsPlugin's NameableNameChanged handler, as core's Friend Notes plugin does), and
 * by comparing the lists between logins for renames that report doesn't cover (FriendRenameTracker).
 *
 * Resolved once per tick for on-screen players; client thread, except setNicknames().
 */
class PlayerNicknameTracker
{
	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	/**
	 * Standardized name to nickname - see setNicknames().
	 */
	private volatile Map<String, String> nicknamesByStandardName = Collections.emptyMap();

	@Getter
	private Map<Player, String> nicknames = Collections.emptyMap();

	@Inject
	PlayerNicknameTracker(Client client, PrepToolsConfig config, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.team = team;
	}

	/**
	 * Called when the nickname config changes (and at startup), with display name to nickname.
	 */
	void setNicknames(Map<String, String> byName)
	{
		Map<String, String> standard = new HashMap<>();
		byName.forEach((name, nickname) -> standard.put(Text.standardize(name), nickname));
		nicknamesByStandardName = standard;
	}

	/**
	 * Any player's nickname by name, whether or not they're on screen - for the friends list, chat
	 * and the hover tooltip.
	 */
	String nicknameFor(String name)
	{
		return name == null ? null : nicknamesByStandardName.get(Text.standardize(name));
	}

	void onGameTick()
	{
		Map<String, String> all = nicknamesByStandardName;
		if (!config.showPlayerNicknames() || all.isEmpty())
		{
			nicknames = Collections.emptyMap();
			return;
		}

		Map<Player, String> found = new HashMap<>();
		for (Player player : client.getPlayers())
		{
			String name = team.standardName(player);
			if (name == null)
			{
				continue;
			}
			String nickname = all.get(name);
			if (nickname != null)
			{
				found.put(player, nickname);
			}
		}
		nicknames = found;
	}

	void clear()
	{
		nicknames = Collections.emptyMap();
	}
}
