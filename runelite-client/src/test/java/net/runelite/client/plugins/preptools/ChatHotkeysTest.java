package net.runelite.client.plugins.preptools;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.vars.InputType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.input.KeyManager;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The chat hotkeys pre-fill the chatbox (with a channel prefix) and never send - the game client
 * rejects chat sent by a plugin.
 */
public class ChatHotkeysTest
{
	private Client client;
	private PrepToolsConfig config;
	private LocationTracker locations;
	private ChatHotkeys hotkeys;

	@Before
	public void before()
	{
		client = mock(Client.class);
		config = mock(PrepToolsConfig.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
		locations = mock(LocationTracker.class);
		hotkeys = new ChatHotkeys(client, mock(ClientThread.class), mock(KeyManager.class), config,
			mock(ChatMessageManager.class), locations, mock(ScoutReporter.class));
		when(client.getWorld()).thenReturn(318);
		// Mockito would answer 0, not null, for an Integer.
		when(locations.getWildernessLevel()).thenReturn(null);
	}

	private void verifyPrefilled(String text)
	{
		verify(client).setVarcStrValue(VarClientID.CHATINPUT, text);
		verify(client).runScript(ScriptID.CHAT_PROMPT_INIT);
		verify(client, never()).runScript(eq(ScriptID.CHAT_SEND), (Object[]) any());
	}

	@Test
	public void locationPrefillsFriendsChat()
	{
		when(locations.getLocationName()).thenReturn("Ferox Enclave");
		when(locations.getWildernessLevel()).thenReturn(15);

		hotkeys.sendLocation();

		verifyPrefilled("/f Location: W318, Ferox Enclave, lvl 15 wild");
	}

	@Test
	public void locationPrefillsClanChatWhenChosen()
	{
		when(config.locationSpamChannel()).thenReturn(ChatSpamChannel.CLAN_CHAT);
		when(locations.getLocationName()).thenReturn("Lumbridge");

		hotkeys.sendLocation();

		verifyPrefilled("/c Location: W318, Lumbridge");
	}

	@Test
	public void spammerPrefillsPublicChat()
	{
		when(config.spamMessages()).thenReturn("gf\nsit");
		when(config.selectedSpamMessage()).thenReturn("sit");

		hotkeys.sendSpammerMessage();

		verifyPrefilled("/p sit");
	}

	@Test
	public void pileSpamPrefillsPublicChat()
	{
		Player local = mock(Player.class);
		Player target = mock(Player.class);
		when(target.getName()).thenReturn("Matt M");
		when(local.getInteracting()).thenReturn(target);
		when(client.getLocalPlayer()).thenReturn(local);
		when(config.pileSpamPrefix()).thenReturn("EoP");

		hotkeys.sendPileSpam();

		verifyPrefilled("/p EoP Matt M");
	}

	@Test
	public void nothingTypedWhileAnotherPromptIsOpen()
	{
		when(client.getVarcIntValue(VarClientID.MESLAYERMODE)).thenReturn(InputType.PRIVATE_MESSAGE.getType());
		when(config.spamMessages()).thenReturn("gf");

		hotkeys.sendSpammerMessage();

		verify(client, never()).setVarcStrValue(anyInt(), anyString());
		// Didn't start the cooldown: the next press, once the prompt's closed, still works.
		when(client.getVarcIntValue(VarClientID.MESLAYERMODE)).thenReturn(InputType.NONE.getType());
		hotkeys.sendSpammerMessage();
		verifyPrefilled("/p gf");
	}

	@Test
	public void nothingTypedWithoutALocation()
	{
		hotkeys.sendLocation();

		verify(client, never()).setVarcStrValue(anyInt(), anyString());
	}
}
