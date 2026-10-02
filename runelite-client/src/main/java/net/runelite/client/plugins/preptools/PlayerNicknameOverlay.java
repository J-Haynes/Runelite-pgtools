package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Experimental, off by default even with nicknames on (showNicknamesOverHeads): a player's
 * nickname over their head, one line above where CallerNameOverlay puts a highlighted name - or
 * "NOT Nick?" for a lookalike name (PlayerNicknameTracker).
 */
public class PlayerNicknameOverlay extends Overlay
{
	private final PrepToolsConfig config;
	private final PlayerNicknameTracker nicknames;

	@Inject
	private PlayerNicknameOverlay(PrepToolsConfig config, PlayerNicknameTracker nicknames)
	{
		this.config = config;
		this.nicknames = nicknames;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showPlayerNicknames() || !config.showNicknamesOverHeads())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int lineHeight = graphics.getFontMetrics().getHeight();
		for (Map.Entry<Player, String> entry : nicknames.getNicknames().entrySet())
		{
			draw(graphics, entry.getKey(), entry.getValue(), config.playerNicknameColor(), lineHeight);
		}
		for (Map.Entry<Player, String> entry : nicknames.getLookalikes().entrySet())
		{
			draw(graphics, entry.getKey(), lookalikeText(entry.getValue()), config.lookalikeNameColor(), lineHeight);
		}
		return null;
	}

	static String lookalikeText(String nickname)
	{
		return "NOT " + nickname + "?";
	}

	private static void draw(Graphics2D graphics, Player player, String text, Color color, int lineHeight)
	{
		Point location = player.getCanvasTextLocation(graphics, text, player.getLogicalHeight() + 40);
		if (location != null)
		{
			OverlayUtil.renderTextLocation(graphics, new Point(location.getX(), location.getY() - lineHeight), text, color);
		}
	}
}
