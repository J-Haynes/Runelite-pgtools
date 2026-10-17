package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The Scout (Discord) hotkey: posts a ScoutReport to the configured Discord webhook, under the
 * "Post as" name (your in-game name if that's blank), with a map attached (scoutMap - ScoutMap's
 * drawn map by default, or your own minimap) carrying the cape counter (scoutCapes). The minimap
 * is cut from the next drawn frame, zoomed out for that one frame; if no frame comes (client
 * minimised), the report goes without it. Only ever sends
 * to a Discord webhook URL, with mentions disabled so a cape name can't ping the channel; the
 * outcome is echoed to your chatbox as a console line.
 */
@Slf4j
class ScoutReporter
{
	/**
	 * Discord allows about 30 webhook messages a minute per channel; a held or double-pressed
	 * hotkey shouldn't spam it.
	 */
	private static final long COOLDOWN_MILLIS = 5000;
	private static final int MAX_USERNAME_LENGTH = 80;
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.get("image/png");

	/**
	 * The minimap's drawing area in each layout: fixed, resizable classic, resizable modern
	 * (bottom line), and the newer one.
	 */
	private static final int[] MINIMAP_WIDGETS = {InterfaceID.Toplevel.MINIMAP,
		InterfaceID.ToplevelOsrsStretch.MINIMAP, InterfaceID.ToplevelPreEoc.MINIMAP, InterfaceID.ToplevelOsm.MINIMAP};

	/**
	 * Minimap pixels a tile for the captured frame - the game's default is 4, so this is twice as
	 * far out. Your own zoom comes back straight after.
	 */
	private static final double MINIMAP_CAPTURE_ZOOM = 2;

	/**
	 * How long to wait for a frame to cut the minimap/capes from before sending without them.
	 */
	private static final long FRAME_TIMEOUT_MILLIS = 1500;

	private static final int MAX_CAPE_ROWS = 10;

	/**
	 * Downloaded map squares, kept - the map art only changes with a game update.
	 */
	private static final File TILE_CACHE = new File(RuneLite.RUNELITE_DIR, "pvptools-maptiles");

	private final Client client;
	private final ClientThread clientThread;
	private final PrepToolsConfig config;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ChatMessageManager chatMessageManager;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;
	private final TeamTracker team;
	private final LocationTracker locations;
	private final ItemManager itemManager;

	private long lastSentMillis;

	@Inject
	ScoutReporter(Client client, ClientThread clientThread, PrepToolsConfig config, OkHttpClient okHttpClient, Gson gson,
		ChatMessageManager chatMessageManager, DrawManager drawManager, ScheduledExecutorService executor,
		TeamTracker team, LocationTracker locations, ItemManager itemManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.chatMessageManager = chatMessageManager;
		this.drawManager = drawManager;
		this.executor = executor;
		this.team = team;
		this.locations = locations;
		this.itemManager = itemManager;
	}

	static boolean isDiscordWebhook(HttpUrl url)
	{
		String host = url.host();
		boolean discordHost = host.equals("discord.com") || host.endsWith(".discord.com")
			|| host.equals("discordapp.com") || host.endsWith(".discordapp.com");
		return url.isHttps() && discordHost && url.encodedPath().startsWith("/api/webhooks/");
	}

	/**
	 * The name to post as: the configured one, or your in-game name if it's blank. Null if
	 * neither - Discord then uses the webhook's own name. Discord allows at most 80 characters.
	 */
	static String posterName(String configured, String inGameName)
	{
		String name = configured != null ? configured.trim() : "";
		if (name.isEmpty() && inGameName != null)
		{
			name = Text.removeTags(inGameName).trim();
		}
		if (name.isEmpty())
		{
			return null;
		}
		return name.length() > MAX_USERNAME_LENGTH ? name.substring(0, MAX_USERNAME_LENGTH) : name;
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

		// Everyone wearing a cape, own team included - counted like the Team Capes overlay, but with
		// Scout's own combat level range.
		Map<Integer, Integer> capeCounts = TeamCapeOverlay.countCapes(client.getPlayers(), config.scoutLevelFilter(),
			config.scoutMinCombatLevel(), config.scoutMaxCombatLevel());
		String report = ScoutReport.format(capeCounts, capeNames, Math.max(1, config.scoutMinCapeCount()),
			client.getWorld(), locationName, wildernessLevel);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("content", report);
		String username = posterName(config.scoutPosterName(),
			client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null);
		if (username != null)
		{
			payload.put("username", username);
		}
		payload.put("allowed_mentions", Collections.singletonMap("parse", Collections.emptyList()));
		String json = gson.toJson(payload);

		ScoutMapStyle style = config.scoutMap();
		Player local = client.getLocalPlayer();
		WorldPoint here = local != null ? local.getWorldLocation() : null;
		List<WildernessLocation> boxes = locations.getStore().getLocations();
		DrawnMap drawn = style == ScoutMapStyle.DRAWN && here != null ? new DrawnMap(here, dots(local), boxes) : null;
		Rectangle minimap = style == ScoutMapStyle.MINIMAP && here != null ? minimapBounds() : null;
		List<ScoutMap.Cape> capes = config.scoutCapes() ? capes(capeCounts, capeNames) : Collections.emptyList();

		if (minimap == null)
		{
			executor.submit(() -> send(url, json, pictures(drawn, null, capes), report));
			return;
		}

		// Zoomed out for the frame it's cut from; back to yours as soon as it's drawn.
		boolean zoomWasOn = client.isMinimapZoom();
		double zoomWas = client.getMinimapZoom();
		Runnable restoreZoom = () ->
		{
			client.setMinimapZoom(zoomWas);
			client.setMinimapZoom(zoomWasOn);
		};

		client.setMinimapZoom(true);
		client.setMinimapZoom(Math.min(zoomWas, MINIMAP_CAPTURE_ZOOM));

		// Whichever comes first: the next frame (with the minimap), or the timeout (without).
		AtomicBoolean sent = new AtomicBoolean();
		int canvasWidth = client.getCanvasWidth();
		drawManager.requestNextFrameListener(frame ->
		{
			if (!sent.compareAndSet(false, true))
			{
				return;
			}
			BufferedImage map = cutMinimap(frame, minimap, canvasWidth, here, boxes);
			restoreZoom.run();
			executor.submit(() -> send(url, json, pictures(null, map, capes), report));
		});
		executor.schedule(() ->
		{
			if (sent.compareAndSet(false, true))
			{
				clientThread.invoke(restoreZoom);
				send(url, json, pictures(null, null, capes), report);
			}
		}, FRAME_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
	}

	/**
	 * The cape counter's rows, as the Team Capes overlay shows them: capes with at least its
	 * minimum wearers, most first, the icon with the count on it. Client thread (item icons).
	 */
	private List<ScoutMap.Cape> capes(Map<Integer, Integer> capeCounts, Map<Integer, String> capeNames)
	{
		int minimum = Math.max(1, config.minimumTeamCapeCount());
		List<ScoutMap.Cape> capes = new ArrayList<>();
		capeCounts.entrySet().stream()
			.filter(e -> e.getValue() >= minimum)
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(MAX_CAPE_ROWS)
			.forEach(e ->
			{
				String name = capeNames.get(e.getKey());
				capes.add(new ScoutMap.Cape(itemManager.getImage(TeamCapeOverlay.teamCapeItemId(e.getKey()), e.getValue(), true),
					e.getValue() + "x Team-" + e.getKey() + (name != null ? " (" + name + ")" : "")));
			});
		return capes;
	}

	/**
	 * What the drawn map needs, read on the client thread.
	 */
	private static final class DrawnMap
	{
		final WorldPoint centre;
		final List<ScoutMap.Dot> dots;
		final List<WildernessLocation> locations;

		DrawnMap(WorldPoint centre, List<ScoutMap.Dot> dots, List<WildernessLocation> locations)
		{
			this.centre = centre;
			this.dots = dots;
			this.locations = locations;
		}
	}

	/**
	 * Every player on screen: enemies, then your team, then you - so you're drawn on top.
	 */
	private List<ScoutMap.Dot> dots(Player local)
	{
		List<ScoutMap.Dot> enemies = new ArrayList<>();
		List<ScoutMap.Dot> mates = new ArrayList<>();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local)
			{
				continue;
			}
			WorldPoint p = player.getWorldLocation();
			boolean mate = team.isOwnTeam(player);
			(mate ? mates : enemies).add(new ScoutMap.Dot(p.getX(), p.getY(), mate ? ScoutMap.TEAM_COLOR : ScoutMap.ENEMY_COLOR));
		}
		List<ScoutMap.Dot> dots = new ArrayList<>(enemies);
		dots.addAll(mates);
		WorldPoint you = local.getWorldLocation();
		dots.add(new ScoutMap.Dot(you.getX(), you.getY(), ScoutMap.YOU_COLOR));
		return dots;
	}

	/**
	 * Your minimap, turned north-up. Client thread, while the frame's zoom is still set: where
	 * you and a point north of you sit on the minimap gives the centre and the turn.
	 */
	private BufferedImage cutMinimap(Image frame, Rectangle bounds, int canvasWidth, WorldPoint here,
		List<WildernessLocation> boxes)
	{
		Player local = client.getLocalPlayer();
		LocalPoint you = local != null ? local.getLocalLocation() : null;
		if (you == null)
		{
			return null;
		}
		int far = Integer.MAX_VALUE / 4;
		Point centre = Perspective.localToMinimap(client, you, far);
		Point north = Perspective.localToMinimap(client, you.plus(0, 2 * Perspective.LOCAL_TILE_SIZE), far);
		if (centre == null || north == null)
		{
			return null;
		}
		// Turn the direction of north on the minimap to straight up.
		double rotation = -Math.PI / 2 - Math.atan2(north.getY() - centre.getY(), north.getX() - centre.getX());
		return ScoutMap.minimap(frame, bounds, canvasWidth, centre.getX(), centre.getY(), rotation,
			client.getMinimapZoom(), here.getX(), here.getY(), here.getPlane(), boxes);
	}

	/**
	 * The pictures to attach, map first. Off the client thread - the drawn map downloads tiles.
	 */
	private List<BufferedImage> pictures(DrawnMap drawn, BufferedImage minimap, List<ScoutMap.Cape> capes)
	{
		BufferedImage map = minimap;
		if (drawn != null)
		{
			try
			{
				map = ScoutMap.drawn(this::mapSquare, drawn.centre.getX(), drawn.centre.getY(), drawn.centre.getPlane(),
					Math.max(8, config.scoutMapRadius()), drawn.dots, drawn.locations, ScoutMap.capePanel(capes));
			}
			catch (RuntimeException e)
			{
				log.warn("PvP Tools: couldn't draw the scout map", e);
			}
		}
		BufferedImage picture = ScoutMap.withCapes(map, capes);
		return picture != null ? Collections.singletonList(picture) : Collections.emptyList();
	}

	/**
	 * One map square, from the disk cache or downloaded into it; null if there's none (sea, or
	 * the download failed). Blocking - never on the client thread.
	 */
	private BufferedImage mapSquare(int[] planeXY)
	{
		File file = new File(TILE_CACHE, planeXY[0] + "_" + planeXY[1] + "_" + planeXY[2] + ".png");
		try
		{
			if (!file.isFile())
			{
				Request request = new Request.Builder()
					.url(String.format(ScoutMap.TILE_URL, planeXY[0], planeXY[1], planeXY[2]))
					.build();
				try (Response response = okHttpClient.newCall(request).execute())
				{
					if (!response.isSuccessful() || response.body() == null)
					{
						return null;
					}
					byte[] bytes = response.body().bytes();
					TILE_CACHE.mkdirs();
					Files.write(file.toPath(), bytes);
				}
			}
			return ImageIO.read(file);
		}
		catch (IOException e)
		{
			log.debug("PvP Tools: no map square {}", file.getName(), e);
			return null;
		}
	}

	/**
	 * The minimap's on-canvas bounds, or null if it isn't showing. Client thread.
	 */
	private Rectangle minimapBounds()
	{
		for (int id : MINIMAP_WIDGETS)
		{
			Widget widget = client.getWidget(id);
			if (widget != null && !widget.isHidden())
			{
				Rectangle bounds = widget.getBounds();
				if (bounds != null && bounds.width > 0 && bounds.height > 0)
				{
					return bounds;
				}
			}
		}
		return null;
	}

	/**
	 * Posts the report, with the pictures attached. Any thread.
	 */
	private void send(HttpUrl url, String json, List<BufferedImage> pictures, String report)
	{
		RequestBody body = RequestBody.create(JSON, json);
		if (!pictures.isEmpty())
		{
			try
			{
				MultipartBody.Builder multipart = new MultipartBody.Builder()
					.setType(MultipartBody.FORM)
					.addFormDataPart("payload_json", json);
				for (int i = 0; i < pictures.size(); i++)
				{
					ByteArrayOutputStream png = new ByteArrayOutputStream();
					ImageIO.write(pictures.get(i), "png", png);
					multipart.addFormDataPart("files[" + i + "]", "scout-" + (i + 1) + ".png",
						RequestBody.create(PNG, png.toByteArray()));
				}
				body = multipart.build();
			}
			catch (IOException e)
			{
				log.warn("PvP Tools: couldn't encode the scout pictures", e);
			}
		}

		Request request = new Request.Builder()
			.url(url)
			.post(body)
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
