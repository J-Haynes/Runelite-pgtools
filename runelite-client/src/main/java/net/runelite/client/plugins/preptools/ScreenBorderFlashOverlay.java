package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The Attack Alert's flashing border around the whole game screen, when you're attacked or frozen
 * (PrepToolsConfig#borderFlashWhenAttacked()/borderFlashWhenFrozen()). The plugin decides when and
 * in what colour (AttackAlert#getBorderColorNow(), which also handles the blink) - this
 * just draws it, above every interface so a full-screen UI can't hide it.
 */
public class ScreenBorderFlashOverlay extends Overlay
{
	private final AttackAlert attackAlert;
	private final PrepToolsConfig config;
	private final Client client;

	@Inject
	private ScreenBorderFlashOverlay(AttackAlert attackAlert, PrepToolsConfig config, Client client)
	{
		this.attackAlert = attackAlert;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Color color = attackAlert.getBorderColorNow();
		if (color == null)
		{
			return null;
		}

		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();
		int border = Math.min(config.borderFlashWidth(), Math.min(width, height) / 2);

		graphics.setColor(color);
		graphics.fillRect(0, 0, width, border);
		graphics.fillRect(0, height - border, width, border);
		graphics.fillRect(0, border, border, height - 2 * border);
		graphics.fillRect(width - border, border, border, height - 2 * border);
		return null;
	}
}
