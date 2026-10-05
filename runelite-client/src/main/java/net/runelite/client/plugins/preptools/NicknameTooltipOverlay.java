package net.runelite.client.plugins.preptools;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;

/**
 * A hovered player's nickname as a tooltip, like Friend Notes' note tooltip - anywhere a menu names
 * the player: in game (the left-click entry), and in the friends/ignore list, friends chat, clan
 * list, chatbox and private chat (the player-name entries those widgets add).
 */
public class NicknameTooltipOverlay extends Overlay
{
	/**
	 * Widget interfaces whose menu entries target a player by name.
	 */
	static final Set<Integer> NAME_LIST_INTERFACES = Set.of(InterfaceID.FRIENDS, InterfaceID.IGNORE,
		InterfaceID.CHATCHANNEL_CURRENT, InterfaceID.CLANS_SIDEPANEL, InterfaceID.CLANS_GUEST_SIDEPANEL,
		InterfaceID.CHATBOX, InterfaceID.PM_CHAT);

	private final Client client;
	private final PrepToolsConfig config;
	private final PlayerNicknameTracker nicknames;
	private final TooltipManager tooltipManager;

	@Inject
	private NicknameTooltipOverlay(Client client, PrepToolsConfig config, PlayerNicknameTracker nicknames,
		TooltipManager tooltipManager)
	{
		this.client = client;
		this.config = config;
		this.nicknames = nicknames;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showPlayerNicknames() || !config.nicknameTooltip() || client.isMenuOpen())
		{
			return null;
		}

		MenuEntry[] entries = client.getMenuEntries();
		if (entries.length == 0)
		{
			return null;
		}

		String nickname = nicknames.nicknameFor(hoveredName(entries[entries.length - 1]));
		if (nickname != null)
		{
			tooltipManager.add(new Tooltip(nickname));
		}
		return null;
	}

	/**
	 * The player the left-click entry is about, or null.
	 */
	static String hoveredName(MenuEntry entry)
	{
		Player player = entry.getPlayer();
		if (player != null)
		{
			return player.getName();
		}
		MenuAction type = entry.getType();
		if ((type == MenuAction.CC_OP || type == MenuAction.CC_OP_LOW_PRIORITY)
			&& NAME_LIST_INTERFACES.contains(WidgetUtil.componentToInterface(entry.getParam1())))
		{
			String target = Text.removeTags(entry.getTarget());
			return target.isEmpty() ? null : Text.toJagexName(target);
		}
		return null;
	}
}
