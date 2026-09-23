package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The wilderness location list, in two layers:
 *
 * - SHIPPED: wilderness_locations.json, bundled in the plugin - the team's map, the same for every
 *   install, updated by shipping a new version of the file. Read-only at runtime.
 * - PERSONAL: this user's own changes on top of it, saved in their RuneLite profile under
 *   USER_LAYER_KEY - locations they ADDED, EDITS to shipped locations (keyed by the shipped id),
 *   and shipped locations they HID.
 *
 * getLocations() is the merge of the two. Because edits/hides refer to shipped entries by id,
 * they survive updates to the shipped map, and anyone who never edits simply follows it.
 *
 * Publishing the map: the Location tab's Export button produces the merged list in exactly the
 * shipped file's format - replacing wilderness_locations.json with it and releasing makes it the
 * new shipped map. reconcile() then drops the exporter's own now-redundant personal entries on
 * their next start (an edit identical to the new shipped value, an addition whose id is now shipped).
 *
 * Storage goes through the small Storage interface (a RuneLite ConfigManager key in the plugin, a
 * plain map in WildernessLocationStoreTest) so the layering logic is unit-testable.
 */
@Slf4j
class WildernessLocationStore
{
	static final String RESOURCE = "wilderness_locations.json";
	static final String USER_LAYER_KEY = "wildernessLocationsUserLayer";

	/**
	 * Where every saved location used to live, before the shipped map existed - one flat list per
	 * profile. Read once, by migrateLegacyList(), and then deliberately left in place as a backup.
	 */
	static final String LEGACY_KEY = "wildernessLocationsJson";

	/**
	 * If the personal layer ever fails to parse, its raw text is copied here before anything can
	 * save over it - so a bug or corrupted profile can never silently destroy someone's mapping.
	 */
	static final String USER_LAYER_BACKUP_KEY = "wildernessLocationsUserLayerUnreadable";

	/**
	 * The five rough starter boxes the plugin used to seed every new profile with - recognised
	 * during migration as stock data rather than a user's own work.
	 */
	private static final List<WildernessLocation> OLD_DEFAULTS = Arrays.asList(
		new WildernessLocation("Ferox Enclave", 3125, 3607, 3160, 3638, 0),
		new WildernessLocation("Wilderness Volcano", 3345, 3903, 3390, 3936, 0),
		new WildernessLocation("Lava Maze", 3050, 3810, 3070, 3830, 0),
		new WildernessLocation("Graveyard of Shadows", 3160, 3660, 3180, 3680, 0),
		new WildernessLocation("Mage Arena Bank", 3090, 3940, 3110, 3960, 0)
	);

	interface Storage
	{
		String get(String key);

		void set(String key, String value);
	}

	/**
	 * The shipped file's format - also exactly what export() produces.
	 */
	static class ShippedFile
	{
		int version = 1;
		String about;
		List<String> openOsrsNotice;
		List<WildernessLocation> locations;
	}

	static class UserLayer
	{
		List<WildernessLocation> added = new ArrayList<>();
		Map<String, WildernessLocation> edits = new LinkedHashMap<>();
		Set<String> hidden = new LinkedHashSet<>();

		/**
		 * Gson leaves a collection missing from the saved JSON as null - normalise to empty.
		 */
		UserLayer normalised()
		{
			if (added == null)
			{
				added = new ArrayList<>();
			}
			if (edits == null)
			{
				edits = new LinkedHashMap<>();
			}
			if (hidden == null)
			{
				hidden = new LinkedHashSet<>();
			}
			return this;
		}
	}

	private final Storage storage;
	private final Gson gson;
	private final Gson prettyGson;

	// Shipped header, kept so export() reproduces it (notably the OpenOSRS licence notice).
	private ShippedFile shippedHeader = new ShippedFile();
	private Map<String, WildernessLocation> shipped = new LinkedHashMap<>();
	private UserLayer layer = new UserLayer();

	/**
	 * The merged list - replaced wholesale on every change, never mutated, so the game/render
	 * threads can iterate it while a sidebar button changes it from the Swing EDT.
	 */
	private volatile List<WildernessLocation> locations = Collections.emptyList();

	WildernessLocationStore(Storage storage, Gson gson)
	{
		this.storage = storage;
		this.gson = gson;
		this.prettyGson = gson.newBuilder().setPrettyPrinting().create();
	}

	/**
	 * Loads the shipped map from the plugin's own resource.
	 */
	void load()
	{
		try (InputStream in = WildernessLocationStore.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.error("PvP Tools: {} missing from the plugin - no shipped locations", RESOURCE);
				load((Reader) null);
				return;
			}
			load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.error("PvP Tools: failed to read {}", RESOURCE, e);
			load((Reader) null);
		}
	}

	/**
	 * Loads the shipped map from the given reader (null = none), then this user's personal layer.
	 */
	void load(Reader shippedReader)
	{
		shipped = new LinkedHashMap<>();
		if (shippedReader != null)
		{
			try
			{
				ShippedFile file = gson.fromJson(shippedReader, ShippedFile.class);
				if (file != null && file.locations != null)
				{
					shippedHeader = file;
					for (WildernessLocation location : file.locations)
					{
						shipped.put(location.id, location);
					}
				}
			}
			catch (JsonParseException e)
			{
				log.error("PvP Tools: {} is not valid - no shipped locations", RESOURCE, e);
			}
		}

		layer = loadUserLayer();
		if (reconcile())
		{
			saveUserLayer();
		}
		rebuild();
	}

	private UserLayer loadUserLayer()
	{
		String raw = storage.get(USER_LAYER_KEY);
		if (raw == null || raw.trim().isEmpty())
		{
			return migrateLegacyList();
		}

		try
		{
			UserLayer parsed = gson.fromJson(raw, UserLayer.class);
			return parsed != null ? parsed.normalised() : new UserLayer();
		}
		catch (JsonParseException e)
		{
			// Never lose it: park the raw text under its own key BEFORE the next save can replace it.
			log.warn("PvP Tools: personal wilderness locations unreadable - copied to '{}', starting from the shipped map", USER_LAYER_BACKUP_KEY, e);
			storage.set(USER_LAYER_BACKUP_KEY, raw);
			return new UserLayer();
		}
	}

	/**
	 * One-time conversion of a pre-shipped-map profile (one flat saved list) into a personal layer.
	 * Only genuine personal work becomes personal additions - anything identical to a shipped
	 * location, one of the five old starter boxes, or one of the original OpenOSRS imports is stock
	 * data and is dropped (otherwise every existing profile would end up with duplicates of the
	 * shipped map). Nothing is hidden: a location missing from someone's old list just means they
	 * never had it, not that they deleted it. The old key is left untouched as a backup.
	 */
	private UserLayer migrateLegacyList()
	{
		UserLayer migrated = new UserLayer();
		String raw = storage.get(LEGACY_KEY);
		if (raw == null || raw.trim().isEmpty())
		{
			return migrated;
		}

		WildernessLocation[] legacy;
		try
		{
			legacy = gson.fromJson(raw, WildernessLocation[].class);
		}
		catch (JsonParseException e)
		{
			log.warn("PvP Tools: old saved wilderness locations unreadable - left untouched under '{}'", LEGACY_KEY, e);
			return migrated;
		}

		if (legacy == null)
		{
			return migrated;
		}

		List<WildernessLocation> stock = new ArrayList<>(shipped.values());
		stock.addAll(OLD_DEFAULTS);
		stock.addAll(OpenOsrsWildernessLocations.locations());

		for (WildernessLocation location : legacy)
		{
			if (location == null || stock.stream().anyMatch(location::sameData))
			{
				continue;
			}

			WildernessLocation added = location.copy();
			added.id = newUserId();
			migrated.added.add(added);
		}

		log.info("PvP Tools: migrated old saved wilderness locations - {} kept as personal additions", migrated.added.size());
		// Saved even if empty, so the migration never runs again.
		storage.set(USER_LAYER_KEY, gson.toJson(migrated));
		return migrated;
	}

	/**
	 * Drops personal entries made redundant by the current shipped map. Returns whether anything
	 * changed.
	 */
	boolean reconcile()
	{
		boolean changed = layer.edits.entrySet().removeIf(e ->
			!shipped.containsKey(e.getKey()) || e.getValue().sameData(shipped.get(e.getKey())));
		changed |= layer.hidden.removeIf(id -> !shipped.containsKey(id));
		changed |= layer.added.removeIf(a ->
			shipped.containsKey(a.id) || shipped.values().stream().anyMatch(a::sameData));
		return changed;
	}

	private void rebuild()
	{
		List<WildernessLocation> merged = new ArrayList<>();
		for (WildernessLocation location : shipped.values())
		{
			if (layer.hidden.contains(location.id))
			{
				continue;
			}

			WildernessLocation edit = layer.edits.get(location.id);
			WildernessLocation entry = (edit != null ? edit : location).copy();
			entry.id = location.id;
			entry.origin = edit != null ? WildernessLocation.Origin.EDITED : WildernessLocation.Origin.SHIPPED;
			merged.add(entry);
		}
		for (WildernessLocation location : layer.added)
		{
			WildernessLocation entry = location.copy();
			entry.origin = WildernessLocation.Origin.YOURS;
			merged.add(entry);
		}
		locations = Collections.unmodifiableList(merged);
	}

	private void saveUserLayer()
	{
		storage.set(USER_LAYER_KEY, gson.toJson(layer));
	}

	private void changed()
	{
		saveUserLayer();
		rebuild();
	}

	private static String newUserId()
	{
		return "u-" + UUID.randomUUID().toString().substring(0, 8);
	}

	List<WildernessLocation> getLocations()
	{
		return locations;
	}

	int getHiddenCount()
	{
		return layer.hidden.size();
	}

	void add(String name, int minX, int minY, int maxX, int maxY, int plane, int priority)
	{
		WildernessLocation location = new WildernessLocation(name, minX, minY, maxX, maxY, plane, priority);
		location.id = newUserId();
		layer.added.add(location);
		changed();
	}

	/**
	 * Edits a location by id: a shipped one gets a personal edit (dropped again if it's put back to
	 * exactly the shipped values), a personal addition is edited in place.
	 */
	void update(String id, String name, int minX, int minY, int maxX, int maxY, int plane, int priority)
	{
		WildernessLocation updated = new WildernessLocation(name, minX, minY, maxX, maxY, plane, priority);
		updated.id = id;

		WildernessLocation shippedLocation = shipped.get(id);
		if (shippedLocation != null)
		{
			if (updated.sameData(shippedLocation))
			{
				layer.edits.remove(id);
			}
			else
			{
				layer.edits.put(id, updated);
			}
			changed();
			return;
		}

		for (int i = 0; i < layer.added.size(); i++)
		{
			if (layer.added.get(i).id.equals(id))
			{
				layer.added.set(i, updated);
				changed();
				return;
			}
		}
	}

	/**
	 * Removes a location by id: a shipped one is hidden (restorable), a personal addition deleted.
	 */
	void remove(String id)
	{
		if (shipped.containsKey(id))
		{
			layer.edits.remove(id);
			layer.hidden.add(id);
			changed();
		}
		else if (layer.added.removeIf(a -> a.id.equals(id)))
		{
			changed();
		}
	}

	/**
	 * Discards this user's edit to a shipped location, back to the shipped values.
	 */
	void revert(String id)
	{
		if (layer.edits.remove(id) != null)
		{
			changed();
		}
	}

	void restoreHidden()
	{
		if (!layer.hidden.isEmpty())
		{
			layer.hidden.clear();
			changed();
		}
	}

	/**
	 * The merged list in the shipped file's format (ids included, origin not) - drop-in replacement
	 * for wilderness_locations.json.
	 */
	String export()
	{
		ShippedFile file = new ShippedFile();
		file.version = shippedHeader.version;
		file.about = shippedHeader.about;
		file.openOsrsNotice = shippedHeader.openOsrsNotice;
		file.locations = new ArrayList<>();
		for (WildernessLocation location : locations)
		{
			WildernessLocation copy = location.copy();
			copy.origin = null;
			file.locations.add(copy);
		}
		return prettyGson.toJson(file) + "\n";
	}
}
