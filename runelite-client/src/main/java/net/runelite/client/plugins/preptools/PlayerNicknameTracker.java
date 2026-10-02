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
 * Experimental: your own nicknames for players (the sidebar's Names tab), TeamSpeak-style, and
 * warnings for names made to look like a nicknamed player's (NameLookalike).
 *
 * There is no account id for other players in the game client - a name is all there is. The one
 * thing that follows an account through a name change is your friends/ignore list, so a nicknamed
 * player on either list has their nickname moved to the new name (PrepToolsPlugin's
 * NameableNameChanged handler, as core's Friend Notes plugin does for notes).
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

	/**
	 * Standardized name to the nickname of the player they look like (or "" for nobody) - names
	 * don't change while on screen, so each is only checked once per nickname list.
	 */
	private final Map<String, String> lookalikeCache = new HashMap<>();
	private Map<String, String> lookalikeCacheFor = Collections.emptyMap();

	@Getter
	private Map<Player, String> nicknames = Collections.emptyMap();

	/**
	 * On-screen players whose name looks like a nicknamed player's, to that player's nickname.
	 */
	@Getter
	private Map<Player, String> lookalikes = Collections.emptyMap();

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

	void onGameTick()
	{
		Map<String, String> all = nicknamesByStandardName;
		if (!config.showPlayerNicknames() || all.isEmpty())
		{
			nicknames = Collections.emptyMap();
			lookalikes = Collections.emptyMap();
			return;
		}
		if (all != lookalikeCacheFor)
		{
			lookalikeCache.clear();
			lookalikeCacheFor = all;
		}

		boolean warn = config.warnLookalikeNames();
		Map<Player, String> found = new HashMap<>();
		Map<Player, String> fakes = new HashMap<>();
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
			else if (warn)
			{
				String resembled = lookalikeCache.computeIfAbsent(name, n -> findLookalike(n, all));
				if (!resembled.isEmpty())
				{
					fakes.put(player, resembled);
				}
			}
		}
		nicknames = found;
		lookalikes = fakes;
	}

	void clear()
	{
		nicknames = Collections.emptyMap();
		lookalikes = Collections.emptyMap();
	}

	/**
	 * The nickname of the first nicknamed player this name looks like, or "".
	 */
	private static String findLookalike(String name, Map<String, String> nicknames)
	{
		for (Map.Entry<String, String> entry : nicknames.entrySet())
		{
			if (NameLookalike.isLookalike(name, entry.getKey()))
			{
				return entry.getValue();
			}
		}
		return "";
	}
}
