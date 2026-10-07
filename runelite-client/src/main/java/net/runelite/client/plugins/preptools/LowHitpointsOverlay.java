package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Experimental: at or below lowHitpointsPercent, the game screen's edges glow red - stronger the
 * lower your hitpoints, pulsing - like a shooter's damage vignette. Drawn under interfaces so it
 * never hides your inventory or the menu.
 */
public class LowHitpointsOverlay extends Overlay
{
	private static final long PULSE_PERIOD_MILLIS = 1200;
	private static final float[] FRACTIONS = {0.45f, 1f};

	private final Client client;
	private final PrepToolsConfig config;

	@Inject
	private LowHitpointsOverlay(Client client, PrepToolsConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int alpha = max > 0 ? edgeAlpha(hp, max, config.lowHitpointsPercent(), System.currentTimeMillis()) : 0;
		if (!config.lowHitpointsVignette() || alpha <= 0)
		{
			return null;
		}

		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();
		Paint old = graphics.getPaint();
		graphics.setPaint(new RadialGradientPaint(new Point2D.Float(width / 2f, height / 2f),
			Math.max(width, height) * 0.75f, FRACTIONS, new Color[]{new Color(255, 0, 0, 0), new Color(255, 0, 0, alpha)},
			MultipleGradientPaint.CycleMethod.NO_CYCLE));
		graphics.fillRect(0, 0, width, height);
		graphics.setPaint(old);
		return null;
	}

	/**
	 * The edges' alpha: 0 above the mark, then from 70 just under it to 200 at 0 hp, pulsing by up to
	 * a third.
	 */
	static int edgeAlpha(int hp, int max, int lowPercent, long nowMillis)
	{
		if (hp * 100 > max * lowPercent || lowPercent <= 0)
		{
			return 0;
		}
		double depth = 1 - (hp * 100.0 / max) / lowPercent;
		double base = 70 + 130 * Math.max(0, Math.min(1, depth));
		double pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * (nowMillis % PULSE_PERIOD_MILLIS) / PULSE_PERIOD_MILLIS);
		return (int) Math.round(base * (1 - pulse / 3));
	}
}
