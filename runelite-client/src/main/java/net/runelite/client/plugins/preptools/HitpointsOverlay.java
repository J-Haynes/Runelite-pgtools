package net.runelite.client.plugins.preptools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.GlyphVector;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Experimental: your hitpoints as a big outlined number with a bar under it, just below your
 * character - in a fight your eyes are on your character and the pile, not the orbs. The colour
 * runs green to red with your hitpoints, and at or below lowHitpointsPercent the whole thing pulses.
 */
public class HitpointsOverlay extends Overlay
{
	private static final long PULSE_PERIOD_MILLIS = 600;

	private final Client client;
	private final PrepToolsConfig config;

	@Inject
	private HitpointsOverlay(Client client, PrepToolsConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player local = client.getLocalPlayer();
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		if (!config.bigHitpoints() || local == null || max <= 0)
		{
			return null;
		}

		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		double fraction = Math.max(0, Math.min(1, (double) hp / max));
		boolean low = hp * 100 <= max * config.lowHitpointsPercent();
		Color color = hitpointsColor(fraction);
		if (low && (System.currentTimeMillis() / (PULSE_PERIOD_MILLIS / 2)) % 2 == 1)
		{
			color = Color.WHITE;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, (float) config.bigHitpointsSize()));
		String text = String.valueOf(hp);
		// At the feet, then pushed down below the character.
		Point feet = local.getCanvasTextLocation(graphics, text, 0);
		if (feet == null)
		{
			return null;
		}
		FontMetrics metrics = graphics.getFontMetrics();
		int x = feet.getX();
		int baseline = feet.getY() + metrics.getAscent();

		// Outlined text: readable on any ground, in any light.
		GlyphVector glyphs = graphics.getFont().createGlyphVector(graphics.getFontRenderContext(), text);
		Shape outline = glyphs.getOutline(x, baseline);
		Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(Math.max(2f, config.bigHitpointsSize() / 8f)));
		graphics.setColor(Color.BLACK);
		graphics.draw(outline);
		graphics.setStroke(oldStroke);
		graphics.setColor(color);
		graphics.fill(outline);

		int barWidth = Math.max(metrics.stringWidth(text), config.bigHitpointsSize() * 2);
		int barHeight = Math.max(4, config.bigHitpointsSize() / 5);
		int barX = x + metrics.stringWidth(text) / 2 - barWidth / 2;
		int barY = baseline + metrics.getDescent() / 2;
		graphics.setColor(new Color(0, 0, 0, 180));
		graphics.fillRect(barX - 1, barY - 1, barWidth + 2, barHeight + 2);
		graphics.setColor(new Color(90, 0, 0));
		graphics.fillRect(barX, barY, barWidth, barHeight);
		graphics.setColor(color);
		graphics.fillRect(barX, barY, (int) Math.round(barWidth * fraction), barHeight);
		return null;
	}

	/**
	 * Green at full, through yellow, to red at none.
	 */
	static Color hitpointsColor(double fraction)
	{
		float hue = (float) (Math.max(0, Math.min(1, fraction)) / 3);
		return Color.getHSBColor(hue, 1f, 1f);
	}
}
