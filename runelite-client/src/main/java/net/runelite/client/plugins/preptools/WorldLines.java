package net.runelite.client.plugins.preptools;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.GeneralPath;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.geometry.Geometry;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.gpu.GpuPluginConfig;

/**
 * Draws world-coordinate shapes (multi-combat area borders, Location Mapper boxes) as lines on the
 * ground. Shared by MultiLinesOverlay and WildernessLocationDebugOverlay.
 *
 * Why this exists instead of the usual LocalPoint.fromWorld() + Perspective.localToCanvas(LocalPoint)
 * pairing (NpcAggroAreaPlugin, upstream Multi-Lines/Wilderness Lines):
 * - LocalPoint.fromWorld() returns null for anything outside the 104x104 loaded scene. A Location
 *   Mapper box with even one corner outside the scene was therefore skipped entirely - and whether
 *   a corner is inside depends on where the scene happened to load, which is why boxes "sometimes
 *   don't show when you leave and re-enter the same area".
 * - The GPU plugin draws terrain out to the 184x184 EXTENDED scene (up to 40 tiles beyond the loaded
 *   one), so lines limited to the loaded scene stop well short of what's visibly on screen.
 *
 * Instead, shapes are clipped to the extended scene and converted to local coordinates by hand
 * (plain arithmetic, valid outside the loaded scene too), then projected with
 * Perspective.localToCanvas(client, x, y, z), which accepts the whole extended range. The one thing
 * the API can't give outside the loaded scene is ground height (Perspective.getTileHeight() returns
 * 0 there), so points beyond it use the height of the nearest loaded tile instead - a good
 * approximation on the wilderness's mostly gentle terrain, but lines out there can sit slightly
 * above/below the ground on steep hills.
 */
final class WorldLines
{
	private static final int EXTENDED_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

	/**
	 * Draw distance used without the GPU plugin - the vanilla (CPU renderer) view distance.
	 */
	private static final int CPU_DRAW_DISTANCE = 25;

	private WorldLines()
	{
	}

	/**
	 * Clips a world-coordinate shape (x/y in tiles, a Rectangle(x, y, w, h) covering tiles x..x+w-1)
	 * to the extended scene and converts it to local coordinates, split into 1-tile segments so each
	 * one follows the terrain height. Needs rebuilding whenever the scene base changes.
	 */
	static GeneralPath toLocalLines(Shape worldShape, WorldView wv)
	{
		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		Rectangle extendedScene = new Rectangle(
			baseX - EXTENDED_OFFSET + 1, baseY - EXTENDED_OFFSET + 1,
			wv.getSizeX() + EXTENDED_OFFSET * 2 - 2, wv.getSizeY() + EXTENDED_OFFSET * 2 - 2);

		GeneralPath lines = new GeneralPath(worldShape);
		lines = Geometry.clipPath(lines, extendedScene);
		lines = Geometry.splitIntoSegments(lines, 1);
		// World tile corner -> local coordinate. A tile's centre is (tile - base) * 128 + 64, and
		// its south-west corner (what a shape's coordinates mean here) is half a tile less.
		return Geometry.transformPath(lines, coords ->
		{
			coords[0] = (coords[0] - baseX) * Perspective.LOCAL_TILE_SIZE;
			coords[1] = (coords[1] - baseY) * Perspective.LOCAL_TILE_SIZE;
		});
	}

	/**
	 * How far from the player lines are drawn, in tiles - the GPU plugin's own draw distance
	 * setting while it's active, so lines reach exactly as far as the terrain does.
	 */
	static int drawDistance(Client client, ConfigManager configManager)
	{
		if (!client.isGpu())
		{
			return CPU_DRAW_DISTANCE;
		}

		Integer configured = configManager.getConfiguration(GpuPluginConfig.GROUP, "drawDistance", Integer.class);
		int distance = configured != null ? configured : CPU_DRAW_DISTANCE;
		return Math.max(1, Math.min(distance, Constants.SCENE_SIZE / 2 + EXTENDED_OFFSET));
	}

	/**
	 * Draws local-coordinate lines (from toLocalLines()) within drawDistanceTiles of the local player.
	 */
	static void draw(Graphics2D graphics, Client client, GeneralPath localLines, int drawDistanceTiles)
	{
		Player local = client.getLocalPlayer();
		if (local == null || localLines == null)
		{
			return;
		}

		LocalPoint playerLp = local.getLocalLocation();
		int reach = drawDistanceTiles * Perspective.LOCAL_TILE_SIZE;
		Rectangle viewArea = new Rectangle(playerLp.getX() - reach, playerLp.getY() - reach, reach * 2, reach * 2);

		WorldView wv = client.getTopLevelWorldView();
		int plane = wv.getPlane();

		GeneralPath path = Geometry.clipPath(localLines, viewArea);
		path = Geometry.filterPath(path, (p1, p2) ->
			project(client, wv, p1[0], p1[1], plane) != null && project(client, wv, p2[0], p2[1], plane) != null);
		path = Geometry.transformPath(path, coords ->
		{
			Point point = project(client, wv, coords[0], coords[1], plane);
			coords[0] = point.getX();
			coords[1] = point.getY();
		});

		graphics.draw(path);
	}

	/**
	 * Projects a local ground point to the canvas, including points in the extended scene.
	 */
	static Point project(Client client, WorldView wv, float localX, float localY, int plane)
	{
		int x = (int) localX;
		int y = (int) localY;
		return Perspective.localToCanvas(client, x, y, groundHeight(client, wv, x, y, plane));
	}

	private static int groundHeight(Client client, WorldView wv, int localX, int localY, int plane)
	{
		// Clamp into the loaded scene - see the class doc for why points beyond it borrow the
		// nearest loaded tile's height.
		int maxX = wv.getSizeX() * Perspective.LOCAL_TILE_SIZE - 1;
		int maxY = wv.getSizeY() * Perspective.LOCAL_TILE_SIZE - 1;
		int x = Math.max(0, Math.min(localX, maxX));
		int y = Math.max(0, Math.min(localY, maxY));
		return Perspective.getTileHeight(client, new LocalPoint(x, y, wv), plane);
	}
}
