package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class WildernessLocationStoreTest
{
	private static final String SHIPPED = "{\"version\":1,\"locations\":["
		+ "{\"id\":\"s-a\",\"name\":\"Ferox\",\"minX\":10,\"minY\":10,\"maxX\":20,\"maxY\":20,\"plane\":0,\"priority\":0},"
		+ "{\"id\":\"s-b\",\"name\":\"Graves\",\"minX\":30,\"minY\":30,\"maxX\":40,\"maxY\":40,\"plane\":0,\"priority\":-1}"
		+ "]}";

	private final Gson gson = new Gson();
	private Map<String, String> config;
	private WildernessLocationStore store;

	@Before
	public void before()
	{
		config = new HashMap<>();
		store = newStore(SHIPPED);
	}

	private WildernessLocationStore newStore(String shipped)
	{
		WildernessLocationStore s = new WildernessLocationStore(new WildernessLocationStore.Storage()
		{
			@Override
			public String get(String key)
			{
				return config.get(key);
			}

			@Override
			public void set(String key, String value)
			{
				config.put(key, value);
			}
		}, gson);
		s.load(new StringReader(shipped));
		return s;
	}

	private static WildernessLocation byId(List<WildernessLocation> locations, String id)
	{
		return locations.stream().filter(l -> l.id.equals(id)).findFirst().orElse(null);
	}

	@Test
	public void bundledResourceIsValid() throws Exception
	{
		try (InputStream in = WildernessLocationStore.class.getResourceAsStream(WildernessLocationStore.RESOURCE))
		{
			assertNotNull("wilderness_locations.json must be bundled in the plugin", in);
			WildernessLocationStore.ShippedFile file = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), WildernessLocationStore.ShippedFile.class);
			assertNotNull(file.locations);
			assertFalse(file.locations.isEmpty());
			assertNotNull("OpenOSRS licence notice must stay in the shipped file", file.openOsrsNotice);

			Set<String> ids = new HashSet<>();
			for (WildernessLocation l : file.locations)
			{
				assertNotNull("every shipped location needs an id", l.id);
				assertTrue("duplicate id " + l.id, ids.add(l.id));
				assertTrue("blank name for " + l.id, l.name != null && !l.name.trim().isEmpty());
				assertTrue("inverted box for " + l.id, l.minX <= l.maxX && l.minY <= l.maxY);
			}
		}
	}

	@Test
	public void freshInstallFollowsShippedMap()
	{
		List<WildernessLocation> locations = store.getLocations();
		assertEquals(2, locations.size());
		assertEquals(WildernessLocation.Origin.SHIPPED, locations.get(0).origin);
		assertNull("nothing personal to save", config.get(WildernessLocationStore.USER_LAYER_KEY));
	}

	@Test
	public void editingShippedLocationIsPersonalAndRevertible()
	{
		store.update("s-a", "Ferox Enclave", 10, 10, 25, 20, 0, 1);
		WildernessLocation edited = byId(store.getLocations(), "s-a");
		assertEquals(WildernessLocation.Origin.EDITED, edited.origin);
		assertEquals(25, edited.maxX);

		store.revert("s-a");
		assertEquals(WildernessLocation.Origin.SHIPPED, byId(store.getLocations(), "s-a").origin);
		assertEquals(20, byId(store.getLocations(), "s-a").maxX);
	}

	@Test
	public void editingBackToShippedValuesDropsTheEdit()
	{
		store.update("s-a", "Changed", 10, 10, 20, 20, 0, 0);
		store.update("s-a", "Ferox", 10, 10, 20, 20, 0, 0);
		assertEquals(WildernessLocation.Origin.SHIPPED, byId(store.getLocations(), "s-a").origin);
	}

	@Test
	public void removingShippedLocationHidesItUntilRestored()
	{
		store.remove("s-b");
		assertNull(byId(store.getLocations(), "s-b"));
		assertEquals(1, store.getHiddenCount());

		store.restoreHidden();
		assertNotNull(byId(store.getLocations(), "s-b"));
		assertEquals(0, store.getHiddenCount());
	}

	@Test
	public void addedLocationsAreYoursAndDeletable()
	{
		store.add("Green Hill", 50, 50, 60, 60, 0, 0);
		WildernessLocation added = store.getLocations().get(2);
		assertEquals(WildernessLocation.Origin.YOURS, added.origin);
		assertTrue(added.id.startsWith("u-"));

		store.remove(added.id);
		assertEquals(2, store.getLocations().size());
	}

	@Test
	public void personalLayerSurvivesRestartAndShippedUpdates()
	{
		store.add("Green Hill", 50, 50, 60, 60, 0, 0);
		store.update("s-a", "Ferox Enclave", 10, 10, 25, 20, 0, 0);
		store.remove("s-b");

		// New shipped version: s-a's box changed upstream, plus a new shipped location.
		String updatedShipped = "{\"version\":1,\"locations\":["
			+ "{\"id\":\"s-a\",\"name\":\"Ferox\",\"minX\":11,\"minY\":10,\"maxX\":20,\"maxY\":20,\"plane\":0,\"priority\":0},"
			+ "{\"id\":\"s-b\",\"name\":\"Graves\",\"minX\":30,\"minY\":30,\"maxX\":40,\"maxY\":40,\"plane\":0,\"priority\":-1},"
			+ "{\"id\":\"s-c\",\"name\":\"Chins\",\"minX\":70,\"minY\":70,\"maxX\":80,\"maxY\":80,\"plane\":0,\"priority\":0}"
			+ "]}";
		WildernessLocationStore restarted = newStore(updatedShipped);
		List<WildernessLocation> locations = restarted.getLocations();

		assertEquals("personal edit wins over the updated shipped box", 25, byId(locations, "s-a").maxX);
		assertNull("hidden stays hidden", byId(locations, "s-b"));
		assertNotNull("new shipped location appears", byId(locations, "s-c"));
		assertTrue(locations.stream().anyMatch(l -> l.name.equals("Green Hill") && l.origin == WildernessLocation.Origin.YOURS));
	}

	@Test
	public void publishedChangesReconcileAway()
	{
		store.add("Green Hill", 50, 50, 60, 60, 0, 0);
		store.update("s-a", "Ferox Enclave", 10, 10, 25, 20, 0, 0);

		// The export becomes the new shipped file - the exporter's personal layer is now redundant.
		WildernessLocationStore restarted = newStore(store.export());
		for (WildernessLocation l : restarted.getLocations())
		{
			assertEquals(l.name, WildernessLocation.Origin.SHIPPED, l.origin);
		}
		assertEquals(3, restarted.getLocations().size());
	}

	@Test
	public void legacyListMigratesOnlyPersonalWork()
	{
		config.put(WildernessLocationStore.LEGACY_KEY, "["
			// identical to shipped s-a
			+ "{\"name\":\"Ferox\",\"minX\":10,\"minY\":10,\"maxX\":20,\"maxY\":20,\"plane\":0,\"priority\":0},"
			// one of the five old starter boxes
			+ "{\"name\":\"Lava Maze\",\"minX\":3050,\"minY\":3810,\"maxX\":3070,\"maxY\":3830,\"plane\":0},"
			// genuine personal work
			+ "{\"name\":\"My Spot\",\"minX\":1,\"minY\":1,\"maxX\":2,\"maxY\":2,\"plane\":0,\"priority\":3}"
			+ "]");
		String legacy = config.get(WildernessLocationStore.LEGACY_KEY);

		WildernessLocationStore migrated = newStore(SHIPPED);
		List<WildernessLocation> locations = migrated.getLocations();
		assertEquals(3, locations.size());
		assertEquals(1, locations.stream().filter(l -> l.origin == WildernessLocation.Origin.YOURS).count());
		assertTrue(locations.stream().anyMatch(l -> l.name.equals("My Spot") && l.priority == 3));
		assertEquals("old key kept as a backup", legacy, config.get(WildernessLocationStore.LEGACY_KEY));
		assertNotNull("migration recorded so it never reruns", config.get(WildernessLocationStore.USER_LAYER_KEY));
	}

	@Test
	public void unreadablePersonalLayerIsBackedUpNotLost()
	{
		config.put(WildernessLocationStore.USER_LAYER_KEY, "{not json");
		WildernessLocationStore s = newStore(SHIPPED);

		assertEquals("{not json", config.get(WildernessLocationStore.USER_LAYER_BACKUP_KEY));
		assertEquals(2, s.getLocations().size());

		s.add("New", 1, 1, 2, 2, 0, 0);
		assertEquals("backup untouched by later saves", "{not json", config.get(WildernessLocationStore.USER_LAYER_BACKUP_KEY));
	}

	@Test
	public void exportIsShippedFormatWithoutOrigin()
	{
		store.add("Green Hill", 50, 50, 60, 60, 0, 0);
		String json = store.export();
		assertFalse(json.contains("origin"));
		WildernessLocationStore.ShippedFile file = gson.fromJson(json, WildernessLocationStore.ShippedFile.class);
		assertEquals(3, file.locations.size());
	}
}
