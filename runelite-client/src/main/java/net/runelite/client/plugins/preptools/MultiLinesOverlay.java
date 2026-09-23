/*
 * Multi-combat area data and the line-building approach here are adapted from two plugins, both
 * under the BSD 2-Clause License:
 * - Multi-Lines (https://github.com/tsbreuer/Multi-Lines, geoJSON branch) - every NON-wilderness
 *   area, bundled as resources/.../preptools/multi_lines.json.
 * - Wilderness Lines (https://github.com/Nightfirecat/plugin-hub-plugins, wilderness-lines branch,
 *   v1.5.0) - every wilderness area (WILDERNESS_MULTI_AREAS below) and the level 20/30 lines
 *   (TWENTY_LINES/THIRTY_LINES below).
 *
 * Copyright (c) 2018, Woox <https://github.com/wooxsolo>
 * Copyright (c) 2020-2021, Jordan Atwood <nightfirecat@protonmail.com>
 * Copyright (c) 2022, Jordan (tsbreuer)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.Shape;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.util.Arrays;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldView;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the borders of multi-combat areas (and optionally the dragon spear range around them) on
 * the ground - the Multi-Lines plugin, folded into PvP Tools.
 *
 * Differences from upstream Multi-Lines:
 * - Wilderness areas come from Wilderness Lines, not Multi-Lines. Multi-Lines deliberately skips
 *   its own (older, unmaintained, and visibly wrong in places) wilderness entries and tells you to
 *   run Wilderness Lines alongside it - so its "Wilderness": true JSON entries are ignored here and
 *   Wilderness Lines' actively-maintained rectangles are used instead, with its own spear range.
 *   Like Wilderness Lines, only the surface wilderness is drawn (its underground rectangles exist
 *   upstream only for its "unexpected multi area" detector and are filtered out of its drawing).
 * - The data is bundled rather than downloaded from GitHub at login, so nothing leaves the client
 *   and it works offline - at the cost of only updating with this fork.
 * - Lines are drawn out to the GPU plugin's draw distance over the extended scene - see WorldLines.
 *
 * Pipeline: the union of every area's rectangles is built once, off the client thread (load()); on
 * every scene load it's clipped and converted to local coordinates (rebuildSceneLines()); render()
 * then just projects those segments to the canvas.
 */
@Slf4j
public class MultiLinesOverlay extends Overlay
{
	private static final String DATA_RESOURCE = "multi_lines.json";

	// Each upstream's own spear range: Multi-Lines uses 4, Wilderness Lines 5.
	private static final int SPEAR_RANGE = 4;
	private static final int WILDERNESS_SPEAR_RANGE = 5;

	/**
	 * Wilderness Lines' surface-wilderness bounds - its drawn areas are intersected with this.
	 */
	private static final Rectangle WILDERNESS = new Rectangle(2941, 3522, 528, 471);

	/**
	 * Copied verbatim from Wilderness Lines v1.5.0 - the boundary between wilderness levels 20/21
	 * and 30/31 (a 20 line is where you stop being able to teleport with most items, 30 for the
	 * rest), overworld plus the stretches of it that run through the rev caves and slayer caves.
	 */
	private static final Line2D[] TWENTY_LINES = {
		// overworld
		new Line2D.Float(2946, 3680, 3384, 3680),

		// rev caves
		new Line2D.Float(3202, 10080, 3205, 10080),
		new Line2D.Float(3216, 10080, 3224, 10080),
		new Line2D.Float(3228, 10080, 3230, 10080),
		new Line2D.Float(3234, 10080, 3245, 10080),

		// wilderness slayer caves
		new Line2D.Float(3335, 10080, 3344, 10080),
		new Line2D.Float(3349, 10080, 3367, 10080),
		new Line2D.Float(3381, 10080, 3385, 10080),
		new Line2D.Float(3394, 10080, 3397, 10080),
		new Line2D.Float(3410, 10080, 3416, 10080),
		new Line2D.Float(3436, 10080, 3449, 10080),
	};
	private static final Line2D[] THIRTY_LINES = {
		// overworld
		new Line2D.Float(2946, 3760, 3375, 3760),

		// rev caves
		new Line2D.Float(3164, 10160, 3185, 10160),
		new Line2D.Float(3194, 10160, 3221, 10160),
		new Line2D.Float(3235, 10160, 3255, 10160),

		// wilderness slayer caves
		new Line2D.Float(3333, 10160, 3349, 10160),
		new Line2D.Float(3356, 10160, 3368, 10160),
		new Line2D.Float(3421, 10160, 3428, 10160),
	};

	/**
	 * Copied verbatim (comments included) from Wilderness Lines v1.5.0's WILDERNESS_MULTI_AREAS.
	 */
	private static final List<Rectangle> WILDERNESS_MULTI_AREAS = Arrays.asList(
		new Rectangle(3008, 3600, 64, 112), // Dark warrior's palace
		new Rectangle(3072, 3654, 1, 2), // Two tiles next to southern rev caves entrance which used to be a BH "singles" lure spot
		new Rectangle(2946, 3816, 14, 16), // Chaos altar
		new Rectangle(2984, 3912, 24, 16), // Balance crossing to wilderness agility course
		new Rectangle(3008, 3856, 40, 48), // North of kbd entrance
		new Rectangle(3021, 3855, 2, 1), // Two tiles NE of kbd entrance cage
		new Rectangle(3048, 3896, 24, 8), // North of rune rocks
		new Rectangle(3072, 3880, 64, 24), // North of lava maze
		new Rectangle(3112, 3872, 24, 8), // Northeast of lava maze
		new Rectangle(3136, 3840, 256, 64), // Northeast f2p wilderness
		new Rectangle(3200, 3904, 192, 64), // Northeast p2p wilderness
		new Rectangle(3152, 3752, 176, 88), // North-mid east f2p wilderness
		new Rectangle(3192, 3525, 136, 227), // East f2p wilderness
		new Rectangle(3328, 3525, 17, 8), // Small east f2p wilderness strip NE of lumberyard
		new Rectangle(3191, 3689, 1, 1), // One silly tile that used to be a BH "singles" lure spot
		new Rectangle(3136, 3525, 56, 59), // Wilderness north of Grand Exchange
		new Rectangle(3152, 3584, 40, 36), // SE of Ferox 1
		new Rectangle(3146, 3598, 6, 22), // SE of Ferox 2
		new Rectangle(3147, 3596, 5, 2), // SE of Ferox 2 extension 1
		new Rectangle(3149, 3595, 3, 1), // SE of Ferox 2 extension 2
		new Rectangle(3150, 3594, 2, 1), // SE of Ferox 2 extension 3
		new Rectangle(3151, 3593, 1, 1), // SE of Ferox 2 extension 4
		new Rectangle(3152, 3620, 10, 6), // SE of Ferox 3
		new Rectangle(3187, 3620, 5, 28), // East of Ferox 1
		new Rectangle(3179, 3636, 8, 4), // East of Ferox 2, south of bridge
		new Rectangle(3176, 3640, 11, 8), // East of Ferox 2, bridge and north of bridge
		new Rectangle(3174, 3647, 2, 1), // Two dumb tiles north of bridge east of Ferox
		new Rectangle(3264, 10048, 192, 320), // Slayer dungeon, Vet'ion, Venenatis, Callisto, and escape caves
		new Rectangle(3218, 10330, 31, 24), // Scorpia's cave
		new Rectangle(3008, 10112, 64, 28), // Wilderness God Wars Dungeon south portion
		new Rectangle(3008, 10140, 51, 12), // Wilderness God Wars Dungeon middle portion
		new Rectangle(3008, 10152, 39, 24) // Wilderness God Wars Dungeon north portion
	);

	private final Client client;
	private final ClientThread clientThread;
	private final PrepToolsConfig config;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;
	private final Gson gson;

	// World-coordinate shapes, built once by load(). Null until then. Wilderness and everywhere
	// else are kept apart so "Show outside the wilderness" can skip the latter entirely.
	private volatile Area wildernessMultiArea;
	private volatile Area wildernessSpearArea;
	private volatile Area otherMultiArea;
	private volatile Area otherSpearArea;

	// Local-coordinate paths for the currently loaded scene, rebuilt by rebuildSceneLines().
	private volatile GeneralPath wildernessMultiLines;
	private volatile GeneralPath wildernessSpearLines;
	private volatile GeneralPath otherMultiLines;
	private volatile GeneralPath otherSpearLines;
	private volatile GeneralPath twentyLines;
	private volatile GeneralPath thirtyLines;

	@Inject
	private MultiLinesOverlay(Client client, ClientThread clientThread, PrepToolsConfig config,
		ConfigManager configManager, ScheduledExecutorService executor, Gson gson)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.configManager = configManager;
		this.executor = executor;
		this.gson = gson;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/**
	 * Parses the bundled area data and builds the multi/spear shapes on a background thread (a
	 * union of ~1200 rectangles is too slow to do on the client thread without a visible hitch),
	 * then schedules a scene rebuild once it's ready.
	 */
	void load()
	{
		if (wildernessMultiArea != null)
		{
			return;
		}

		executor.execute(() ->
		{
			try
			{
				Area otherMulti = new Area();
				Area otherSpear = new Area();
				addAreas(readRectangles(), SPEAR_RANGE, otherMulti, otherSpear);

				Area wildernessMulti = new Area();
				Area wildernessSpear = new Area();
				addAreas(WILDERNESS_MULTI_AREAS, WILDERNESS_SPEAR_RANGE, wildernessMulti, wildernessSpear);
				wildernessMulti.intersect(new Area(WILDERNESS));
				wildernessSpear.intersect(new Area(WILDERNESS));

				otherMultiArea = otherMulti;
				otherSpearArea = otherSpear;
				wildernessSpearArea = wildernessSpear;
				// Assigned last - it's the "loaded" flag load()/rebuildSceneLines() check.
				wildernessMultiArea = wildernessMulti;
				clientThread.invokeLater(this::rebuildSceneLines);
			}
			catch (RuntimeException e)
			{
				log.warn("PvP Tools: failed to load multi lines data", e);
			}
		});
	}

	private static void addAreas(List<Rectangle> rectangles, int spearRange, Area multi, Area spear)
	{
		for (Rectangle rectangle : rectangles)
		{
			multi.add(new Area(rectangle));
			// Same diamond-ish spear range shape as both upstreams: grow by (range - i, i).
			for (int i = 0; i <= spearRange; i++)
			{
				Rectangle grown = new Rectangle(rectangle);
				grown.grow(spearRange - i, i);
				spear.add(new Area(grown));
			}
		}
	}

	void clear()
	{
		wildernessMultiLines = null;
		wildernessSpearLines = null;
		otherMultiLines = null;
		otherSpearLines = null;
		twentyLines = null;
		thirtyLines = null;
	}

	private List<Rectangle> readRectangles()
	{
		List<Rectangle> rectangles = new ArrayList<>();
		InputStream in = MultiLinesOverlay.class.getResourceAsStream(DATA_RESOURCE);
		if (in == null)
		{
			log.warn("PvP Tools: {} resource missing", DATA_RESOURCE);
			return rectangles;
		}

		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			JsonObject root = gson.fromJson(reader, JsonObject.class);
			JsonArray areas = root.getAsJsonObject("MultiLines").getAsJsonArray("Areas");
			for (JsonElement element : areas)
			{
				JsonObject area = element.getAsJsonObject();
				if (area.get("Removed").getAsBoolean())
				{
					continue;
				}

				// Wilderness entries come from WILDERNESS_MULTI_AREAS instead - see the class doc.
				if (area.get("Wilderness").getAsBoolean() || !area.get("Enabled").getAsBoolean())
				{
					continue;
				}

				for (JsonElement tileElement : area.getAsJsonArray("Tiles"))
				{
					JsonObject tile = tileElement.getAsJsonObject();
					rectangles.add(new Rectangle(
						tile.get("x").getAsInt(),
						tile.get("y").getAsInt(),
						tile.get("width").getAsInt(),
						tile.get("height").getAsInt()));
				}
			}
		}
		catch (java.io.IOException e)
		{
			log.warn("PvP Tools: failed to read {}", DATA_RESOURCE, e);
		}

		return rectangles;
	}

	/**
	 * Clips every shape to the currently loaded (extended) scene. Must run on the client thread.
	 * Cheap enough to always rebuild all of them, regardless of which are currently switched on -
	 * so toggling a setting never needs a rebuild.
	 */
	void rebuildSceneLines()
	{
		if (wildernessMultiArea == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		wildernessMultiLines = WorldLines.toLocalLines(wildernessMultiArea, wv);
		wildernessSpearLines = WorldLines.toLocalLines(wildernessSpearArea, wv);
		otherMultiLines = WorldLines.toLocalLines(otherMultiArea, wv);
		otherSpearLines = WorldLines.toLocalLines(otherSpearArea, wv);
		twentyLines = toLocalLines(TWENTY_LINES, wv);
		thirtyLines = toLocalLines(THIRTY_LINES, wv);
	}

	private static GeneralPath toLocalLines(Shape[] shapes, WorldView wv)
	{
		GeneralPath paths = new GeneralPath();
		for (Shape shape : shapes)
		{
			paths.append(WorldLines.toLocalLines(shape, wv), false);
		}
		return paths;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		boolean multi = config.showMultiLines();
		boolean spear = config.showSpearLines();
		boolean outside = config.showMultiLinesOutsideWilderness();
		if (!multi && !spear && !config.showTwentyLine() && !config.showThirtyLine())
		{
			return null;
		}

		int drawDistance = WorldLines.drawDistance(client, configManager);
		graphics.setStroke(new BasicStroke(1));

		// Spear first so the multi line itself draws on top where they touch.
		if (spear)
		{
			draw(graphics, wildernessSpearLines, config.spearLinesColor(), drawDistance);
			if (outside)
			{
				draw(graphics, otherSpearLines, config.spearLinesColor(), drawDistance);
			}
		}
		if (multi)
		{
			draw(graphics, wildernessMultiLines, config.multiLinesColor(), drawDistance);
			if (outside)
			{
				draw(graphics, otherMultiLines, config.multiLinesColor(), drawDistance);
			}
		}
		if (config.showTwentyLine())
		{
			draw(graphics, twentyLines, config.twentyLineColor(), drawDistance);
		}
		if (config.showThirtyLine())
		{
			draw(graphics, thirtyLines, config.thirtyLineColor(), drawDistance);
		}
		return null;
	}

	private void draw(Graphics2D graphics, GeneralPath lines, Color color, int drawDistance)
	{
		if (lines != null)
		{
			graphics.setColor(color);
			WorldLines.draw(graphics, client, lines, drawDistance);
		}
	}
}
