package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The Scout (Discord) hotkey: posts a ScoutReport to the configured Discord webhook, under your
 * in-game name. Only ever sends to a Discord webhook URL, with mentions disabled so a cape name
 * can't ping the channel; the outcome is echoed to your chatbox as a console line.
 */
@Slf4j
class ScoutReporter
{
	/**
	 * Discord allows about 30 webhook messages a minute per channel; a held or double-pressed
	 * hotkey shouldn't spam it.
	 */
	private static final long COOLDOWN_MILLIS = 5000;
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final Client client;
	private final PrepToolsConfig config;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ChatMessageManager chatMessageManager;

	private long lastSentMillis;

	@Inject
	ScoutReporter(Client client, PrepToolsConfig config, OkHttpClient okHttpClient, Gson gson, ChatMessageManager chatMessageManager)
	{
		this.client = client;
		this.config = config;
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.chatMessageManager = chatMessageManager;
	}

	static boolean isDiscordWebhook(HttpUrl url)
	{
		String host = url.host();
		boolean discordHost = host.equals("discord.com") || host.endsWith(".discord.com")
			|| host.equals("discordapp.com") || host.endsWith(".discordapp.com");
		return url.isHttps() && discordHost && url.encodedPath().startsWith("/api/webhooks/");
	}

	/**
	 * Client thread only - reads the players around you.
	 */
	void post(Map<Integer, String> capeNames, String locationName, Integer wildernessLevel)
	{
		HttpUrl url = HttpUrl.parse(config.scoutWebhookUrl().trim());
		if (url == null || !isDiscordWebhook(url))
		{
			printConsole("Scout: set a Discord webhook URL in the PvP Tools settings (Scout section) first.");
			return;
		}

		long now = System.currentTimeMillis();
		long remainingMillis = lastSentMillis + COOLDOWN_MILLIS - now;
		if (remainingMillis > 0)
		{
			printConsole(String.format("Scout on cooldown - %.1fs left.", remainingMillis / 1000.0));
			return;
		}
		lastSentMillis = now;

		// Everyone wearing a cape, own team included - the same numbers as the Team Capes overlay.
		Map<Integer, Integer> capeCounts = new HashMap<>();
		for (Player player : client.getPlayers())
		{
			if (player != null && player.getTeam() > 0)
			{
				capeCounts.merge(player.getTeam(), 1, Integer::sum);
			}
		}
		String report = ScoutReport.format(capeCounts, capeNames, Math.max(1, config.scoutMinCapeCount()),
			client.getWorld(), locationName, wildernessLevel);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("content", report);
		Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			payload.put("username", Text.removeTags(local.getName()));
		}
		payload.put("allowed_mentions", Collections.singletonMap("parse", Collections.emptyList()));

		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("PvP Tools: scout webhook failed", e);
				printConsole("Scout failed to send: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					printConsole(response.isSuccessful()
						? "Scout sent: " + report.replace('\n', ' ')
						: "Scout failed to send: Discord replied HTTP " + response.code());
				}
			}
		});
	}

	void clear()
	{
		lastSentMillis = 0;
	}

	private void printConsole(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value("PvP Tools: " + message)
			.build());
	}
}
