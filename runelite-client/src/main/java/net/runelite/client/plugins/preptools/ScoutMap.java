package net.runelite.client.plugins.preptools;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ImageUtil;

/**
 * The map pictures Scout attaches - pure drawing, no game state, so it's testable:
 * <ul>
 * <li>drawn: north-up world-map art from mejrs' OSRS map tiles (64x64 game tiles a square, 4 pixels
 * a tile at zoom 2, image top = north), with a dot per player and the Location Mapper's names.</li>
 * <li>minimap: your real minimap cut out of a frame, turned to face north, with the same names.</li>
 * </ul>
 * Either can carry the cape counter (withCapes).
 */
final class ScoutMap
{
	/**
	 * mejrs' map squares: {@code <plane>_<squareX>_<squareY>.png}, 256x256.
	 */
	static final String TILE_URL = "https://raw.githubusercontent.com/mejrs/layers_osrs/master/mapsquares/-1/2/%d_%d_%d.png";
	static final int SQUARE_TILES = 64;
	static final int TILE_PIXELS = 4;

	static final int DRAWN_SIZE = 512;

	/**
	 * The minimap is small in Discord - scaled up this much, pixel for pixel, so dots stay sharp.
	 */
	static final int MINIMAP_SCALE = 2;

	static final Color YOU_COLOR = Color.WHITE;
	static final Color TEAM_COLOR = new Color(60, 220, 60);
	static final Color ENEMY_COLOR = new Color(235, 50, 50);
	private static final Color NO_MAP_COLOR = new Color(30, 30, 30);

	private ScoutMap()
	{
	}

	/**
	 * A player on the drawn map.
	 */
	static final class Dot
	{
		final int x;
		final int y;
		final Color color;

		Dot(int x, int y, Color color)
		{
			this.x = x;
			this.y = y;
			this.color = color;
		}
	}

	/**
	 * The drawn map: radius tiles each way around (centreX, centreY), DRAWN_SIZE square.
	 * tiles(new int[]{plane, squareX, squareY}) gives a map square, or null if there's none. No
	 * names are put under noLabels (where the cape counter will go), if given.
	 */
	static BufferedImage drawn(Function<int[], BufferedImage> tiles, int centreX, int centreY, int plane, int radius,
		List<Dot> dots, List<WildernessLocation> locations, Rectangle noLabels)
	{
		int minX = centreX - radius;
		int maxY = centreY + radius;
		int span = radius * 2 + 1;

		// Native resolution first (4 pixels a tile), then scaled to DRAWN_SIZE.
		BufferedImage raw = new BufferedImage(span * TILE_PIXELS, span * TILE_PIXELS, BufferedImage.TYPE_INT_RGB);
		Graphics2D rg = raw.createGraphics();
		rg.setColor(NO_MAP_COLOR);
		rg.fillRect(0, 0, raw.getWidth(), raw.getHeight());
		for (int sx = Math.floorDiv(minX, SQUARE_TILES); sx <= Math.floorDiv(centreX + radius, SQUARE_TILES); sx++)
		{
			for (int sy = Math.floorDiv(centreY - radius, SQUARE_TILES); sy <= Math.floorDiv(maxY, SQUARE_TILES); sy++)
			{
				BufferedImage tile = tiles.apply(new int[]{plane, sx, sy});
				if (tile != null)
				{
					// The square's north-west corner: x sx*64, y sx*64+63.
					int px = (sx * SQUARE_TILES - minX) * TILE_PIXELS;
					int py = (maxY - (sy * SQUARE_TILES + SQUARE_TILES - 1)) * TILE_PIXELS;
					rg.drawImage(tile, px, py, SQUARE_TILES * TILE_PIXELS, SQUARE_TILES * TILE_PIXELS, null);
				}
			}
		}
		rg.dispose();

		BufferedImage out = new BufferedImage(DRAWN_SIZE, DRAWN_SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(raw, 0, 0, DRAWN_SIZE, DRAWN_SIZE, null);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double perTile = (double) DRAWN_SIZE / span;
		// A tile's centre, on the image.
		Function<double[], double[]> toImage = p -> new double[]{(p[0] - minX + 0.5) * perTile, (maxY - p[1] + 0.5) * perTile};

		List<Label> labels = new ArrayList<>();
		for (WildernessLocation location : locations)
		{
			double cx = (location.minX + location.maxX) / 2.0;
			double cy = (location.minY + location.maxY) / 2.0;
			if (location.plane == plane && Math.abs(cx - centreX) <= radius && Math.abs(cy - centreY) <= radius)
			{
				double[] p = toImage.apply(new double[]{cx, cy});
				labels.add(new Label(location, p[0], p[1]));
			}
		}

		// Names first, so the players' dots sit on top of them.
		drawLabels(g, labels, DRAWN_SIZE, DRAWN_SIZE, noLabels);

		int dotRadius = Math.max(3, (int) Math.round(perTile * 0.6));
		for (Dot dot : dots)
		{
			double[] p = toImage.apply(new double[]{dot.x, dot.y});
			g.setColor(Color.BLACK);
			g.fill(new Ellipse2D.Double(p[0] - dotRadius - 1, p[1] - dotRadius - 1, dotRadius * 2 + 2, dotRadius * 2 + 2));
			g.setColor(dot.color);
			g.fill(new Ellipse2D.Double(p[0] - dotRadius, p[1] - dotRadius, dotRadius * 2, dotRadius * 2));
		}
		g.dispose();
		return out;
	}

	/**
	 * Your minimap cut out of a frame, turned to face north, as a circle (the corners are other
	 * interface), scaled up, with the Location Mapper names in reach. The frame can be bigger than
	 * the canvas (stretched mode), so canvas positions are scaled to it.
	 *
	 * @param bounds the minimap widget on the canvas
	 * @param centreX where you are on the canvas minimap (canvas x)
	 * @param centreY where you are on the canvas minimap (canvas y)
	 * @param rotation radians to turn the minimap so north is up
	 * @param pixelsPerTile minimap zoom when the frame was drawn
	 */
	static BufferedImage minimap(Image frame, Rectangle bounds, int canvasWidth, double centreX, double centreY,
		double rotation, double pixelsPerTile, int worldX, int worldY, int plane, List<WildernessLocation> locations)
	{
		BufferedImage source = frame instanceof BufferedImage ? (BufferedImage) frame : ImageUtil.bufferedImageFromImage(frame);
		double scale = canvasWidth > 0 ? (double) source.getWidth() / canvasWidth : 1;
		int x = (int) Math.round(bounds.x * scale);
		int y = (int) Math.round(bounds.y * scale);
		int w = Math.min((int) Math.round(bounds.width * scale), source.getWidth() - x);
		int h = Math.min((int) Math.round(bounds.height * scale), source.getHeight() - y);
		if (x < 0 || y < 0 || w <= 0 || h <= 0)
		{
			return null;
		}

		double out = MINIMAP_SCALE;
		int outW = w * MINIMAP_SCALE;
		int outH = h * MINIMAP_SCALE;
		// You, on the output image - what it turns around.
		double cx = (centreX * scale - x) * out;
		double cy = (centreY * scale - y) * out;

		BufferedImage result = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = result.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.fill(new Ellipse2D.Double(0, 0, outW, outH));
		g.setComposite(AlphaComposite.SrcIn);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			rotation == 0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		AffineTransform old = g.getTransform();
		g.rotate(rotation, cx, cy);
		g.drawImage(source.getSubimage(x, y, w, h), 0, 0, outW, outH, null);
		g.setTransform(old);
		g.setComposite(AlphaComposite.SrcOver);

		// North up, the minimap is plain: east is right, north is up, pixelsPerTile a tile.
		double perTile = pixelsPerTile * scale * out;
		double radius = Math.min(outW, outH) / 2.0;
		List<Label> labels = new ArrayList<>();
		for (WildernessLocation location : locations)
		{
			double lx = cx + ((location.minX + location.maxX) / 2.0 - worldX) * perTile;
			double ly = cy - ((location.minY + location.maxY) / 2.0 - worldY) * perTile;
			if (location.plane == plane && Math.hypot(lx - outW / 2.0, ly - outH / 2.0) < radius * 0.9)
			{
				labels.add(new Label(location, lx, ly));
			}
		}
		drawLabels(g, labels, outW, outH, null);
		g.dispose();
		return result;
	}

	/**
	 * One row of the cape counter: the cape's item icon (with its count, as the Team Capes overlay
	 * draws it) and the cape's name.
	 */
	static final class Cape
	{
		final BufferedImage icon;
		final String label;

		Cape(BufferedImage icon, String label)
		{
			this.icon = icon;
			this.label = label;
		}
	}

	private static final int CAPE_ROW_HEIGHT = 34;
	private static final int CAPE_ICON_WIDTH = 38;
	private static final int CAPE_PADDING = 6;
	private static final Font CAPE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);

	private static FontMetrics capeMetrics()
	{
		Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		FontMetrics metrics = g.getFontMetrics(CAPE_FONT);
		g.dispose();
		return metrics;
	}

	/**
	 * Where the cape counter goes on a map big enough for it (the top-left corner) - the drawn map
	 * keeps its names out of there. Null with no capes.
	 */
	static Rectangle capePanel(List<Cape> capes)
	{
		if (capes.isEmpty())
		{
			return null;
		}
		FontMetrics metrics = capeMetrics();
		int textWidth = 0;
		for (Cape cape : capes)
		{
			textWidth = Math.max(textWidth, metrics.stringWidth(cape.label));
		}
		return new Rectangle(CAPE_PADDING, CAPE_PADDING, CAPE_PADDING * 2 + CAPE_ICON_WIDTH + 4 + textWidth,
			CAPE_PADDING * 2 + CAPE_ROW_HEIGHT * capes.size());
	}

	/**
	 * The cape counter drawn on the map: in its top-left corner when the map is big enough (the
	 * drawn map), else beside it (the minimap); on its own if there's no map. Null if neither.
	 */
	static BufferedImage withCapes(BufferedImage map, List<Cape> capes)
	{
		if (capes.isEmpty())
		{
			return map;
		}

		FontMetrics metrics = capeMetrics();
		Rectangle panel = capePanel(capes);
		int panelW = panel.width;
		int panelH = panel.height;

		boolean inside = map != null && map.getWidth() >= panelW * 2 && map.getHeight() >= panelH + CAPE_PADDING * 2;
		int panelX = inside ? CAPE_PADDING : map != null ? map.getWidth() + CAPE_PADDING : 0;
		int panelY = inside ? CAPE_PADDING : 0;
		int width = map == null ? panelW : inside ? map.getWidth() : map.getWidth() + CAPE_PADDING + panelW;
		int height = map == null ? panelH : Math.max(map.getHeight(), panelH);

		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		if (map != null)
		{
			g.drawImage(map, 0, 0, null);
		}
		g.setColor(new Color(20, 20, 20, 200));
		g.fillRoundRect(panelX, panelY, panelW, panelH, 8, 8);
		g.setColor(new Color(90, 90, 90));
		g.drawRoundRect(panelX, panelY, panelW - 1, panelH - 1, 8, 8);
		g.setFont(CAPE_FONT);
		int y = panelY + CAPE_PADDING;
		for (Cape cape : capes)
		{
			if (cape.icon != null)
			{
				g.drawImage(cape.icon, panelX + CAPE_PADDING + (CAPE_ICON_WIDTH - cape.icon.getWidth()) / 2,
					y + (CAPE_ROW_HEIGHT - cape.icon.getHeight()) / 2, null);
			}
			g.setColor(Color.WHITE);
			g.drawString(cape.label, panelX + CAPE_PADDING + CAPE_ICON_WIDTH + 4,
				y + (CAPE_ROW_HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2);
			y += CAPE_ROW_HEIGHT;
		}
		g.dispose();
		return out;
	}

	private static final int LEVEL_WIDTH = 270;
	private static final int LEVEL_HEADER_HEIGHT = 26;
	private static final int LEVEL_ROW_HEIGHT = 16;
	private static final int LEVEL_RANGE_WIDTH = 52;
	private static final int LEVEL_COUNT_WIDTH = 26;
	private static final int LEVEL_ICON_WIDTH = 26;
	private static final int LEVEL_ICON_HEIGHT = 23;
	private static final int LEVEL_MIN_COLUMN_HEIGHT = 512;
	private static final Font LEVEL_TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font LEVEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Color LEVEL_BACKGROUND = new Color(30, 30, 30);
	private static final Color LEVEL_CHART_BACKGROUND = new Color(45, 45, 45);
	private static final Color LEVEL_TRACK = new Color(60, 60, 60);
	private static final Color LEVEL_BAR = ColorScheme.BRAND_ORANGE;
	private static final Color LEVEL_MUTED = new Color(160, 160, 160);
	private static final Color LEVEL_EMPTY = new Color(100, 100, 100);

	private static int levelChartHeight(EnemyLevels.Chart chart)
	{
		return CAPE_PADDING * 2 + LEVEL_HEADER_HEIGHT + LEVEL_ROW_HEIGHT * (chart.getRanges().size() + (chart.getOutside() > 0 ? 1 : 0));
	}

	/**
	 * The Capes tab's Enemy Levels charts, drawn to the right of the picture (or on their own if
	 * there's none) - in columns no taller than the picture (at least 512px), so a long list wraps
	 * sideways rather than making a very tall image. icons: team number to its cape's item icon.
	 */
	static BufferedImage withEnemyLevels(BufferedImage picture, List<EnemyLevels.Chart> charts, Map<Integer, BufferedImage> icons)
	{
		if (charts.isEmpty())
		{
			return picture;
		}

		int columnLimit = Math.max(LEVEL_MIN_COLUMN_HEIGHT, picture != null ? picture.getHeight() : 0) - CAPE_PADDING;
		List<List<EnemyLevels.Chart>> columns = new ArrayList<>();
		List<EnemyLevels.Chart> column = new ArrayList<>();
		int columnHeight = CAPE_PADDING;
		int tallest = 0;
		for (EnemyLevels.Chart chart : charts)
		{
			int height = levelChartHeight(chart) + CAPE_PADDING;
			if (!column.isEmpty() && columnHeight + height > columnLimit)
			{
				columns.add(column);
				column = new ArrayList<>();
				columnHeight = CAPE_PADDING;
			}
			column.add(chart);
			columnHeight += height;
			tallest = Math.max(tallest, columnHeight);
		}
		columns.add(column);

		int left = picture != null ? picture.getWidth() : 0;
		int width = left + columns.size() * (LEVEL_WIDTH + CAPE_PADDING) + CAPE_PADDING;
		int height = Math.max(picture != null ? picture.getHeight() : 0, tallest);

		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(LEVEL_BACKGROUND);
		g.fillRect(left, 0, width - left, height);
		if (picture != null)
		{
			g.drawImage(picture, 0, 0, null);
		}

		int x = left + CAPE_PADDING;
		for (List<EnemyLevels.Chart> chartsInColumn : columns)
		{
			int y = CAPE_PADDING;
			for (EnemyLevels.Chart chart : chartsInColumn)
			{
				drawLevelChart(g, chart, icons.get(chart.getTeam()), x, y);
				y += levelChartHeight(chart) + CAPE_PADDING;
			}
			x += LEVEL_WIDTH + CAPE_PADDING;
		}
		g.dispose();
		return out;
	}

	/**
	 * Same layout as the Capes tab: header (icon, title - count, spread on the right), then per
	 * range its label, a bar scaled to the chart's fullest range, and the count.
	 */
	private static void drawLevelChart(Graphics2D g, EnemyLevels.Chart chart, BufferedImage icon, int x, int y)
	{
		int height = levelChartHeight(chart);
		g.setColor(LEVEL_CHART_BACKGROUND);
		g.fillRoundRect(x, y, LEVEL_WIDTH, height, 8, 8);

		int innerX = x + CAPE_PADDING;
		int innerRight = x + LEVEL_WIDTH - CAPE_PADDING;
		int top = y + CAPE_PADDING;
		int titleX = innerX;
		if (icon != null)
		{
			g.drawImage(icon, innerX, top + (LEVEL_HEADER_HEIGHT - LEVEL_ICON_HEIGHT) / 2, LEVEL_ICON_WIDTH, LEVEL_ICON_HEIGHT, null);
			titleX += LEVEL_ICON_WIDTH + 4;
		}
		g.setFont(LEVEL_FONT);
		FontMetrics small = g.getFontMetrics();
		int spreadWidth = small.stringWidth(chart.getSpread());
		g.setColor(LEVEL_MUTED);
		int headerBaseline = top + (LEVEL_HEADER_HEIGHT + small.getAscent() - small.getDescent()) / 2;
		g.drawString(chart.getSpread(), innerRight - spreadWidth, headerBaseline);

		g.setFont(LEVEL_TITLE_FONT);
		FontMetrics bold = g.getFontMetrics();
		g.setColor(Color.WHITE);
		String title = chart.getTitle() + " - " + chart.getTotal();
		int titleRoom = innerRight - spreadWidth - 6 - titleX;
		while (title.length() > 1 && bold.stringWidth(title) > titleRoom)
		{
			title = title.substring(0, title.length() - 1);
		}
		g.drawString(title, titleX, headerBaseline);

		g.setFont(LEVEL_FONT);
		int fullest = chart.fullest();
		int barX = innerX + LEVEL_RANGE_WIDTH;
		int barWidth = innerRight - LEVEL_COUNT_WIDTH - 4 - barX;
		int rowY = top + LEVEL_HEADER_HEIGHT;
		for (int i = 0; i < chart.getRanges().size(); i++)
		{
			int count = chart.getCounts().get(i);
			int baseline = rowY + (LEVEL_ROW_HEIGHT + small.getAscent() - small.getDescent()) / 2;
			g.setColor(count > 0 ? Color.WHITE : LEVEL_EMPTY);
			g.drawString(chart.getRanges().get(i).toString(), innerX, baseline);
			String number = String.valueOf(count);
			g.drawString(number, innerRight - small.stringWidth(number), baseline);

			int barY = rowY + (LEVEL_ROW_HEIGHT - 8) / 2;
			g.setColor(LEVEL_TRACK);
			g.fillRect(barX, barY, barWidth, 8);
			if (count > 0)
			{
				g.setColor(LEVEL_BAR);
				g.fillRect(barX, barY, Math.max(1, Math.round(barWidth * count / (float) fullest)), 8);
			}
			rowY += LEVEL_ROW_HEIGHT;
		}
		if (chart.getOutside() > 0)
		{
			g.setColor(LEVEL_MUTED);
			g.drawString(chart.getOutside() + " outside your ranges", innerX,
				rowY + (LEVEL_ROW_HEIGHT + small.getAscent() - small.getDescent()) / 2);
		}
	}

	private static final class Label
	{
		final WildernessLocation location;
		final double x;
		final double y;

		Label(WildernessLocation location, double x, double y)
		{
			this.location = location;
			this.x = x;
			this.y = y;
		}

		long area()
		{
			return (long) (location.maxX - location.minX + 1) * (location.maxY - location.minY + 1);
		}
	}

	/**
	 * Outlined names centred on their points, the most specific box first (higher priority, then
	 * smaller); a name that would overlap one already drawn is left out.
	 */
	private static void drawLabels(Graphics2D g, List<Label> labels, int width, int height, Rectangle noLabels)
	{
		labels.sort(Comparator.<Label>comparingInt(l -> -l.location.priority).thenComparingLong(Label::area));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		FontMetrics metrics = g.getFontMetrics();
		List<Rectangle> taken = new ArrayList<>();
		if (noLabels != null)
		{
			taken.add(noLabels);
		}
		for (Label label : labels)
		{
			String name = label.location.name;
			if (name == null || name.isEmpty())
			{
				continue;
			}
			int w = metrics.stringWidth(name);
			int x = (int) Math.round(Math.max(2, Math.min(width - w - 2, label.x - w / 2.0)));
			int baseline = (int) Math.round(label.y + (metrics.getAscent() - metrics.getDescent()) / 2.0);
			Rectangle box = new Rectangle(x - 2, baseline - metrics.getAscent() - 1, w + 4, metrics.getHeight() + 2);
			if (box.y < 0 || box.y + box.height > height || taken.stream().anyMatch(box::intersects))
			{
				continue;
			}
			taken.add(box);
			Shape outline = g.getFont().createGlyphVector(g.getFontRenderContext(), name).getOutline(x, baseline);
			g.setStroke(new BasicStroke(3f));
			g.setColor(Color.BLACK);
			g.draw(outline);
			g.setColor(Color.WHITE);
			g.fill(outline);
		}
	}
}
