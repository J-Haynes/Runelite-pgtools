package net.runelite.client.plugins.preptools;

/**
 * One named, axis-aligned rectangular area of the wilderness (or any other plane/region), used by
 * PrepToolsPlugin#findLocationName() to turn a WorldPoint into a human-readable place name (e.g.
 * "Ferox Enclave"). Deliberately just a min/max box rather than an arbitrary polygon - see
 * PrepToolsPlugin's loadWildernessLocations() doc for why: a handful of rectangles, built up over
 * time with the sidebar's "Location Mapper" tool, covers the useful cases (named landmarks/hotspots)
 * without needing real polygon geometry.
 *
 * When a point is inside more than one box, PrepToolsPlugin#resolveLocation() picks: highest
 * priority first, then the smallest box (the most specific place), then earliest in the list.
 *
 * Plain Gson-serializable POJO - field names are the JSON keys, both in the shipped
 * wilderness_locations.json resource and in a user's personal layer (see WildernessLocationStore).
 */
class WildernessLocation
{
	/**
	 * Stable identity, never shown to users. "u-..." is minted when a user adds a location; the
	 * shipped file keeps whatever ids its entries had when exported ("s-..." for the original
	 * batch), so a user's edit/hide of a shipped location survives updates to the shipped map.
	 */
	String id;

	String name;
	int minX;
	int minY;
	int maxX;
	int maxY;
	int plane;

	/**
	 * Higher wins when boxes overlap - see the class doc. 0 for anything mapped by hand (and for
	 * locations saved before this field existed - Gson leaves a missing field at 0); starter
	 * locations imported from OpenOSRS sit at OpenOsrsWildernessLocations.IMPORTED_PRIORITY (-1).
	 */
	int priority;

	/**
	 * Where this entry in the merged list came from - set by WildernessLocationStore when it builds
	 * that list, never saved (transient, so Gson skips it).
	 */
	transient Origin origin;

	enum Origin
	{
		/** Straight from the shipped map. */
		SHIPPED,
		/** A shipped location this user has edited. */
		EDITED,
		/** Added by this user - not part of the shipped map. */
		YOURS
	}

	WildernessLocation()
	{
	}

	WildernessLocation(String name, int minX, int minY, int maxX, int maxY, int plane)
	{
		this.name = name;
		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
		this.plane = plane;
	}

	WildernessLocation(String name, int minX, int minY, int maxX, int maxY, int plane, int priority)
	{
		this(name, minX, minY, maxX, maxY, plane);
		this.priority = priority;
	}

	WildernessLocation copy()
	{
		WildernessLocation copy = new WildernessLocation(name, minX, minY, maxX, maxY, plane, priority);
		copy.id = id;
		copy.origin = origin;
		return copy;
	}

	/**
	 * Same name, box, plane and priority - ignores id/origin. Used to recognise an edit that's been
	 * put back to (or published as) exactly the shipped values.
	 */
	boolean sameData(WildernessLocation other)
	{
		return other != null && java.util.Objects.equals(name, other.name) && minX == other.minX && minY == other.minY
			&& maxX == other.maxX && maxY == other.maxY && plane == other.plane && priority == other.priority;
	}

	long area()
	{
		return (long) (maxX - minX + 1) * (maxY - minY + 1);
	}

	boolean contains(int x, int y, int worldPlane)
	{
		return worldPlane == plane && x >= minX && x <= maxX && y >= minY && y <= maxY;
	}
}
