package net.runelite.client.plugins.preptools;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Bottom-right fight info panel. Sections, in order:
 * 1) "Your Stats" (each stat individually toggleable, off by default) - hitpoints (current/max,
 *    flashing red below a configurable percentage), prayer points, strength level, ranged level,
 *    all drawn inline on one row rather than one section per stat.
 * 2) Your freeze status (optional, on by default) - a faded placeholder icon while unfrozen, or
 *    the active spell's own icon and countdown while frozen. Reuses PrepToolsPlugin's FreezeState
 *    tracking and PrepToolsConfig's freeze colour/format settings, so it always agrees with
 *    FreezeOverlay's in-world countdown for the same freeze.
 * 3) Your current server-assigned player index (optional, off by default) - see
 *    drawPlayerIndexRow()'s doc for what this is and, importantly, isn't confirmed to do.
 * 4) The primary caller's target's active overhead prayer icon (optional, off by default).
 * 5) Your current wilderness location (optional, on by default).
 * 6) Opponents' overhead protection prayers (melee/ranged/magic), aggregated across every
 *    player currently on screen who is NOT in your friends chat.
 * 7) Opponents' weapons, same population, same melee/ranged/magic categorisation as the "attacking
 *    you" section below.
 * 8) Weapons of whoever is currently interacting with (attacking) YOU specifically. This row
 *    always reserves its space, even when empty, and can flash briefly when someone new engages you.
 *
 * Rendering approach: content is first drawn onto an offscreen buffer so the exact pixel bounds
 * are known, then an opaque background is painted at those exact bounds before compositing the
 * buffer on top. This avoids an oversized or mismatched background rectangle.
 *
 * In compact mode (no section labels), each row instead gets a thin coloured accent stripe on its
 * left edge, so the sections stay easy to tell apart at a glance without needing the text labels -
 * see drawAccentStripe()'s doc for the colour reasoning.
 */
public class FightInfoOverlay extends Overlay
{
	private static final int MELEE_WEAPON_ICON_ITEM_ID = 1333; // Rune scimitar
	private static final int RANGED_WEAPON_ICON_ITEM_ID = 853;  // Maple shortbow
	private static final int MAGIC_WEAPON_ICON_ITEM_ID = 1381;  // Staff of air

	// Real overhead protection prayer sprite IDs, verified against RuneLite's SpriteID source.
	private static final int SPRITE_PROTECT_MELEE = SpriteID.PRAYER_PROTECT_FROM_MELEE;
	private static final int SPRITE_PROTECT_RANGED = SpriteID.PRAYER_PROTECT_FROM_MISSILES;
	private static final int SPRITE_PROTECT_MAGIC = SpriteID.PRAYER_PROTECT_FROM_MAGIC;

	// Every icon (sprite or item) is drawn centred inside a fixed square slot, preserving its
	// native aspect ratio, rather than being force-stretched - this is what fixed the scaling issue.
	private static final int ICON_SLOT_SIZE = 26;
	private static final int ROW_HEIGHT = 30;

	private static final int PADDING = 8;
	private static final int SECTION_GAP = 10;
	private static final int LABEL_HEIGHT = 14;
	private static final int PANEL_MIN_WIDTH = 150;

	// Compact mode uses tighter spacing and skips section labels entirely.
	private static final int COMPACT_PADDING = 5;
	private static final int COMPACT_ROW_GAP = 4;
	private static final int COMPACT_MIN_WIDTH = 90;

	private static final long FLASH_BLINK_PERIOD_MILLIS = 200;

	// Subtle - just enough to break up sections visually without drawing attention to itself.
	private static final Color SEPARATOR_COLOR = new Color(255, 255, 255, 35);

	// Compact-mode-only accent stripe, drawn on the left edge of each row in place of a text
	// label - see drawAccentStripe()'s doc for what each colour means.
	private static final int ACCENT_STRIPE_WIDTH = 3;
	private static final Color SELF_ACCENT_COLOR = Color.WHITE;
	private static final Color OPPONENT_ACCENT_COLOR = new Color(200, 70, 70);

	// Generous offscreen buffer size - just needs to comfortably fit the panel; unused area stays
	// transparent. BUG FIX: this was 320x220, sized back when the panel only had three possible
	// sections (opponent prayers/weapons/attacking you, ~190px tall at most). With hitpoints,
	// freeze status and target's prayer added on top, every section enabled at once in normal
	// (non-compact) mode needs ~330px - taller than the old buffer, so content past 220px was
	// silently clipped by the fixed-size BufferedImage below. Sized here with real headroom above
	// that ~330px worst case (now ~380px wide too, since "Your Stats" can draw up to four stats
	// inline on one row) so the panel doesn't break again the next time a section is added.
	private static final int BUFFER_WIDTH = 380;
	private static final int BUFFER_HEIGHT = 420;

	private final PrepToolsPlugin plugin;
	private final PrepToolsConfig config;
	private final Client client;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;

	// Simple cache so we only request each sprite from SpriteManager once.
	private final Map<Integer, BufferedImage> spriteCache = new HashMap<>();

	// PERFORMANCE: this pair used to be a `new BufferedImage(BUFFER_WIDTH, BUFFER_HEIGHT, ...)` +
	// createGraphics() allocated fresh on every single render() call (every frame, ~every 16ms) -
	// a ~571KB (340*420*4 bytes) heap allocation roughly 60 times a second whenever the overlay was
	// enabled, purely to be thrown away and GC'd moments later. Allocated once, lazily, on first
	// render instead, and reused every frame after - see render()'s clearBuffer() call below.
	private BufferedImage buffer;
	private Graphics2D bufferGraphics;

	@Inject
	private FightInfoOverlay(PrepToolsPlugin plugin, PrepToolsConfig config, Client client, ItemManager itemManager, SpriteManager spriteManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showFightInfoOverlay())
		{
			return null;
		}

		if (buffer == null)
		{
			buffer = new BufferedImage(BUFFER_WIDTH, BUFFER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
			bufferGraphics = buffer.createGraphics();
			bufferGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			bufferGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		}

		clearBuffer();

		Dimension contentSize = renderContent(bufferGraphics);
		if (contentSize == null)
		{
			return null;
		}

		// Opaque background sized to the exact measured content, then the pre-rendered content on top.
		graphics.setColor(config.overlayBackgroundColor());
		graphics.fillRoundRect(0, 0, contentSize.width, contentSize.height, 6, 6);
		graphics.drawImage(buffer, 0, 0, null);

		return contentSize;
	}

	/**
	 * Wipes the shared buffer back to fully transparent before drawing this frame's content into
	 * it - cheaper than reallocating a new BufferedImage every frame, and behaviourally identical
	 * (the buffer is ARGB and last frame's content would otherwise still be sitting underneath).
	 */
	private void clearBuffer()
	{
		Composite previousComposite = bufferGraphics.getComposite();
		bufferGraphics.setComposite(AlphaComposite.Clear);
		bufferGraphics.fillRect(0, 0, BUFFER_WIDTH, BUFFER_HEIGHT);
		bufferGraphics.setComposite(previousComposite);
	}

	private Dimension renderContent(Graphics2D graphics)
	{
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 14f));

		if (config.compactMode())
		{
			return renderCompact(graphics);
		}

		return renderNormal(graphics);
	}

	private Dimension renderNormal(Graphics2D graphics)
	{
		boolean prayersEnabled = config.showOpponentPrayers();
		boolean weaponsEnabled = config.showOpponentWeapons();
		boolean attackingEnabled = config.showAttackingYou();
		boolean statsEnabled = config.showPlayerHitpoints() || config.showPlayerPrayer()
			|| config.showPlayerStrength() || config.showPlayerRanged();
		boolean freezeStatusEnabled = config.showFreezeStatus();
		boolean targetPrayerEnabled = config.showTargetPrayer();
		boolean locationEnabled = config.showLocationInInfobox();
		boolean playerIndexEnabled = config.showPlayerIndex();

		if (!prayersEnabled && !weaponsEnabled && !attackingEnabled && !statsEnabled
			&& !freezeStatusEnabled && !targetPrayerEnabled && !locationEnabled && !playerIndexEnabled)
		{
			return null;
		}

		int y = PADDING;
		int maxWidth = 0;
		boolean anySectionDrawn = false;

		if (statsEnabled)
		{
			drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));
			drawLabel(graphics, "Your Stats", PADDING, y);
			y += LABEL_HEIGHT;

			maxWidth = Math.max(maxWidth, PADDING + drawPlayerStatsRow(graphics, PADDING, y));
			y += ROW_HEIGHT + SECTION_GAP;
			anySectionDrawn = true;
		}

		if (freezeStatusEnabled)
		{
			drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));
			drawLabel(graphics, "Freeze Status", PADDING, y);
			y += LABEL_HEIGHT;

			maxWidth = Math.max(maxWidth, PADDING + drawFreezeStatusRow(graphics, PADDING, y));
			y += ROW_HEIGHT + SECTION_GAP;
			anySectionDrawn = true;
		}

		if (playerIndexEnabled)
		{
			drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));
			drawLabel(graphics, "Player Index", PADDING, y);
			y += LABEL_HEIGHT;

			maxWidth = Math.max(maxWidth, PADDING + drawPlayerIndexRow(graphics, PADDING, y));
			y += ROW_HEIGHT + SECTION_GAP;
			anySectionDrawn = true;
		}

		if (targetPrayerEnabled)
		{
			drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));
			drawLabel(graphics, "Target's Prayer", PADDING, y);
			y += LABEL_HEIGHT;

			maxWidth = Math.max(maxWidth, PADDING + drawTargetPrayerRow(graphics, PADDING, y));
			y += ROW_HEIGHT + SECTION_GAP;
			anySectionDrawn = true;
		}

		if (locationEnabled)
		{
			drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));
			drawLabel(graphics, "Location", PADDING, y);
			y += LABEL_HEIGHT;

			maxWidth = Math.max(maxWidth, PADDING + drawLocationRow(graphics, PADDING, y));
			y += ROW_HEIGHT + SECTION_GAP;
			anySectionDrawn = true;
		}

		boolean hasOpponents = !plugin.getCurrentOpponents().isEmpty();

		if (prayersEnabled && hasOpponents)
		{
			drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));
			drawLabel(graphics, "Opponents' Prayers", PADDING, y);
			y += LABEL_HEIGHT;

			int x = PADDING;

			x += drawSpriteIcon(graphics, SPRITE_PROTECT_MELEE, plugin.getOpponentMeleePrayerCount(), x, y);
			x += drawSpriteIcon(graphics, SPRITE_PROTECT_RANGED, plugin.getOpponentRangedPrayerCount(), x, y);
			x += drawSpriteIcon(graphics, SPRITE_PROTECT_MAGIC, plugin.getOpponentMagicPrayerCount(), x, y);

			maxWidth = Math.max(maxWidth, x);
			y += ROW_HEIGHT + SECTION_GAP;
			anySectionDrawn = true;
		}

		if (weaponsEnabled && hasOpponents)
		{
			boolean anyWeapons = plugin.getOpponentMeleeWeaponCount() > 0 || plugin.getOpponentRangedWeaponCount() > 0
				|| plugin.getOpponentMagicWeaponCount() > 0;
			if (anyWeapons)
			{
				drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));
				drawLabel(graphics, "Opponents' Weapons", PADDING, y);
				y += LABEL_HEIGHT;

				int x = PADDING;

				if (plugin.getOpponentMeleeWeaponCount() > 0)
				{
					x += drawItemIcon(graphics, MELEE_WEAPON_ICON_ITEM_ID, plugin.getOpponentMeleeWeaponCount(), x, y);
				}
				if (plugin.getOpponentRangedWeaponCount() > 0)
				{
					x += drawItemIcon(graphics, RANGED_WEAPON_ICON_ITEM_ID, plugin.getOpponentRangedWeaponCount(), x, y);
				}
				if (plugin.getOpponentMagicWeaponCount() > 0)
				{
					x += drawItemIcon(graphics, MAGIC_WEAPON_ICON_ITEM_ID, plugin.getOpponentMagicWeaponCount(), x, y);
				}

				maxWidth = Math.max(maxWidth, x);
				y += ROW_HEIGHT + SECTION_GAP;
				anySectionDrawn = true;
			}
		}

		if (attackingEnabled)
		{
			// "Attacking You" section - ALWAYS shown when enabled, space always reserved, even when empty.
			boolean blinkOn = plugin.isFlashingAttacker() && (System.currentTimeMillis() / FLASH_BLINK_PERIOD_MILLIS) % 2 == 0;

			drawSeparatorIfNeeded(graphics, anySectionDrawn, y, Math.max(maxWidth, PANEL_MIN_WIDTH));

			int sectionTop = y;
			int sectionHeight = LABEL_HEIGHT + ROW_HEIGHT;
			int sectionWidth = Math.max(maxWidth, PANEL_MIN_WIDTH);

			if (blinkOn)
			{
				Color flashColor = config.attackerFlashColor();
				graphics.setColor(new Color(flashColor.getRed(), flashColor.getGreen(), flashColor.getBlue(), 110));
				graphics.fillRect(0, sectionTop - 2, sectionWidth + PADDING, sectionHeight + 4);
			}

			drawLabel(graphics, "Attacking You", PADDING, y);
			y += LABEL_HEIGHT;

			int x = PADDING;

			if (plugin.getMeleeAttackerCount() == 0 && plugin.getRangedAttackerCount() == 0 && plugin.getMagicAttackerCount() == 0)
			{
				graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 13f));
				graphics.setColor(Color.LIGHT_GRAY);
				graphics.drawString("None", x, y + (ROW_HEIGHT / 2) + 5);
				graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 14f));
			}
			else
			{
				if (plugin.getMeleeAttackerCount() > 0)
				{
					x += drawItemIcon(graphics, MELEE_WEAPON_ICON_ITEM_ID, plugin.getMeleeAttackerCount(), x, y);
				}
				if (plugin.getRangedAttackerCount() > 0)
				{
					x += drawItemIcon(graphics, RANGED_WEAPON_ICON_ITEM_ID, plugin.getRangedAttackerCount(), x, y);
				}
				if (plugin.getMagicAttackerCount() > 0)
				{
					x += drawItemIcon(graphics, MAGIC_WEAPON_ICON_ITEM_ID, plugin.getMagicAttackerCount(), x, y);
				}
			}

			maxWidth = Math.max(maxWidth, Math.max(x, sectionWidth));
			y += ROW_HEIGHT;
		}

		return new Dimension(maxWidth + PADDING, y + PADDING);
	}

	/**
	 * Draws a subtle horizontal divider between sections, roughly centred in the gap already left
	 * by SECTION_GAP - only when a previous section actually got drawn first (never before the
	 * very first visible section). Compact mode intentionally has no separators - its whole design
	 * is maximum density, and a divider would work against that.
	 */
	private void drawSeparatorIfNeeded(Graphics2D graphics, boolean anySectionDrawn, int y, int width)
	{
		if (!anySectionDrawn)
		{
			return;
		}

		int lineY = y - (SECTION_GAP / 2);
		graphics.setColor(SEPARATOR_COLOR);
		graphics.drawLine(PADDING, lineY, PADDING + width, lineY);
	}

	/**
	 * Compact mode's substitute for a text label: a thin coloured bar down the left edge of the
	 * row, chosen per what the row is actually about (its "purpose... what it does in game",
	 * rather than an arbitrary palette) so the association is easy to learn at a glance and stays
	 * consistent with colours already used elsewhere in the plugin for the same concept:
	 *   - White: about you specifically (Your Stats, your freeze status) - a neutral "self" colour
	 *     that doesn't collide with any category colour below.
	 *   - The Target section's own configured colour: the target's prayer row - literally the same
	 *     colour already used to highlight that exact player in-world, so it's not a new colour to
	 *     learn at all.
	 *   - A muted hostile red: the aggregate "everyone hostile on screen" rows (opponents' prayers/
	 *     weapons) - these aren't about one specific player, so they get their own "crowd" colour
	 *     rather than borrowing target/caller/pile's identity colours.
	 *   - The existing attacker-flash colour: the attacking-you row - already the exact colour that
	 *     row flashes with when a new attacker engages, just always visible as a thin baseline too.
	 */
	private void drawAccentStripe(Graphics2D graphics, int y, Color color)
	{
		graphics.setColor(color);
		graphics.fillRect(0, y, ACCENT_STRIPE_WIDTH, ROW_HEIGHT);
	}

	/**
	 * Compact layout: no section labels, tighter padding throughout, each row instead marked with
	 * a thin coloured accent stripe (see drawAccentStripe()). Opponent prayers and opponent
	 * weapons used to share a single row here; they now each get their own row like every other
	 * section, for the same reason they already did in normal mode - a shared row made it harder
	 * to tell at a glance which icon belonged to which count. The "Attacking You" row still always
	 * reserves its space - just with a small dash placeholder instead of the word "None" to save room.
	 */
	private Dimension renderCompact(Graphics2D graphics)
	{
		boolean prayersEnabled = config.showOpponentPrayers();
		boolean weaponsEnabled = config.showOpponentWeapons();
		boolean attackingEnabled = config.showAttackingYou();
		boolean statsEnabled = config.showPlayerHitpoints() || config.showPlayerPrayer()
			|| config.showPlayerStrength() || config.showPlayerRanged();
		boolean freezeStatusEnabled = config.showFreezeStatus();
		boolean targetPrayerEnabled = config.showTargetPrayer();
		boolean locationEnabled = config.showLocationInInfobox();
		boolean playerIndexEnabled = config.showPlayerIndex();

		if (!prayersEnabled && !weaponsEnabled && !attackingEnabled && !statsEnabled
			&& !freezeStatusEnabled && !targetPrayerEnabled && !locationEnabled && !playerIndexEnabled)
		{
			return null;
		}

		int y = COMPACT_PADDING;
		int maxWidth = 0;

		if (statsEnabled)
		{
			drawAccentStripe(graphics, y, SELF_ACCENT_COLOR);
			maxWidth = Math.max(maxWidth, COMPACT_PADDING + drawPlayerStatsRow(graphics, COMPACT_PADDING, y));
			y += ROW_HEIGHT + COMPACT_ROW_GAP;
		}

		if (freezeStatusEnabled)
		{
			drawAccentStripe(graphics, y, SELF_ACCENT_COLOR);
			maxWidth = Math.max(maxWidth, COMPACT_PADDING + drawFreezeStatusRow(graphics, COMPACT_PADDING, y));
			y += ROW_HEIGHT + COMPACT_ROW_GAP;
		}

		if (playerIndexEnabled)
		{
			drawAccentStripe(graphics, y, SELF_ACCENT_COLOR);
			maxWidth = Math.max(maxWidth, COMPACT_PADDING + drawPlayerIndexRow(graphics, COMPACT_PADDING, y));
			y += ROW_HEIGHT + COMPACT_ROW_GAP;
		}

		if (targetPrayerEnabled)
		{
			drawAccentStripe(graphics, y, config.targetColor());
			maxWidth = Math.max(maxWidth, COMPACT_PADDING + drawTargetPrayerRow(graphics, COMPACT_PADDING, y));
			y += ROW_HEIGHT + COMPACT_ROW_GAP;
		}

		if (locationEnabled)
		{
			drawAccentStripe(graphics, y, SELF_ACCENT_COLOR);
			maxWidth = Math.max(maxWidth, COMPACT_PADDING + drawLocationRow(graphics, COMPACT_PADDING, y));
			y += ROW_HEIGHT + COMPACT_ROW_GAP;
		}

		boolean hasOpponents = !plugin.getCurrentOpponents().isEmpty();

		if (prayersEnabled && hasOpponents)
		{
			drawAccentStripe(graphics, y, OPPONENT_ACCENT_COLOR);

			int x = COMPACT_PADDING;
			x += drawSpriteIcon(graphics, SPRITE_PROTECT_MELEE, plugin.getOpponentMeleePrayerCount(), x, y);
			x += drawSpriteIcon(graphics, SPRITE_PROTECT_RANGED, plugin.getOpponentRangedPrayerCount(), x, y);
			x += drawSpriteIcon(graphics, SPRITE_PROTECT_MAGIC, plugin.getOpponentMagicPrayerCount(), x, y);

			maxWidth = Math.max(maxWidth, x);
			y += ROW_HEIGHT + COMPACT_ROW_GAP;
		}

		if (weaponsEnabled && hasOpponents)
		{
			boolean anyWeapons = plugin.getOpponentMeleeWeaponCount() > 0 || plugin.getOpponentRangedWeaponCount() > 0
				|| plugin.getOpponentMagicWeaponCount() > 0;
			if (anyWeapons)
			{
				drawAccentStripe(graphics, y, OPPONENT_ACCENT_COLOR);

				int x = COMPACT_PADDING;
				if (plugin.getOpponentMeleeWeaponCount() > 0)
				{
					x += drawItemIcon(graphics, MELEE_WEAPON_ICON_ITEM_ID, plugin.getOpponentMeleeWeaponCount(), x, y);
				}
				if (plugin.getOpponentRangedWeaponCount() > 0)
				{
					x += drawItemIcon(graphics, RANGED_WEAPON_ICON_ITEM_ID, plugin.getOpponentRangedWeaponCount(), x, y);
				}
				if (plugin.getOpponentMagicWeaponCount() > 0)
				{
					x += drawItemIcon(graphics, MAGIC_WEAPON_ICON_ITEM_ID, plugin.getOpponentMagicWeaponCount(), x, y);
				}

				maxWidth = Math.max(maxWidth, x);
				y += ROW_HEIGHT + COMPACT_ROW_GAP;
			}
		}

		if (attackingEnabled)
		{
			boolean blinkOn = plugin.isFlashingAttacker() && (System.currentTimeMillis() / FLASH_BLINK_PERIOD_MILLIS) % 2 == 0;
			int sectionTop = y;
			int sectionWidth = Math.max(maxWidth, COMPACT_MIN_WIDTH);

			if (blinkOn)
			{
				Color flashColor = config.attackerFlashColor();
				graphics.setColor(new Color(flashColor.getRed(), flashColor.getGreen(), flashColor.getBlue(), 110));
				graphics.fillRect(0, sectionTop - 2, sectionWidth + COMPACT_PADDING, ROW_HEIGHT + 4);
			}

			drawAccentStripe(graphics, y, config.attackerFlashColor());

			int x = COMPACT_PADDING;

			if (plugin.getMeleeAttackerCount() == 0 && plugin.getRangedAttackerCount() == 0 && plugin.getMagicAttackerCount() == 0)
			{
				graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 13f));
				graphics.setColor(Color.LIGHT_GRAY);
				graphics.drawString("\u2013", x, y + (ROW_HEIGHT / 2) + 5); // en dash placeholder
				graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 14f));
			}
			else
			{
				if (plugin.getMeleeAttackerCount() > 0)
				{
					x += drawItemIcon(graphics, MELEE_WEAPON_ICON_ITEM_ID, plugin.getMeleeAttackerCount(), x, y);
				}
				if (plugin.getRangedAttackerCount() > 0)
				{
					x += drawItemIcon(graphics, RANGED_WEAPON_ICON_ITEM_ID, plugin.getRangedAttackerCount(), x, y);
				}
				if (plugin.getMagicAttackerCount() > 0)
				{
					x += drawItemIcon(graphics, MAGIC_WEAPON_ICON_ITEM_ID, plugin.getMagicAttackerCount(), x, y);
				}
			}

			maxWidth = Math.max(maxWidth, Math.max(x, sectionWidth));
			y += ROW_HEIGHT;
		}

		return new Dimension(maxWidth + COMPACT_PADDING, y + COMPACT_PADDING);
	}

	private void drawLabel(Graphics2D graphics, String text, int x, int y)
	{
		graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 11f));
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + LABEL_HEIGHT - 3);
		graphics.setColor(Color.LIGHT_GRAY);
		graphics.drawString(text, x, y + LABEL_HEIGHT - 4);
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 14f));
	}

	/**
	 * Draws a real game sprite (e.g. an overhead prayer icon), centred within a fixed slot at its
	 * native aspect ratio (scaled down only if larger than the slot - never stretched), followed
	 * by a vertically-centred count. Returns the total width consumed.
	 */
	private int drawSpriteIcon(Graphics2D graphics, int spriteId, int count, int x, int y)
	{
		BufferedImage icon = getSprite(spriteId);
		drawIconInSlot(graphics, icon, x, y);
		return ICON_SLOT_SIZE + 4 + drawCountText(graphics, count, x + ICON_SLOT_SIZE + 4, y);
	}

	private int drawItemIcon(Graphics2D graphics, int itemId, int count, int x, int y)
	{
		BufferedImage icon = itemManager.getImage(itemId);
		drawIconInSlot(graphics, icon, x, y);
		return ICON_SLOT_SIZE + 4 + drawCountText(graphics, count, x + ICON_SLOT_SIZE + 4, y);
	}

	/**
	 * Draws an icon centred within a fixed-size square slot, preserving aspect ratio. Icons
	 * smaller than the slot are drawn at native size (not upscaled/blurred); icons larger than
	 * the slot are scaled down to fit.
	 */
	private void drawIconInSlot(Graphics2D graphics, BufferedImage icon, int slotX, int slotY)
	{
		if (icon == null)
		{
			return;
		}

		int nativeWidth = icon.getWidth();
		int nativeHeight = icon.getHeight();

		float scale = Math.min(1f, Math.min(
			(float) ICON_SLOT_SIZE / nativeWidth,
			(float) ICON_SLOT_SIZE / nativeHeight));

		int drawWidth = Math.round(nativeWidth * scale);
		int drawHeight = Math.round(nativeHeight * scale);

		int drawX = slotX + (ICON_SLOT_SIZE - drawWidth) / 2;
		int drawY = slotY + (ROW_HEIGHT - drawHeight) / 2;

		graphics.drawImage(icon, drawX, drawY, drawWidth, drawHeight, null);
	}

	/**
	 * Draws text vertically centred against the row height, using font metrics rather than a
	 * hardcoded offset, so it lines up correctly regardless of font/scale. Shared by the "xN"
	 * counts below and the newer hitpoints/freeze/target-prayer rows, which each need their own
	 * text and colour rather than a fixed "xN" count.
	 */
	private int drawText(Graphics2D graphics, String text, int textX, int rowY, Color color)
	{
		int textY = rowY + (ROW_HEIGHT + graphics.getFontMetrics().getAscent() - graphics.getFontMetrics().getDescent()) / 2;

		graphics.setColor(Color.BLACK);
		graphics.drawString(text, textX + 1, textY + 1);
		graphics.setColor(color);
		graphics.drawString(text, textX, textY);

		return graphics.getFontMetrics().stringWidth(text) + 10;
	}

	private int drawCountText(Graphics2D graphics, int count, int textX, int rowY)
	{
		return drawText(graphics, "x" + count, textX, rowY, Color.WHITE);
	}

	/**
	 * "Your Stats": each of hitpoints/prayer/strength/ranged individually toggleable, drawn inline
	 * left-to-right on one row - like the opponent prayer/weapon count rows - rather than each
	 * getting its own labelled section, so the panel stays compact regardless of how many are on.
	 */
	private int drawPlayerStatsRow(Graphics2D graphics, int x, int y)
	{
		int drawnX = x;

		if (config.showPlayerHitpoints())
		{
			drawnX += drawHitpointsStat(graphics, drawnX, y);
		}

		if (config.showPlayerPrayer())
		{
			Color color = plugin.isPrayerBoosted() ? config.prayerBoostColor() : Color.WHITE;
			drawnX += drawSkillLevelStat(graphics, SpriteID.SKILL_PRAYER, client.getBoostedSkillLevel(Skill.PRAYER), drawnX, y, color);
		}

		if (config.showPlayerStrength())
		{
			Color color = plugin.isStrengthBoosted() ? config.strengthPeakColor() : Color.WHITE;
			drawnX += drawSkillLevelStat(graphics, SpriteID.SKILL_STRENGTH, client.getBoostedSkillLevel(Skill.STRENGTH), drawnX, y, color);
		}

		if (config.showPlayerRanged())
		{
			drawnX += drawSkillLevelStat(graphics, SpriteID.SKILL_RANGED, client.getBoostedSkillLevel(Skill.RANGED), drawnX, y, Color.WHITE);
		}

		return drawnX - x;
	}

	/**
	 * Current/max hitpoints, using the Hitpoints skill icon. The text blinks red (same blink
	 * cadence as the "Attacking You" flash) whenever current HP is below the configured
	 * percentage of max, so a dropping HP total is hard to miss during a fight.
	 */
	private int drawHitpointsStat(Graphics2D graphics, int x, int y)
	{
		int current = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max = client.getRealSkillLevel(Skill.HITPOINTS);

		drawIconInSlot(graphics, getSprite(SpriteID.SKILL_HITPOINTS), x, y);

		boolean lowHp = max > 0 && (current * 100 / max) < config.hitpointsFlashThresholdPercent();
		boolean blinkOn = lowHp && (System.currentTimeMillis() / FLASH_BLINK_PERIOD_MILLIS) % 2 == 0;

		return ICON_SLOT_SIZE + 4 + drawText(graphics, current + "/" + max, x + ICON_SLOT_SIZE + 4, y, blinkOn ? Color.RED : Color.WHITE);
	}

	/**
	 * A single current (boosted) skill level, e.g. prayer points remaining or a buffed Strength
	 * level - boosted rather than base, since knowing whether a potion has worn off is the whole
	 * point of showing it during a fight. Colour is caller-chosen: Strength turns green while the
	 * boost exceeds the configured threshold (plugin.isStrengthBoosted()), Prayer turns green
	 * while boosted at all above the real level (plugin.isPrayerBoosted()), Ranged has no such
	 * distinction and always draws white.
	 */
	private int drawSkillLevelStat(Graphics2D graphics, int spriteId, int level, int x, int y, Color color)
	{
		drawIconInSlot(graphics, getSprite(spriteId), x, y);
		return ICON_SLOT_SIZE + 4 + drawText(graphics, String.valueOf(level), x + ICON_SLOT_SIZE + 4, y, color);
	}

	/**
	 * Your own freeze status: a faded placeholder icon while unfrozen, or the active spell's own
	 * icon and a countdown while frozen/immune - identical detection state and countdown maths as
	 * FreezeOverlay (same PrepToolsPlugin.FreezeState entry, same PrepToolsConfig colour/format
	 * settings), just laid out as a fixed panel row instead of drawn above your character's head.
	 *
	 * The immune phase is prefixed "Immune " here (e.g. "Immune 2.1s"), unlike FreezeOverlay's
	 * world-space countdown above a player's head, which stays icon+bare-countdown only for every
	 * player (including you) - this row is the one place a written-out state is wanted, since it's
	 * specifically about your own status and there's room for the extra word.
	 */
	private int drawFreezeStatusRow(Graphics2D graphics, int x, int y)
	{
		Player local = client.getLocalPlayer();
		PrepToolsPlugin.FreezeState state = local != null ? plugin.getFreezeStates().get(local) : null;

		if (state == null)
		{
			drawPlaceholderIcon(graphics, x, y);
			return ICON_SLOT_SIZE + 4 + drawText(graphics, "Not frozen", x + ICON_SLOT_SIZE + 4, y, Color.LIGHT_GRAY);
		}

		drawIconInSlot(graphics, getSprite(state.spriteId), x, y);

		long now = System.currentTimeMillis();
		int currentTick = client.getTickCount();
		FreezeTimeFormat format = config.freezeTimeFormat();
		String countdown;
		String text;
		Color color;

		if (state.immune)
		{
			color = config.immunityTimerColor();
			if (format == FreezeTimeFormat.TICKS)
			{
				countdown = String.valueOf(Math.max(0, state.immunityEndTick - currentTick));
			}
			else
			{
				double remainingSeconds = (PrepToolsPlugin.FREEZE_IMMUNITY_DURATION_MILLIS - (now - state.immunityStartTimeMillis)) / 1000.0;
				countdown = String.format("%.1fs", Math.max(0, remainingSeconds));
			}
			text = "Immune " + countdown;
		}
		else
		{
			color = config.freezeTimerColor();
			if (format == FreezeTimeFormat.TICKS)
			{
				countdown = String.valueOf(Math.max(0, state.endTick - currentTick));
			}
			else
			{
				double remainingSeconds = (state.durationMillis - (now - state.startTimeMillis)) / 1000.0;
				countdown = String.format("%.1fs", Math.max(0, remainingSeconds));
			}
			text = countdown;
		}

		return ICON_SLOT_SIZE + 4 + drawText(graphics, text, x + ICON_SLOT_SIZE + 4, y, color);
	}

	/**
	 * The primary caller's target's active overhead prayer/curse icon, with a readable label next
	 * to it (unlike the aggregate opponent prayer counts above, this is a single one-off
	 * indicator, so a name is more useful here than a number). Falls back to the placeholder icon
	 * plus a label when there's no target, no active overhead icon, or an overhead icon type with
	 * no matching sprite (e.g. a combo protect like RANGE_MAGE_MELEE) - see headIconSpriteId().
	 */
	private int drawTargetPrayerRow(Graphics2D graphics, int x, int y)
	{
		Player target = null;
		for (Player candidate : plugin.getTargets())
		{
			if (candidate != null)
			{
				target = candidate;
				break;
			}
		}

		HeadIcon headIcon = target != null ? target.getOverheadIcon() : null;
		Integer spriteId = headIconSpriteId(headIcon);

		if (spriteId != null)
		{
			drawIconInSlot(graphics, getSprite(spriteId), x, y);
		}
		else
		{
			drawPlaceholderIcon(graphics, x, y);
		}

		String label = headIcon != null ? headIconLabel(headIcon) : "None";
		return ICON_SLOT_SIZE + 4 + drawText(graphics, label, x + ICON_SLOT_SIZE + 4, y, Color.WHITE);
	}

	/**
	 * Maps an overhead icon to its real game sprite where one clearly exists. Combo protects
	 * (RANGE_MAGE, RANGE_MELEE, MAGE_MELEE, RANGE_MAGE_MELEE) and the older Arceuus curse icons
	 * (Soul Split, Deflect Melee/Range/Magic) have no equivalent standalone sprite in SpriteID, so
	 * those fall back to the placeholder icon in drawTargetPrayerRow() - still labelled via
	 * headIconLabel() so the information isn't lost, just not iconified.
	 */
	private Integer headIconSpriteId(HeadIcon icon)
	{
		if (icon == null)
		{
			return null;
		}

		switch (icon)
		{
			case MELEE:
				return SPRITE_PROTECT_MELEE;
			case RANGED:
				return SPRITE_PROTECT_RANGED;
			case MAGIC:
				return SPRITE_PROTECT_MAGIC;
			case RETRIBUTION:
				return SpriteID.PRAYER_RETRIBUTION;
			case SMITE:
				return SpriteID.PRAYER_SMITE;
			case REDEMPTION:
				return SpriteID.PRAYER_REDEMPTION;
			case WRATH:
				return SpriteID.PRAYER_RP_WRATH;
			default:
				return null;
		}
	}

	private String headIconLabel(HeadIcon icon)
	{
		switch (icon)
		{
			case MELEE:
				return "Protect Melee";
			case RANGED:
				return "Protect Missiles";
			case MAGIC:
				return "Protect Magic";
			case RETRIBUTION:
				return "Retribution";
			case SMITE:
				return "Smite";
			case REDEMPTION:
				return "Redemption";
			case WRATH:
				return "Wrath";
			case RANGE_MAGE:
				return "Protect Range/Mage";
			case RANGE_MELEE:
				return "Protect Range/Melee";
			case MAGE_MELEE:
				return "Protect Mage/Melee";
			case RANGE_MAGE_MELEE:
				return "Protect All";
			case SOUL_SPLIT:
				return "Soul Split";
			case DEFLECT_MELEE:
				return "Deflect Melee";
			case DEFLECT_RANGE:
				return "Deflect Range";
			case DEFLECT_MAGE:
				return "Deflect Magic";
			default:
				return icon.toString();
		}
	}

	/**
	 * Your current wilderness location - name (from the "Location Mapper" sidebar tool's saved
	 * areas), wilderness level (from the game's own wilderness level widget), or both. No icon -
	 * unlike the other rows, there's no single sprite that represents "a place", so this is
	 * text-only, left-aligned at the row's normal text position.
	 */
	private int drawLocationRow(Graphics2D graphics, int x, int y)
	{
		String name = plugin.getCurrentLocationName();
		Integer level = plugin.getCurrentWildernessLevel();

		String text;
		if (name == null && level == null)
		{
			text = "Not in wilderness";
		}
		else if (name != null && level != null)
		{
			text = name + " (level " + level + ")";
		}
		else if (name != null)
		{
			text = name;
		}
		else
		{
			text = "Wilderness (level " + level + ")";
		}

		return drawText(graphics, text, x, y, Color.WHITE);
	}

	/**
	 * Your current server-assigned player index - a stable ID given at login (distinct from the
	 * "PID" that reshuffles every ~30-90s). A Jagex developer (Mod Ash, Feb 2023, on the OSRS
	 * Wiki's "Player identification number" page) confirmed the client uses this ID to decide
	 * render priority when multiple players stand on the same tile - i.e. who's drawn on top vs
	 * hidden in a pile. Deliberately informational only: no colour-coding, and no claim that this
	 * controls which player gets right/left-clicked in a pile - that specific claim (which is the
	 * one PKers usually care about) isn't confirmed anywhere, only the render-priority part is. No
	 * icon, same reasoning as drawLocationRow() - there's no single sprite that represents "an ID".
	 */
	private int drawPlayerIndexRow(Graphics2D graphics, int x, int y)
	{
		Player local = client.getLocalPlayer();
		String text = local != null ? "Index " + local.getId() : "Unknown";
		return drawText(graphics, text, x, y, Color.WHITE);
	}

	/**
	 * A faded, self-drawn "no status" icon (circle with a slash) for the freeze status and target
	 * prayer rows when there's nothing to show - no external sprite needed, so it can never fail
	 * to load and always fits the same ICON_SLOT_SIZE square as a real icon would.
	 */
	private void drawPlaceholderIcon(Graphics2D graphics, int slotX, int slotY)
	{
		int size = ICON_SLOT_SIZE - 6;
		int px = slotX + (ICON_SLOT_SIZE - size) / 2;
		int py = slotY + (ROW_HEIGHT - size) / 2;

		graphics.setColor(new Color(255, 255, 255, 60));
		graphics.fillOval(px, py, size, size);
		graphics.setColor(Color.LIGHT_GRAY);
		graphics.drawOval(px, py, size, size);
		graphics.drawLine(px + 4, py + size - 4, px + size - 4, py + 4);
	}

	private BufferedImage getSprite(int spriteId)
	{
		BufferedImage cached = spriteCache.get(spriteId);
		if (cached != null)
		{
			return cached;
		}

		// Async fetch; will populate the cache for subsequent frames once loaded.
		spriteManager.getSpriteAsync(spriteId, 0, image -> spriteCache.put(spriteId, image));
		return null;
	}
}
