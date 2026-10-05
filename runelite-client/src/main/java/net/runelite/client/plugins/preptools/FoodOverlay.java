package net.runelite.client.plugins.preptools;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Experimental: an enemy's bites eaten (FoodTracker) over their head, one line below where
 * CallerNameOverlay puts a highlighted name, e.g. "Ate 14 (~7 left)" or, once low,
 * "LOW FOOD (~2 left)". Teammates are never labelled.
 */
public class FoodOverlay extends Overlay
{
	private final Client client;
	private final PrepToolsConfig config;
	private final FoodTracker food;
	private final TeamTracker team;

	@Inject
	private FoodOverlay(Client client, PrepToolsConfig config, FoodTracker food, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.food = food;
		this.team = team;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showFoodCounter())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int lineHeight = graphics.getFontMetrics().getHeight();
		for (Player player : client.getPlayers())
		{
			int bites = food.getBites(player);
			if (bites == 0 || team.isOwnTeam(player))
			{
				continue;
			}
			boolean low = food.isLowOnFood(player);
			if (config.foodShowOnlyLow() && !low)
			{
				continue;
			}

			int left = FoodTracker.pizzasLeft(bites, config.foodAssumedPizzas());
			String text = (low ? "LOW FOOD" : "Ate " + bites) + " (~" + left + " left)";
			Point location = player.getCanvasTextLocation(graphics, text, player.getLogicalHeight() + 40);
			if (location != null)
			{
				OverlayUtil.renderTextLocation(graphics, new Point(location.getX(), location.getY() + lineHeight), text,
					low ? config.foodLowColor() : config.foodCountColor());
			}
		}
		return null;
	}
}
