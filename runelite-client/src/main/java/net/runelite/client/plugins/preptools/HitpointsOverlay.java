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
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Experimental: your hitpoints as a big outlined number with a bar under it, just below your
 * character - in a fight your eyes are on your character and the pile, not the orbs. The colour
 * runs green to red with your hitpoints, and at or below lowHitpointsPercent the whole thing pulses.
 * Optionally (bigHitpointsAttackers) the infobox's "Attacking you" icons go under the bar: a weapon
 * icon and count per style of enemy interacting with you, from FightInfoTracker.
 */
public class HitpointsOverlay extends Overlay
{
	private static final long PULSE_PERIOD_MILLIS = 600;
	private static final int ATTACKER_ICON_SIZE = 32;
	private static final int ATTACKER_GAP = 8;

	private final Client client;
	private final PrepToolsConfig config;
	private final FightInfoTracker fightInfo;
	private final ItemManager itemManager;

	@Inject
	private HitpointsOverlay(Client client, PrepToolsConfig config, FightInfoTracker fightInfo, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.fightInfo = fightInfo;
		this.itemManager = itemManager;
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

		GlyphVector glyphs = graphics.getFont().createGlyphVector(graphics.getFontRenderContext(), text);
		drawOutlined(graphics, glyphs.getOutline(x, baseline), color, Math.max(2f, config.bigHitpointsSize() / 8f));

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

		if (config.bigHitpointsAttackers())
		{
			drawAttackers(graphics, barX + barWidth / 2, barY + barHeight + 4);
		}
		return null;
	}

	/**
	 * A weapon icon and "xN" per style attacking you, centred under the bar. Nothing if no one is.
	 */
	private void drawAttackers(Graphics2D graphics, int centreX, int top)
	{
		int[][] styles = {
			{FightInfoOverlay.MELEE_WEAPON_ICON_ITEM_ID, fightInfo.getMeleeAttackerCount()},
			{FightInfoOverlay.RANGED_WEAPON_ICON_ITEM_ID, fightInfo.getRangedAttackerCount()},
			{FightInfoOverlay.MAGIC_WEAPON_ICON_ITEM_ID, fightInfo.getMagicAttackerCount()},
		};
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 16f));
		FontMetrics metrics = graphics.getFontMetrics();

		int width = 0;
		for (int[] style : styles)
		{
			if (style[1] > 0)
			{
				width += (width > 0 ? ATTACKER_GAP : 0) + ATTACKER_ICON_SIZE + metrics.stringWidth("x" + style[1]);
			}
		}
		if (width == 0)
		{
			return;
		}

		int x = centreX - width / 2;
		int textBaseline = top + (ATTACKER_ICON_SIZE + metrics.getAscent() - metrics.getDescent()) / 2;
		for (int[] style : styles)
		{
			if (style[1] <= 0)
			{
				continue;
			}
			BufferedImage icon = itemManager.getImage(style[0]);
			if (icon != null)
			{
				float scale = Math.min(1f, (float) ATTACKER_ICON_SIZE / Math.max(icon.getWidth(), icon.getHeight()));
				int w = Math.round(icon.getWidth() * scale);
				int h = Math.round(icon.getHeight() * scale);
				graphics.drawImage(icon, x + (ATTACKER_ICON_SIZE - w) / 2, top + (ATTACKER_ICON_SIZE - h) / 2, w, h, null);
			}
			x += ATTACKER_ICON_SIZE;
			String count = "x" + style[1];
			GlyphVector glyphs = graphics.getFont().createGlyphVector(graphics.getFontRenderContext(), count);
			drawOutlined(graphics, glyphs.getOutline(x, textBaseline), Color.WHITE, 3f);
			x += metrics.stringWidth(count) + ATTACKER_GAP;
		}
	}

	/**
	 * Outlined text: readable on any ground, in any light.
	 */
	private static void drawOutlined(Graphics2D graphics, Shape outline, Color color, float outlineWidth)
	{
		Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(outlineWidth));
		graphics.setColor(Color.BLACK);
		graphics.draw(outline);
		graphics.setStroke(oldStroke);
		graphics.setColor(color);
		graphics.fill(outline);
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
