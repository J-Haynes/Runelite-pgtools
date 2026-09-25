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
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.vars.InputType;
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
 *
 * The chat hotkeys only pre-fill the chatbox - you press Enter to send. The game client blocks a
 * plugin sending chat itself ("Packet sent from script without permission", since the 2026-09-24
 * injected-client).
 */
class ChatHotkeys
{
	/**
	 * Chat input prefixes the game's own chat script (ChatDefaultOnKey) turns into a channel and
	 * strips before sending - so the message goes to that channel whatever the chatbox is set to.
	 */
	private static final String PREFIX_PUBLIC = "/p ";
	private static final String PREFIX_CLAN_CHAT = "/c ";
	private static final String PREFIX_FRIENDS_CHAT = "/f ";

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

	void sendPileSpam()
	{
		Player local = client.getLocalPlayer();
		Actor interacting = local != null ? local.getInteracting() : null;
		if (!(interacting instanceof Player) || interacting.getName() == null)
		{
			return;
		}

		String prefix = config.pileSpamPrefix();
		String name = interacting.getName();
		prefill(prefix == null || prefix.trim().isEmpty() ? name : prefix.trim() + " " + name, PREFIX_PUBLIC);
	}

	/**
	 * The selected message, or with Random on a random one - never the same twice in a row.
	 */
	void sendSpammerMessage()
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
			printConsole(String.format("Spammer on cooldown - %.1fs left.", remainingMillis / 1000.0));
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

		if (prefill(message, PREFIX_PUBLIC))
		{
			lastSpamMessage = message;
			lastSpamSentMillis = now;
		}
	}

	void sendLocation()
	{
		String name = locations.getLocationName();
		Integer level = locations.getWildernessLevel();
		if (name == null && level == null)
		{
			return;
		}
		prefill(formatLocation(client.getWorld(), name, level),
			config.locationSpamChannel() == ChatSpamChannel.CLAN_CHAT ? PREFIX_CLAN_CHAT : PREFIX_FRIENDS_CHAT);
	}

	/**
	 * Puts the message in the chatbox, replacing whatever was typed, for you to send with Enter -
	 * the same as core's ChatKeyboardListener. Not while another chat prompt is open (a private
	 * message, "Enter amount", a search): the text would go to the hidden chat line instead.
	 *
	 * @return false if a prompt was open and nothing was typed
	 */
	private boolean prefill(String message, String channelPrefix)
	{
		if (client.getVarcIntValue(VarClientID.MESLAYERMODE) != InputType.NONE.getType())
		{
			printConsole("Close the open chat prompt first.");
			return false;
		}
		client.setVarcStrValue(VarClientID.CHATINPUT, channelPrefix + message);
		client.runScript(ScriptID.CHAT_PROMPT_INIT);
		return true;
	}

	/**
	 * A local console line only, so a blocked press isn't mistaken for a broken hotkey.
	 */
	private void printConsole(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value(message)
			.build());
	}
}
