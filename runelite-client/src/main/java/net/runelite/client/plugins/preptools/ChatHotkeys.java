package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;

/**
 * The hotkeys: Pile Spam (followed player's name to public chat), Spammer (saved message to public
 * chat, 5s cooldown), Location Spam (location to FC or clan chat) and Scout (Discord webhook).
 * Hotkeys fire on the AWT thread; everything they do runs on the client thread.
 */
class ChatHotkeys
{
	/**
	 * CHAT_SEND's "mode" argument, all CONFIRMED in game (guessed at first - neither RuneLite's
	 * source nor the web had them).
	 */
	private static final int CHAT_MODE_PUBLIC = 0;
	private static final int CHAT_MODE_CLAN_CHAT = 1;
	private static final int CHAT_MODE_FRIENDS_CHAT = 2;

	/**
	 * Spammer only (anti-abuse, asked for) - Pile/Location Spam carry fight-critical information
	 * and are never rate-limited.
	 */
	private static final long SPAM_COOLDOWN_MILLIS = 5000;

	private final Client client;
	private final ClientThread clientThread;
	private final KeyManager keyManager;
	private final PrepToolsConfig config;
	private final ChatMessageManager chatMessageManager;
	private final LocationTracker locations;
	private final ScoutReporter scout;

	private String lastSpamMessage;
	private long lastSpamSentMillis;

	private final HotkeyListener pileSpamListener;
	private final HotkeyListener spammerListener;
	private final HotkeyListener locationSpamListener;
	private final HotkeyListener scoutListener;

	@Inject
	ChatHotkeys(Client client, ClientThread clientThread, KeyManager keyManager, PrepToolsConfig config,
		ChatMessageManager chatMessageManager, LocationTracker locations, ScoutReporter scout)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.keyManager = keyManager;
		this.config = config;
		this.chatMessageManager = chatMessageManager;
		this.locations = locations;
		this.scout = scout;

		pileSpamListener = listener(config::pileSpamHotkey, this::sendPileSpam);
		spammerListener = listener(config::spamHotkey, this::sendSpammerMessage);
		locationSpamListener = listener(config::locationSpamHotkey, this::sendLocation);
		scoutListener = listener(config::scoutHotkey, () -> scout.post(
			ConfigLists.parseTeamCapeNames(config.teamCapeNames()), locations.getLocationName(), locations.getWildernessLevel()));
	}

	void start()
	{
		keyManager.registerKeyListener(pileSpamListener);
		keyManager.registerKeyListener(spammerListener);
		keyManager.registerKeyListener(locationSpamListener);
		keyManager.registerKeyListener(scoutListener);
	}

	void stop()
	{
		keyManager.unregisterKeyListener(pileSpamListener);
		keyManager.unregisterKeyListener(spammerListener);
		keyManager.unregisterKeyListener(locationSpamListener);
		keyManager.unregisterKeyListener(scoutListener);
		lastSpamMessage = null;
		lastSpamSentMillis = 0;
		scout.clear();
	}

	/**
	 * "Location: W318, Ferox Enclave, lvl 15 wild" - fixed format, no prefix; the level part is left
	 * out outside the wilderness.
	 */
	static String formatLocation(int world, String locationName, Integer wildernessLevel)
	{
		StringBuilder sb = new StringBuilder("Location: W").append(world)
			.append(", ").append(locationName != null ? locationName : "Wilderness");
		if (wildernessLevel != null)
		{
			sb.append(", lvl ").append(wildernessLevel).append(" wild");
		}
		return sb.toString();
	}

	private HotkeyListener listener(Supplier<Keybind> key, Runnable onPress)
	{
		return new HotkeyListener(key)
		{
			@Override
			public void hotkeyPressed()
			{
				clientThread.invoke(onPress);
			}
		};
	}

	private void sendPileSpam()
	{
		Player local = client.getLocalPlayer();
		Actor interacting = local != null ? local.getInteracting() : null;
		if (!(interacting instanceof Player) || interacting.getName() == null)
		{
			return;
		}

		String prefix = config.pileSpamPrefix();
		String name = interacting.getName();
		send(prefix == null || prefix.trim().isEmpty() ? name : prefix.trim() + " " + name, CHAT_MODE_PUBLIC);
	}

	/**
	 * The selected message, or with Random on a random one - never the same twice in a row.
	 */
	private void sendSpammerMessage()
	{
		List<String> messages = ConfigLists.parseSpamMessages(config.spamMessages());
		if (messages.isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		long remainingMillis = lastSpamSentMillis + SPAM_COOLDOWN_MILLIS - now;
		if (remainingMillis > 0)
		{
			// Local console line only, so a blocked press isn't mistaken for a broken hotkey.
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.value(String.format("Spammer on cooldown - %.1fs left.", remainingMillis / 1000.0))
				.build());
			return;
		}

		String message;
		if (config.spamRandom())
		{
			List<String> candidates = new ArrayList<>(messages);
			if (candidates.size() > 1)
			{
				candidates.remove(lastSpamMessage);
			}
			message = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		}
		else
		{
			String selected = config.selectedSpamMessage();
			message = messages.contains(selected) ? selected : messages.get(0);
		}

		lastSpamMessage = message;
		lastSpamSentMillis = now;
		send(message, CHAT_MODE_PUBLIC);
	}

	private void sendLocation()
	{
		String name = locations.getLocationName();
		Integer level = locations.getWildernessLevel();
		if (name == null && level == null)
		{
			return;
		}
		send(formatLocation(client.getWorld(), name, level),
			config.locationSpamChannel() == ChatSpamChannel.CLAN_CHAT ? CHAT_MODE_CLAN_CHAT : CHAT_MODE_FRIENDS_CHAT);
	}

	/**
	 * Sends a chat message exactly as if typed and Enter pressed - the same script RuneLite's
	 * ChatInputManager uses.
	 */
	private void send(String message, int mode)
	{
		client.runScript(ScriptID.CHAT_SEND, message, mode, 0, 0, -1);
	}
}
