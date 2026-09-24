package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

/**
 * Where you are: your position, wilderness level and named wilderness location (Location Spam,
 * Scout, the infobox), plus the Location Mapper's editing state and the location list itself
 * (WildernessLocationStore).
 *
 * Position fields are updated on the client thread each tick and read from the EDT by the sidebar;
 * they're single immutable references, so that's safe without locking.
 */
@Slf4j
class LocationTracker
{
	/**
	 * "Level: 15" - unanchored, as CombatLevelPlugin (attack level range option, on by default)
	 * rewrites this widget's text to "Level: 15{@code <br>}81-111".
	 */
	private static final Pattern WILDERNESS_LEVEL_PATTERN = Pattern.compile("Level: (\\d+)");

	private final Client client;
	private final WildernessLocationStore store;

	@Getter
	private volatile WorldPoint position;
	@Getter
	private volatile Integer wildernessLevel;
	@Getter
	private volatile String locationName;

	/**
	 * Other boxes you're standing in that lost on priority, as "Name (P1)" - Mapper readout.
	 */
	@Getter
	private volatile List<String> overlaps = Collections.emptyList();

	/**
	 * The tile under the cursor, for the Mapper readout.
	 */
	@Getter
	private volatile WorldPoint hoveredPoint;
	@Getter
	private volatile String hoveredLocationName;

	@Getter
	private volatile WorldPoint cornerA;
	@Getter
	private volatile WorldPoint cornerB;

	/**
	 * The box being edited in the Mapper, drawn by WildernessLocationDebugOverlay before it's saved.
	 */
	@Getter
	private volatile WildernessLocation preview;

	@Inject
	LocationTracker(Client client, ConfigManager configManager, Gson gson)
	{
		this.client = client;
		this.store = new WildernessLocationStore(new WildernessLocationStore.Storage()
		{
			@Override
			public String get(String key)
			{
				return configManager.getConfiguration(PrepToolsConfig.GROUP, key);
			}

			@Override
			public void set(String key, String value)
			{
				configManager.setConfiguration(PrepToolsConfig.GROUP, key, value);
			}
		}, gson);
	}

	/**
	 * Every saved location containing the point, best match first: highest priority, then the
	 * smallest box (most specific), then list order.
	 */
	static List<WildernessLocation> findContaining(List<WildernessLocation> locations, WorldPoint point)
	{
		List<WildernessLocation> matches = new ArrayList<>();
		for (WildernessLocation location : locations)
		{
			if (location.contains(point.getX(), point.getY(), point.getPlane()))
			{
				matches.add(location);
			}
		}
		// Stable sort: equal priority and area keeps list order.
		matches.sort(Comparator.<WildernessLocation>comparingInt(l -> -l.priority).thenComparingLong(WildernessLocation::area));
		return matches;
	}

	void load()
	{
		store.load();
	}

	WildernessLocationStore getStore()
	{
		return store;
	}

	void onGameTick()
	{
		Player local = client.getLocalPlayer();
		WorldPoint point = local != null ? local.getWorldLocation() : null;
		if (point == null)
		{
			return;
		}

		position = point;
		Integer level = readWildernessLevelWidget();
		wildernessLevel = level != null ? level : wildernessLevelFromCoordinates(point);

		List<WildernessLocation> matches = findContaining(store.getLocations(), point);
		locationName = matches.isEmpty() ? null : matches.get(0).name;
		List<String> lost = new ArrayList<>();
		for (int i = 1; i < matches.size(); i++)
		{
			lost.add(matches.get(i).name + " (P" + matches.get(i).priority + ")");
		}
		overlaps = lost;

		Tile hovered = client.getTopLevelWorldView().getSelectedSceneTile();
		hoveredPoint = hovered != null ? hovered.getWorldLocation() : null;
		List<WildernessLocation> hoveredMatches = hoveredPoint != null
			? findContaining(store.getLocations(), hoveredPoint) : Collections.emptyList();
		hoveredLocationName = hoveredMatches.isEmpty() ? null : hoveredMatches.get(0).name;
	}

	void setCornerA(WorldPoint point)
	{
		cornerA = point;
	}

	void setCornerB(WorldPoint point)
	{
		cornerB = point;
	}

	void setPreview(WildernessLocation location)
	{
		preview = location;
	}

	/**
	 * Saves the box between the two corners. False if a corner is missing or they're on different
	 * planes.
	 */
	boolean saveCorners(String name, int priority)
	{
		WorldPoint a = cornerA;
		WorldPoint b = cornerB;
		if (name == null || name.trim().isEmpty() || a == null || b == null || a.getPlane() != b.getPlane())
		{
			return false;
		}

		store.add(name.trim(), Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
			Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), a.getPlane(), priority);
		cornerA = null;
		cornerB = null;
		return true;
	}

	/**
	 * Saves an export under ~/.runelite/pvptools-exports/ with a timestamp - every export doubles as
	 * a backup. Null if it couldn't be written.
	 */
	File saveExport(String json)
	{
		try
		{
			File dir = new File(RuneLite.RUNELITE_DIR, "pvptools-exports");
			if (!dir.isDirectory() && !dir.mkdirs())
			{
				return null;
			}
			File file = new File(dir, "wilderness_locations-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".json");
			Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
			return file;
		}
		catch (IOException e)
		{
			log.warn("PvP Tools: couldn't write wilderness locations export", e);
			return null;
		}
	}

	void clear()
	{
		position = null;
		wildernessLevel = null;
		locationName = null;
		overlaps = Collections.emptyList();
		hoveredPoint = null;
		hoveredLocationName = null;
		cornerA = null;
		cornerB = null;
		preview = null;
	}

	/**
	 * The game's own wilderness level, null outside the wilderness.
	 */
	private Integer readWildernessLevelWidget()
	{
		Widget widget = client.getWidget(InterfaceID.PvpIcons.WILDERNESSLEVEL);
		String text = widget != null ? widget.getText() : null;
		if (text == null)
		{
			return null;
		}
		Matcher m = WILDERNESS_LEVEL_PATTERN.matcher(text);
		return m.find() ? Integer.parseInt(m.group(1)) : null;
	}

	/**
	 * Fallback if the widget can't be read while the IN_WILDERNESS varbit says you're in the
	 * wilderness: 8 tiles per level from y=3520 (surface) or y=9920 (underground).
	 */
	private Integer wildernessLevelFromCoordinates(WorldPoint point)
	{
		if (client.getVarbitValue(Varbits.IN_WILDERNESS) != 1)
		{
			return null;
		}
		int baseY = point.getY() >= 9920 ? 9920 : 3520;
		int level = (point.getY() - baseY) / 8 + 1;
		return level >= 1 ? level : null;
	}
}
