package net.runelite.client.plugins.preptools;

/**
 * One named, axis-aligned rectangular area of the wilderness (or any other plane/region), used by
 * PrepToolsPlugin#findLocationName() to turn a WorldPoint into a human-readable place name (e.g.
 * "Ferox Enclave"). Deliberately just a min/max box rather than an arbitrary polygon - see
 * PrepToolsPlugin's loadWildernessLocations() doc for why: a handful of rectangles, built up over
 * time with the sidebar's "Location Mapper" tool, covers the useful cases (named landmarks/hotspots)
 * without needing real polygon geometry.
 *
 * Plain Gson-serializable POJO - field names are the JSON keys, persisted as a single JSON-encoded
 * string via ConfigManager (see PrepToolsPlugin#saveWildernessLocations()/loadWildernessLocations()).
 */
class WildernessLocation
{
	String name;
	int minX;
	int minY;
	int maxX;
	int maxY;
	int plane;

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

	boolean contains(int x, int y, int worldPlane)
	{
		return worldPlane == plane && x >= minX && x <= maxX && y >= minY && y <= maxY;
	}
}
