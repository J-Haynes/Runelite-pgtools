package net.runelite.client.plugins.preptools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import net.runelite.api.SpriteID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Text;

/**
 * Sidebar panel for switching the primary caller with a single click, in a fixed, stable
 * location - no need to find the right player in a crowded PK fight. Also shows which friends
 * chat members aren't currently in your loaded game view, a flat always-highlight snipe list, a
 * newest-first friends chat activity log, and the "Location Mapper" tool for building up the
 * wilderness location list used by the Location Tools feature.
 *
 * Caller/snipe rows are rebuilt only when their respective config text changes (see
 * PrepToolsPlugin#onConfigChanged); the on-screen/primary status text on each row, and the
 * missing-members list, are refreshed every game tick via updateStatus()/updateMissingMembers()
 * without rebuilding the row widgets themselves, so this stays cheap and flicker-free during
 * combat. The same split applies to the snipe list (updateSnipeStatus() vs rebuildSnipeList()).
 *
 * All names passed into this panel by the plugin are already run through
 * Text.standardize() - a RuneLite utility that normalizes non-breaking spaces, trims, and
 * lowercases - so comparisons here use plain .equals() rather than .equalsIgnoreCase(), and
 * rows are keyed the same way.
 *
 * Text uses RuneLite's own bitmap-hinted UI fonts (FontManager.getRunescapeFont() and friends)
 * rather than the JVM's default Swing look-and-feel font, and every text component has its
 * width explicitly clamped to the panel's own width - both were needed to fix pixelated text
 * and a stray horizontal scrollbar in the previous version.
 */
public class PrepToolsPanel extends PluginPanel
{
	private static final int LOG_SCROLL_HEIGHT = 160;

	private final PrepToolsPlugin plugin;
	private final SpriteManager spriteManager;
	private final ItemManager itemManager;

	// --- Tabs ---
	private final JPanel display = new JPanel();
	private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);

	private final JPanel listPanel = new JPanel();
	private final JTextField addCallerField = new JTextField();
	// Not final: assigned inside buildCallersSection(), called from the constructor - Java's
	// definite-assignment checker can't trace a final field's assignment through a delegated method
	// call, only direct constructor code, even though each is still only ever assigned once here.
	private JTextArea missingArea;
	private JButton copyMissingButton;

	private ButtonGroup buttonGroup = new ButtonGroup();
	private final Map<String, JRadioButton> radiosByKey = new HashMap<>();
	private final Map<String, JLabel> statusLabelsByKey = new HashMap<>();
	private List<String> missingNames = Collections.emptyList();

	// Guards against radio ActionListener callbacks firing while updateStatus() is
	// programmatically changing selection state (which would otherwise re-trigger the listener).
	private boolean updatingProgrammatically;

	// --- Snipe list ---
	private final JPanel snipeListPanel = new JPanel();
	private final JTextField addSnipeField = new JTextField();
	private final Map<String, JLabel> snipeStatusLabelsByKey = new HashMap<>();

	// --- Clan IDs ---
	private final JPanel clanIdsListPanel = new JPanel();

	// --- Friends chat log ---
	private JTextArea friendsChatLogArea;
	private JButton copyFriendsChatLogButton;
	private List<FriendsChatLogEntry> lastFriendsChatLog = Collections.emptyList();

	// --- Team capes ---
	private final JPanel teamCapeNamesPanel = new JPanel();
	private final JSpinner teamCapeNumberSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
	private final JTextField teamCapeNameField = new JTextField();

	// --- Location mapper ---
	private final JLabel currentCoordLabel = new JLabel();
	private final JLabel hoveredCoordLabel = new JLabel();
	private final JLabel cornerALabel = new JLabel();
	private final JLabel cornerBLabel = new JLabel();
	private final JTextField newLocationNameField = new JTextField();
	private final JPanel savedLocationsPanel = new JPanel();
	private List<WildernessLocation> lastWildernessLocations = Collections.emptyList();
	private WildernessLocation editingWildernessLocation;

	PrepToolsPanel(PrepToolsPlugin plugin, SpriteManager spriteManager, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		MaterialTab callersTab = new MaterialTab("Callers", tabGroup, wrapTabContent(buildCallersSection()));
		MaterialTab snipeTab = new MaterialTab("Snipe", tabGroup, wrapTabContent(buildSnipeListSection()));
		MaterialTab clanIdsTab = new MaterialTab("Clan IDs", tabGroup, wrapTabContent(buildClanIdsSection()));
		MaterialTab fcLogTab = new MaterialTab("FC", tabGroup, wrapTabContent(buildFriendsChatLogSection()));
		MaterialTab capesTab = new MaterialTab("Capes", tabGroup, wrapTabContent(buildTeamCapeNamesSection()));
		MaterialTab locationTab = new MaterialTab("Location", tabGroup, wrapTabContent(buildLocationMapperSection()));

		// Centre icon+text within each tab's own grid cell (not just the icon relative to the text -
		// see loadTabIcon()/loadTabItemIcon() for that part) - applied up front, before the icon even
		// loads, so nothing shifts once it does.
		for (MaterialTab tab : new MaterialTab[] {callersTab, snipeTab, clanIdsTab, fcLogTab, capesTab, locationTab})
		{
			tab.setHorizontalAlignment(SwingConstants.CENTER);
			tab.setVerticalAlignment(SwingConstants.CENTER);
		}

		// Real in-game sprites, not bundled image files - this plugin has no resource assets of its
		// own, and these already look native/consistent with the rest of the client's own UI chrome.
		// Loaded async (sprite cache isn't guaranteed ready synchronously at panel-construction time)
		// - see loadTabIcon() below. Capes is the one exception: SpriteID.EQUIPMENT_SLOT_CAPE turned
		// out to be a blank/placeholder equipment-slot icon, not an actual cape - team cape *items*
		// are the real source of a recognisable cape icon (same items TeamCapeOverlay itself already
		// draws), so that one tab goes through ItemManager instead (loadTabItemIcon()).
		loadTabIcon(callersTab, SpriteID.TAB_FRIENDS);
		loadTabIcon(snipeTab, SpriteID.PLAYER_KILLER_SKULL);
		loadTabIcon(clanIdsTab, SpriteID.TAB_STATS);
		loadTabIcon(fcLogTab, SpriteID.TAB_FRIENDS_CHAT);
		loadTabItemIcon(capesTab, TeamCapeOverlay.teamCapeItemId(30));
		loadTabIcon(locationTab, SpriteID.MINIMAP_ORB_WORLD_MAP_PLANET);

		tabGroup.setLayout(new GridLayout(2, 3, 4, 4));
		tabGroup.setBorder(new EmptyBorder(8, 8, 6, 8));
		tabGroup.addTab(callersTab);
		tabGroup.addTab(snipeTab);
		tabGroup.addTab(clanIdsTab);
		tabGroup.addTab(fcLogTab);
		tabGroup.addTab(capesTab);
		tabGroup.addTab(locationTab);
		tabGroup.select(callersTab);

		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		rebuild(new ArrayList<>());
		rebuildSnipeList(new ArrayList<>());
		rebuildTeamCapeNames(new HashMap<>());
	}

	private static final int TAB_ICON_SIZE = 15;

	private void applyTabIcon(MaterialTab tab, Image image)
	{
		Image scaled = image.getScaledInstance(TAB_ICON_SIZE, TAB_ICON_SIZE, Image.SCALE_SMOOTH);
		tab.setIcon(new ImageIcon(scaled));
		tab.setHorizontalTextPosition(SwingConstants.CENTER);
		tab.setVerticalTextPosition(SwingConstants.BOTTOM);
	}

	/**
	 * Loads a real in-game sprite as this tab's icon, above its text label. MaterialTab extends
	 * JLabel, so it can carry both an icon and text simultaneously - just not through either of
	 * MaterialTab's own two constructors (string-only or icon-only), so the icon is set directly
	 * afterward instead. Scaled to a small fixed size since sprites come in wildly different native
	 * resolutions and would otherwise make the tab row uneven.
	 */
	private void loadTabIcon(MaterialTab tab, int spriteId)
	{
		spriteManager.getSpriteAsync(spriteId, 0, image -> SwingUtilities.invokeLater(() -> applyTabIcon(tab, image)));
	}

	/**
	 * Same as loadTabIcon(), but for an item icon (ItemManager) rather than a UI sprite
	 * (SpriteManager) - see the Capes tab's comment at the call site for why it needs this one.
	 */
	private void loadTabItemIcon(MaterialTab tab, int itemId)
	{
		AsyncBufferedImage image = itemManager.getImage(itemId);
		image.onLoaded(() -> SwingUtilities.invokeLater(() -> applyTabIcon(tab, image)));
	}

	/**
	 * Wraps a tab's content panel in its own scroll pane - so a tall tab (Callers+Missing, or
	 * Location with a long Saved Locations list) scrolls within the display area while the tab bar
	 * above it stays fixed/visible, instead of the whole sidebar growing and pushing the tab bar
	 * itself out of view. Same idiom TimeTrackingPanel uses for its own tabs: content pinned to
	 * BorderLayout.NORTH inside a wrapper (so it doesn't stretch to fill unused vertical space),
	 * scroller with no horizontal bar.
	 */
	private JScrollPane wrapTabContent(JPanel content)
	{
		JPanel wrapped = new JPanel(new BorderLayout());
		wrapped.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapped.setBorder(new EmptyBorder(8, 10, 10, 10));
		wrapped.add(content, BorderLayout.NORTH);

		JScrollPane scroller = new JScrollPane(wrapped,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroller.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroller.setBorder(BorderFactory.createEmptyBorder());
		scroller.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
		return scroller;
	}

	/**
	 * Builds the "Callers" tab: primary caller list, title/instructions/list/add field. The
	 * "Missing (Friends Chat)" section lives on the Friends Chat tab instead - see
	 * buildMissingMembersSection() - since it's about friends chat membership generally, not
	 * specifically about configured callers.
	 */
	private JPanel buildCallersSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Primary Caller", 0);

		JTextArea instructions = createDescriptionArea(
			"When your primary caller dies or leaves screen, secondary "
				+ "callers take over automatically, top to bottom through the "
				+ "list below.\n\n"
				+ "Use the ▲/▼ arrows to reorder who takes over next, or ✕ "
				+ "to remove someone.\n\n"
				+ "Add a nickname with \":Nickname\" after a name, e.g. "
				+ "Xx_Bob_xX:Bob - works in the Add field below the list too.\n\n"
				+ "To switch primary right now, just click a name below.");

		listPanel.setLayout(new GridLayout(0, 1, 0, 6));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listPanel.setAlignmentX(LEFT_ALIGNMENT);
		listPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		addCallerField.setToolTipText("Name, optionally with :Nickname, e.g. Xx_Bob_xX:Bob");
		addCallerField.setFont(FontManager.getRunescapeSmallFont());
		addCallerField.addActionListener(e -> addCallerFromField());

		JButton addCallerButton = createSmallButton("+ Add", "Add this name to Callers");
		addCallerButton.addActionListener(e -> addCallerFromField());

		JPanel addCallerPanel = new JPanel(new BorderLayout(4, 0));
		addCallerPanel.setAlignmentX(LEFT_ALIGNMENT);
		addCallerPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		addCallerPanel.add(addCallerField, BorderLayout.CENTER);
		addCallerPanel.add(addCallerButton, BorderLayout.EAST);
		styleInputRow(addCallerPanel);

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(listPanel);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(addCallerPanel);
		return section;
	}

	/**
	 * Builds the "Missing (Friends Chat)" block - moved into the Friends Chat tab (alongside the
	 * activity log) rather than living under Callers, since it's about friends chat membership in
	 * general, not specifically about configured callers.
	 */
	private JPanel buildMissingMembersSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel missingTitle = new JLabel("Missing");
		missingTitle.setForeground(Color.WHITE);
		missingTitle.setFont(FontManager.getRunescapeBoldFont());

		copyMissingButton = createSmallButton("Copy", "Copy missing players to clipboard, one per line");
		copyMissingButton.setEnabled(false);
		copyMissingButton.addActionListener(e -> copyMissingToClipboard());

		JPanel missingHeader = new JPanel(new BorderLayout());
		missingHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		missingHeader.setAlignmentX(LEFT_ALIGNMENT);
		missingHeader.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		missingHeader.add(missingTitle, BorderLayout.WEST);
		missingHeader.add(copyMissingButton, BorderLayout.EAST);

		// Width-clamped like `instructions` elsewhere - a JLabel doesn't wrap on its own, and this
		// sentence is long enough to run past the sidebar's width and get cut off instead of
		// wrapping to a second line.
		JTextArea missingSubtitle = createWidthClampedTextArea("Friends chat members not in your current game view:");
		missingSubtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		missingSubtitle.setBorder(new EmptyBorder(0, 0, 6, 0));

		missingArea = createWidthClampedTextArea("(none)");
		missingArea.setForeground(Color.WHITE);

		section.add(missingHeader);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(missingSubtitle);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(missingArea);
		return section;
	}

	/**
	 * Adds whatever's typed in the "Add caller" field (Enter key or the Add button both trigger
	 * this) and clears the field on success. Supports the same "Name" or "Name:Nickname" syntax as
	 * the raw Callers config field - see PrepToolsPlugin#addCallerFromPanel, which persists it the
	 * same way the reorder arrows and delete button persist their own changes.
	 */
	private void addCallerFromField()
	{
		String text = addCallerField.getText();
		if (text == null || text.trim().isEmpty())
		{
			return;
		}

		plugin.addCallerFromPanel(text.trim());
		addCallerField.setText("");
	}

	/**
	 * Creates a non-editable, word-wrapping JTextArea whose width is hard-capped to the panel's
	 * width - JTextArea has no explicit maximum size by default, which is what was letting it
	 * (and the BoxLayout container around it) grow wider than the sidebar and trigger a
	 * horizontal scrollbar. Height is left unconstrained so it can still grow vertically to fit
	 * wrapped text.
	 */
	private JTextArea createWidthClampedTextArea(String text)
	{
		JTextArea area = new JTextArea(text)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE);
			}
		};
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setFocusable(false);
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setAlignmentX(LEFT_ALIGNMENT);
		return area;
	}

	// ============================================================================================
	// Shared title/description/input-row styling - three visually distinct tiers repeated across
	// every tab (a bold heading, a muted explanatory paragraph, then the actual controls), given a
	// consistent look everywhere rather than each section inventing its own spacing/colour by hand:
	// titles get a brand-orange underline (matching MaterialTab's own selected-tab underline, so it
	// reads as "the same accent colour" rather than a new one), descriptions get a left accent
	// stripe (a blockquote-style treatment - visually marks it as "read this," not "click this").
	// Gaps between all three tiers are explicit Box.createVerticalStrut() calls at each call site,
	// not border insets baked into these components - insets alone didn't read as real spacing.
	// Input rows deliberately do NOT get a background/border box (tried that, asked to remove it) -
	// styleInputRow() below is spacing-only now.
	// ============================================================================================

	static final int TIER_GAP = 10;

	private JLabel createSectionTitle(String text, int topInset)
	{
		JLabel title = new JLabel(text);
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 2, 0, ColorScheme.BRAND_ORANGE),
			new EmptyBorder(topInset, 0, 4, 0)));
		return title;
	}

	private JTextArea createDescriptionArea(String text)
	{
		JTextArea area = createWidthClampedTextArea(text);
		area.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		area.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(1, 8, 0, 2)));
		return area;
	}

	/**
	 * Padding-only "spacing, not a box" treatment for an already-built input row (a text field +
	 * button, a spinner + field + button, etc.) - no background/border, just breathing room inside
	 * the row itself. The gap BEFORE this row (separating it from the description above) is a
	 * Box.createVerticalStrut() at the call site, same as every other tier gap.
	 */
	private void styleInputRow(JPanel row)
	{
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
	}

	/**
	 * Rebuilds the list of caller rows. Called once at startup and again whenever the "Callers"
	 * config text changes.
	 */
	void rebuild(List<PrepToolsPlugin.CallerEntry> entries)
	{
		listPanel.removeAll();
		radiosByKey.clear();
		statusLabelsByKey.clear();
		buttonGroup = new ButtonGroup();

		for (int i = 0; i < entries.size(); i++)
		{
			PrepToolsPlugin.CallerEntry entry = entries.get(i);
			String label = entry.nickname != null
				? entry.nickname + " (" + entry.name + ")"
				: entry.name;
			addRow(Text.standardize(entry.name), label, entry.name, i == 0, i == entries.size() - 1);
		}

		revalidate();
		repaint();
	}

	private void addRow(String key, String label, String realName, boolean isFirst, boolean isLast)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

		JRadioButton radio = new JRadioButton(label);
		radio.setBackground(row.getBackground());
		radio.setForeground(Color.WHITE);
		radio.setFont(FontManager.getRunescapeFont());
		radio.setToolTipText(realName);
		radio.addActionListener(e ->
		{
			if (updatingProgrammatically)
			{
				return;
			}
			plugin.setPinnedCaller(realName);
		});

		buttonGroup.add(radio);
		radiosByKey.put(key, radio);

		JLabel status = new JLabel();
		status.setFont(FontManager.getRunescapeSmallFont());
		statusLabelsByKey.put(key, status);

		JPanel eastPanel = new JPanel();
		eastPanel.setLayout(new BoxLayout(eastPanel, BoxLayout.X_AXIS));
		eastPanel.setBackground(row.getBackground());
		eastPanel.add(status);
		eastPanel.add(Box.createHorizontalStrut(4));
		eastPanel.add(createDeleteButton(realName, plugin::removeCaller));

		row.add(createReorderButtons(realName, isFirst, isLast), BorderLayout.WEST);
		row.add(radio, BorderLayout.CENTER);
		row.add(eastPanel, BorderLayout.EAST);
		listPanel.add(row);
	}

	/**
	 * Removes a caller from the "Callers" config field entirely - see PrepToolsPlugin#removeCaller,
	 * which (like moveCaller) persists straight to config, so onConfigChanged picks it up and
	 * rebuilds this panel automatically; no separate confirmation dialog, same lightweight
	 * "just re-add the name if you didn't mean it" model as the reorder arrows. Shared by both the
	 * caller list and the snipe list (via a functional callback) since they're the same idiom.
	 */
	private JButton createDeleteButton(String realName, java.util.function.Consumer<String> onDelete)
	{
		JButton deleteButton = createSmallButton("✕", "Remove " + realName);
		deleteButton.setForeground(new Color(220, 80, 80));
		deleteButton.addActionListener(e -> onDelete.accept(realName));
		return deleteButton;
	}

	/**
	 * Builds the stacked up/down arrow buttons used to reorder a caller row. Reordering changes
	 * fallback priority (who becomes primary next if the current primary leaves/dies) - see
	 * PrepToolsPlugin#moveCaller, which persists the new order straight to the "Callers" config
	 * field, the same source of truth used everywhere else.
	 *
	 * An unavailable direction (already at the top/bottom of the list) simply isn't added, rather
	 * than added-but-disabled/greyed - so a row at the top of the list shows only a down arrow, one
	 * at the bottom shows only an up arrow, and (for a single-caller list) neither.
	 */
	private JPanel createReorderButtons(String realName, boolean isFirst, boolean isLast)
	{
		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (!isFirst)
		{
			JButton upButton = createSmallButton("▲", "Move up (earlier fallback priority)");
			upButton.addActionListener(e -> plugin.moveCaller(realName, -1));
			buttons.add(upButton);
		}

		if (!isLast)
		{
			JButton downButton = createSmallButton("▼", "Move down (later fallback priority)");
			downButton.addActionListener(e -> plugin.moveCaller(realName, 1));
			buttons.add(downButton);
		}

		return buttons;
	}

	private JButton createSmallButton(String glyph, String tooltip)
	{
		JButton button = new JButton(glyph);
		button.setToolTipText(tooltip);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setMargin(new Insets(0, 3, 0, 3));
		button.setFocusable(false);
		return button;
	}

	/**
	 * Updates selection state and per-row status text/colour without rebuilding any widgets -
	 * safe to call every game tick.
	 *
	 * The checked radio always reflects whoever is ACTUALLY primary right now, whether that's
	 * because they were explicitly clicked, or because they're simply first-on-screen with
	 * nothing explicitly chosen.
	 *
	 * @param onScreenStandardNames Text.standardize()-d real names of configured callers
	 *                              currently on screen
	 * @param primaryStandardName   the current actual primary caller's Text.standardize()-d real
	 *                              name, or null if none
	 */
	void updateStatus(Set<String> onScreenStandardNames, String primaryStandardName)
	{
		updatingProgrammatically = true;
		try
		{
			for (Map.Entry<String, JRadioButton> e : radiosByKey.entrySet())
			{
				String key = e.getKey();
				JRadioButton radio = e.getValue();

				boolean selected = primaryStandardName != null && key.equals(primaryStandardName);
				if (radio.isSelected() != selected)
				{
					radio.setSelected(selected);
				}

				JLabel status = statusLabelsByKey.get(key);
				if (status == null)
				{
					continue;
				}

				boolean onScreen = onScreenStandardNames.contains(key);

				if (selected)
				{
					status.setForeground(Color.RED);
					status.setText("PRIMARY");
				}
				else if (onScreen)
				{
					status.setForeground(new Color(80, 200, 80));
					status.setText("on screen");
				}
				else
				{
					status.setForeground(Color.GRAY);
					status.setText("offscreen");
				}
			}
		}
		finally
		{
			updatingProgrammatically = false;
		}
	}

	/**
	 * Updates the "Missing (Friends Chat)" list - one name per line, or "(none)" if everyone's
	 * currently in view. Cheap to call every tick; just replaces the text area's content.
	 */
	void updateMissingMembers(List<String> missingNames)
	{
		// Copied rather than aliased - the plugin clears and refills its own list in place every
		// tick on the client thread, and the Copy button below can be clicked from the EDT at any
		// time, so holding a reference to that same mutable list would risk reading it mid-mutation.
		this.missingNames = new ArrayList<>(missingNames);
		copyMissingButton.setEnabled(!missingNames.isEmpty());

		if (missingNames.isEmpty())
		{
			missingArea.setText("(none)");
			return;
		}

		StringBuilder sb = new StringBuilder();
		for (String name : missingNames)
		{
			sb.append(name).append('\n');
		}
		missingArea.setText(sb.toString().trim());
	}

	/**
	 * Copies the current "Missing (Friends Chat)" list to the system clipboard, one name per line -
	 * handy for pasting straight into the friends chat/clan chat to ask where everyone is.
	 */
	private void copyMissingToClipboard()
	{
		if (missingNames.isEmpty())
		{
			return;
		}

		String text = String.join("\n", missingNames);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	// ============================================================================================
	// Snipe list - a flat "always highlight" list, no primary/fallback concept, so its rows are
	// just a name + on-screen status + delete button, no radio button or reorder arrows.
	// ============================================================================================

	private JPanel buildSnipeListSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Snipe List", 0);

		JTextArea instructions = createDescriptionArea(
			"Always highlighted on sight, regardless of caller/target status. "
				+ "Shift-right-click a player in game and choose 'Add to snipe list', "
				+ "or type a name below.");

		snipeListPanel.setLayout(new GridLayout(0, 1, 0, 6));
		snipeListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		snipeListPanel.setAlignmentX(LEFT_ALIGNMENT);
		snipeListPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		addSnipeField.setToolTipText("Player name");
		addSnipeField.setFont(FontManager.getRunescapeSmallFont());
		addSnipeField.addActionListener(e -> addSnipeFromField());

		JButton addSnipeButton = createSmallButton("+ Add", "Add this name to the snipe list");
		addSnipeButton.addActionListener(e -> addSnipeFromField());

		JPanel addSnipePanel = new JPanel(new BorderLayout(4, 0));
		addSnipePanel.setAlignmentX(LEFT_ALIGNMENT);
		addSnipePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		addSnipePanel.add(addSnipeField, BorderLayout.CENTER);
		addSnipePanel.add(addSnipeButton, BorderLayout.EAST);
		styleInputRow(addSnipePanel);

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(snipeListPanel);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(addSnipePanel);
		return section;
	}

	private void addSnipeFromField()
	{
		String text = addSnipeField.getText();
		if (text == null || text.trim().isEmpty())
		{
			return;
		}

		plugin.addSnipeFromPanel(text.trim());
		addSnipeField.setText("");
	}

	/**
	 * Rebuilds the snipe list rows. Called once at startup and again whenever the "Snipe list"
	 * config text changes.
	 */
	void rebuildSnipeList(List<String> names)
	{
		snipeListPanel.removeAll();
		snipeStatusLabelsByKey.clear();

		for (String name : names)
		{
			addSnipeRow(Text.standardize(name), name);
		}

		revalidate();
		repaint();
	}

	private void addSnipeRow(String key, String realName)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

		JLabel nameLabel = new JLabel(realName);
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(FontManager.getRunescapeFont());

		JLabel status = new JLabel();
		status.setFont(FontManager.getRunescapeSmallFont());
		snipeStatusLabelsByKey.put(key, status);

		JPanel eastPanel = new JPanel();
		eastPanel.setLayout(new BoxLayout(eastPanel, BoxLayout.X_AXIS));
		eastPanel.setBackground(row.getBackground());
		eastPanel.add(status);
		eastPanel.add(Box.createHorizontalStrut(4));
		eastPanel.add(createDeleteButton(realName, plugin::removeSnipedName));

		row.add(nameLabel, BorderLayout.CENTER);
		row.add(eastPanel, BorderLayout.EAST);
		snipeListPanel.add(row);
	}

	/**
	 * Updates per-row on-screen status without rebuilding any widgets - safe to call every tick.
	 */
	void updateSnipeStatus(Set<String> onScreenStandardNames)
	{
		for (Map.Entry<String, JLabel> e : snipeStatusLabelsByKey.entrySet())
		{
			JLabel status = e.getValue();
			if (onScreenStandardNames.contains(e.getKey()))
			{
				status.setForeground(new Color(80, 200, 80));
				status.setText("on screen");
			}
			else
			{
				status.setForeground(Color.GRAY);
				status.setText("offscreen");
			}
		}
	}

	// ============================================================================================
	// Clan IDs - see MENU_ORDER_PID_INVESTIGATION.md at the repo root for what a player's id is and
	// why the min/max of a stack matter for right-click menu Attack-option exposure. Clan/FC members
	// only (not enemies) - confirmed with the user that OSRS's own friendly-fire protection means an
	// enemy's menu on your team's pile is built exclusively from your team's ids, so an enemy's own
	// id is never a factor in who on your side is exposed.
	// ============================================================================================

	private static final Color CALLER_HIGHLIGHT_COLOR = new Color(230, 180, 60);
	private static final Color ID_RISK_COLOR = new Color(220, 100, 90);

	private JPanel buildClanIdsSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Clan IDs", 0);

		JTextArea instructions = createDescriptionArea(
			"You + friends chat members currently on screen, sorted by id. "
				+ "The lowest and highest id in the list are both flagged - whichever one ends up "
				+ "the literal top menu entry depends on whether the person clicking is standing in "
				+ "your stack or not, so both stay flagged rather than picking one. "
				+ "Current understanding, not a guarantee - see MENU_ORDER_PID_INVESTIGATION.md.");

		clanIdsListPanel.setLayout(new GridLayout(0, 1, 0, 4));
		clanIdsListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		clanIdsListPanel.setAlignmentX(LEFT_ALIGNMENT);
		clanIdsListPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(clanIdsListPanel);
		updateClanIds(Collections.emptyList());
		return section;
	}

	/**
	 * Rebuilds the Clan IDs row list from scratch every call - unlike the caller/snipe lists (which
	 * only rebuild on a config change and get cheap per-tick label updates in between), the id order
	 * itself changes as players move on/off screen or relog, so there's no stable "same set of rows,
	 * just update status text" split to make here. Called once per tick; cheap at clan-sized lists.
	 */
	void updateClanIds(List<ClanIdRow> rows)
	{
		clanIdsListPanel.removeAll();

		if (rows.isEmpty())
		{
			JLabel empty = new JLabel("(no friends chat members on screen)");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			clanIdsListPanel.add(empty);
			revalidate();
			repaint();
			return;
		}

		int minId = rows.get(0).id;
		int maxId = rows.get(rows.size() - 1).id;

		for (ClanIdRow row : rows)
		{
			boolean atRiskId = rows.size() > 1 && (row.id == minId || row.id == maxId);
			clanIdsListPanel.add(buildClanIdRow(row, atRiskId));
		}

		revalidate();
		repaint();
	}

	private JPanel buildClanIdRow(ClanIdRow row, boolean atRiskId)
	{
		JPanel rowPanel = new JPanel(new BorderLayout(6, 0));
		rowPanel.setBackground(atRiskId ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
		rowPanel.setBorder(atRiskId
			? BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 2, 0, 0, ID_RISK_COLOR),
				BorderFactory.createEmptyBorder(2, 4, 2, 2))
			: BorderFactory.createEmptyBorder(2, 6, 2, 2));

		String displayName = row.isYou ? row.name + " (you)" : row.name;
		JLabel nameLabel = new JLabel(displayName);
		nameLabel.setFont(row.isCaller ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		nameLabel.setForeground(row.isCaller ? CALLER_HIGHLIGHT_COLOR : Color.WHITE);

		JLabel idLabel = new JLabel("id " + row.id);
		idLabel.setFont(FontManager.getRunescapeSmallFont());
		idLabel.setForeground(atRiskId ? ID_RISK_COLOR : ColorScheme.LIGHT_GRAY_COLOR);

		rowPanel.add(nameLabel, BorderLayout.CENTER);
		rowPanel.add(idLabel, BorderLayout.EAST);
		return rowPanel;
	}

	// ============================================================================================
	// Friends Chat tab - who's missing from view, plus a join/leave/death activity log. Both are
	// about friends chat membership in general (not specifically callers), so they live together.
	// ============================================================================================

	private JPanel buildFriendsChatLogSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel pageTitle = createSectionTitle("Friends Chat", 0);

		JLabel title = new JLabel("Log");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());

		copyFriendsChatLogButton = createSmallButton("Copy", "Copy the friends chat log to clipboard, one entry per line");
		copyFriendsChatLogButton.setEnabled(false);
		copyFriendsChatLogButton.addActionListener(e -> copyFriendsChatLogToClipboard());

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		header.add(title, BorderLayout.WEST);
		header.add(copyFriendsChatLogButton, BorderLayout.EAST);

		JTextArea subtitle = createDescriptionArea("Newest first. Join/leave/death activity, with a timestamp.");

		friendsChatLogArea = createWidthClampedTextArea("(no activity yet)");
		friendsChatLogArea.setForeground(Color.WHITE);

		// Fixed-height scroll pane rather than letting the log grow the whole sidebar unbounded -
		// with up to friendsChatLogMaxEntries (200 by default) lines, an unclamped panel would make
		// everything below it require excessive scrolling to reach.
		JScrollPane scrollPane = new JScrollPane(friendsChatLogArea,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setAlignmentX(LEFT_ALIGNMENT);
		scrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR));
		scrollPane.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, LOG_SCROLL_HEIGHT));
		scrollPane.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, LOG_SCROLL_HEIGHT));

		section.add(pageTitle);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(buildMissingMembersSection());
		section.add(Box.createVerticalStrut(TIER_GAP * 2));
		section.add(header);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(subtitle);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(scrollPane);
		return section;
	}

	/**
	 * Replaces the log display's content - called whenever a new entry is logged (join/leave/
	 * death), not on every tick, since the underlying log itself only changes on those events.
	 */
	void updateFriendsChatLog(Deque<FriendsChatLogEntry> log)
	{
		// Copied rather than aliased, same reasoning as updateMissingMembers() above - the plugin
		// mutates its own deque on the client thread, and the Copy button can be clicked from the
		// EDT at any time.
		lastFriendsChatLog = new ArrayList<>(log);
		copyFriendsChatLogButton.setEnabled(!log.isEmpty());

		if (log.isEmpty())
		{
			friendsChatLogArea.setText("(no activity yet)");
			return;
		}

		StringBuilder sb = new StringBuilder();
		for (FriendsChatLogEntry entry : log)
		{
			sb.append(entry.displayText).append('\n');
		}
		friendsChatLogArea.setText(sb.toString().trim());
	}

	/**
	 * Copies the current Friends Chat Log to the system clipboard, one entry per line - same idiom
	 * as copyMissingToClipboard() above.
	 */
	private void copyFriendsChatLogToClipboard()
	{
		if (lastFriendsChatLog.isEmpty())
		{
			return;
		}

		StringBuilder sb = new StringBuilder();
		for (FriendsChatLogEntry entry : lastFriendsChatLog)
		{
			sb.append(entry.displayText).append('\n');
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString().trim()), null);
	}

	// ============================================================================================
	// Team capes - a friendlier editor for PrepToolsConfig#teamCapeNames than hand-typing
	// "Number:Name" lines into the raw config field, and the only way to actually ENFORCE the
	// 5-character name limit at typing time (a plain multi-line config text area can't do that).
	// ============================================================================================

	private JPanel buildTeamCapeNamesSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Team Capes", 0);

		JTextArea instructions = createDescriptionArea(
			"Custom name shown below a team cape's icon (max 5 characters). "
				+ "Team numbers without a name here just show the icon.");

		teamCapeNumberSpinner.setFont(FontManager.getRunescapeSmallFont());
		((JSpinner.DefaultEditor) teamCapeNumberSpinner.getEditor()).getTextField().setColumns(3);

		teamCapeNameField.setToolTipText("Name, max 5 characters, e.g. EoP");
		teamCapeNameField.setFont(FontManager.getRunescapeSmallFont());
		limitTextFieldLength(teamCapeNameField, PrepToolsPlugin.TEAM_CAPE_NAME_MAX_LENGTH);
		teamCapeNameField.addActionListener(e -> saveTeamCapeNameFromFields());

		JButton saveButton = createSmallButton("Save", "Save this team number's custom name");
		saveButton.addActionListener(e -> saveTeamCapeNameFromFields());

		JPanel entryPanel = new JPanel(new BorderLayout(4, 0));
		entryPanel.setAlignmentX(LEFT_ALIGNMENT);
		entryPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		entryPanel.add(teamCapeNumberSpinner, BorderLayout.WEST);
		entryPanel.add(teamCapeNameField, BorderLayout.CENTER);
		entryPanel.add(saveButton, BorderLayout.EAST);
		styleInputRow(entryPanel);

		teamCapeNamesPanel.setLayout(new GridLayout(0, 1, 0, 4));
		teamCapeNamesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		teamCapeNamesPanel.setAlignmentX(LEFT_ALIGNMENT);
		teamCapeNamesPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(entryPanel);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(teamCapeNamesPanel);
		return section;
	}

	private void saveTeamCapeNameFromFields()
	{
		int teamNumber = (Integer) teamCapeNumberSpinner.getValue();
		String name = teamCapeNameField.getText();
		if (name == null || name.trim().isEmpty())
		{
			return;
		}

		plugin.setTeamCapeNameFromPanel(teamNumber, name.trim());
		teamCapeNameField.setText("");
	}

	/**
	 * Installs a DocumentFilter that refuses any edit which would push the field's text past
	 * maxLength - the standard Swing idiom for a hard character limit, since JTextField itself has
	 * no built-in max-length property.
	 */
	private void limitTextFieldLength(JTextField field, int maxLength)
	{
		((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter()
		{
			@Override
			public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException
			{
				if (text != null && fb.getDocument().getLength() + text.length() <= maxLength)
				{
					super.insertString(fb, offset, text, attrs);
				}
			}

			@Override
			public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException
			{
				int addedLength = text == null ? 0 : text.length();
				if (fb.getDocument().getLength() - length + addedLength <= maxLength)
				{
					super.replace(fb, offset, length, text, attrs);
				}
			}
		});
	}

	/**
	 * Rebuilds the list of configured team cape names with a delete button per entry. Called at
	 * startup and whenever the "Team cape names" config changes.
	 */
	void rebuildTeamCapeNames(Map<Integer, String> names)
	{
		teamCapeNamesPanel.removeAll();

		// TreeMap: shown in ascending team-number order rather than whatever order the backing
		// HashMap happens to iterate in, so the list doesn't visually reshuffle on every edit.
		for (Map.Entry<Integer, String> entry : new TreeMap<>(names).entrySet())
		{
			int teamNumber = entry.getKey();
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			row.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

			JLabel label = new JLabel("Team " + teamNumber + ": " + entry.getValue());
			label.setForeground(Color.WHITE);
			label.setFont(FontManager.getRunescapeSmallFont());

			JButton deleteButton = createSmallButton("✕", "Remove this team's custom name");
			deleteButton.setForeground(new Color(220, 80, 80));
			deleteButton.addActionListener(e -> plugin.removeTeamCapeName(teamNumber));

			row.add(label, BorderLayout.CENTER);
			row.add(deleteButton, BorderLayout.EAST);
			teamCapeNamesPanel.add(row);
		}

		revalidate();
		repaint();
	}

	// ============================================================================================
	// Location mapper - builds up PrepToolsConfig's wilderness location list from your own
	// in-game coordinates, for the Location Tools feature.
	// ============================================================================================

	private JPanel buildLocationMapperSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Location Mapper", 0);

		JTextArea instructions = createDescriptionArea(
			"Walk to a landmark, click Set Corner A, walk to the opposite "
				+ "corner of the area, click Set Corner B, name it, then Save. "
				+ "The saved box is used to detect and spam your location. "
				+ "Click ✎ on a saved area below to edit its name or coordinates directly.");

		currentCoordLabel.setForeground(Color.WHITE);
		currentCoordLabel.setFont(FontManager.getRunescapeSmallFont());
		currentCoordLabel.setAlignmentX(LEFT_ALIGNMENT);
		currentCoordLabel.setText("Current: (unknown)");

		hoveredCoordLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hoveredCoordLabel.setFont(FontManager.getRunescapeSmallFont());
		hoveredCoordLabel.setAlignmentX(LEFT_ALIGNMENT);
		hoveredCoordLabel.setText("Hovered: (none)");
		hoveredCoordLabel.setBorder(new EmptyBorder(0, 0, 6, 0));

		cornerALabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cornerALabel.setFont(FontManager.getRunescapeSmallFont());
		cornerALabel.setAlignmentX(LEFT_ALIGNMENT);
		cornerALabel.setText("Corner A: (not set)");

		cornerBLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cornerBLabel.setFont(FontManager.getRunescapeSmallFont());
		cornerBLabel.setAlignmentX(LEFT_ALIGNMENT);
		cornerBLabel.setText("Corner B: (not set)");

		JButton setCornerAButton = createSmallButton("Set Corner A", "Capture your current position as corner A");
		setCornerAButton.addActionListener(e -> plugin.setLocationCornerA());

		JButton setCornerBButton = createSmallButton("Set Corner B", "Capture your current position as corner B");
		setCornerBButton.addActionListener(e -> plugin.setLocationCornerB());

		JPanel cornerButtonsPanel = new JPanel(new GridLayout(1, 2, 6, 0));
		cornerButtonsPanel.setAlignmentX(LEFT_ALIGNMENT);
		cornerButtonsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		cornerButtonsPanel.setBorder(new EmptyBorder(6, 0, 0, 0));
		cornerButtonsPanel.add(setCornerAButton);
		cornerButtonsPanel.add(setCornerBButton);

		// The live coordinate readouts + capture buttons are one linked "tool" - grouped into a
		// single input card rather than four separate loose labels, so it's visually obvious they
		// belong together and are distinct from the plain-background instructions above.
		JPanel coordCard = new JPanel();
		coordCard.setLayout(new BoxLayout(coordCard, BoxLayout.Y_AXIS));
		coordCard.setAlignmentX(LEFT_ALIGNMENT);
		coordCard.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		coordCard.add(currentCoordLabel);
		coordCard.add(hoveredCoordLabel);
		coordCard.add(cornerALabel);
		coordCard.add(cornerBLabel);
		coordCard.add(cornerButtonsPanel);
		styleInputRow(coordCard);

		newLocationNameField.setToolTipText("Name for this area, e.g. Ferox Enclave");
		newLocationNameField.setFont(FontManager.getRunescapeSmallFont());
		newLocationNameField.addActionListener(e -> saveLocationFromField());

		JButton saveLocationButton = createSmallButton("Save", "Save this named area (both corners must be set)");
		saveLocationButton.addActionListener(e -> saveLocationFromField());

		JPanel saveLocationPanel = new JPanel(new BorderLayout(4, 0));
		saveLocationPanel.setAlignmentX(LEFT_ALIGNMENT);
		saveLocationPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		saveLocationPanel.add(newLocationNameField, BorderLayout.CENTER);
		saveLocationPanel.add(saveLocationButton, BorderLayout.EAST);
		styleInputRow(saveLocationPanel);

		JLabel savedTitle = createSectionTitle("Saved Locations", 0);

		savedLocationsPanel.setLayout(new GridLayout(0, 1, 0, 4));
		savedLocationsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		savedLocationsPanel.setAlignmentX(LEFT_ALIGNMENT);
		savedLocationsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(coordCard);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(saveLocationPanel);
		section.add(Box.createVerticalStrut(TIER_GAP * 2));
		section.add(savedTitle);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(savedLocationsPanel);
		return section;
	}

	private void saveLocationFromField()
	{
		String text = newLocationNameField.getText();
		if (text == null || text.trim().isEmpty())
		{
			return;
		}

		plugin.saveLocationFromPanel(text.trim());
		newLocationNameField.setText("");
	}

	/**
	 * Updates the live coordinate readout (your own position and the hovered tile) and corner
	 * labels - safe to call every tick.
	 */
	void updateLocationMapper(WorldPoint current, Integer wildernessLevel, String matchedName,
		WorldPoint hovered, String hoveredName, WorldPoint cornerA, WorldPoint cornerB)
	{
		currentCoordLabel.setText("Current: " + formatCoord(current, wildernessLevel, matchedName));
		hoveredCoordLabel.setText("Hovered: " + formatCoord(hovered, null, hoveredName));
		cornerALabel.setText("Corner A: " + formatCorner(cornerA));
		cornerBLabel.setText("Corner B: " + formatCorner(cornerB));
	}

	/**
	 * Shared formatting for the "Current"/"Hovered" readouts: coordinates, an optional wilderness
	 * level, and an optional matched saved-location name. The hovered tile has no wilderness level
	 * of its own readily available (that widget only reports YOUR level, not an arbitrary tile's),
	 * so callers simply pass null for that tile's level.
	 */
	private String formatCoord(WorldPoint point, Integer wildernessLevel, String matchedName)
	{
		if (point == null)
		{
			return "(none)";
		}

		StringBuilder sb = new StringBuilder()
			.append(point.getX()).append(", ").append(point.getY()).append(", plane ").append(point.getPlane());
		if (wildernessLevel != null)
		{
			sb.append(" (level ").append(wildernessLevel).append(')');
		}
		if (matchedName != null)
		{
			sb.append(" - ").append(matchedName);
		}
		return sb.toString();
	}

	private String formatCorner(WorldPoint corner)
	{
		return corner == null ? "(not set)" : corner.getX() + ", " + corner.getY() + ", plane " + corner.getPlane();
	}

	/**
	 * Rebuilds the "Saved Locations" list with edit/delete buttons per entry. Called at startup and
	 * whenever a location is added/removed/edited. Caches the list so rebuildSavedLocations() (the
	 * edit/cancel toggle) can rebuild locally without needing a round trip through the plugin.
	 */
	void updateWildernessLocations(List<WildernessLocation> locations)
	{
		lastWildernessLocations = locations;
		rebuildSavedLocations();
	}

	private void rebuildSavedLocations()
	{
		savedLocationsPanel.removeAll();

		for (WildernessLocation location : lastWildernessLocations)
		{
			savedLocationsPanel.add(location == editingWildernessLocation
				? buildLocationEditRow(location)
				: buildLocationViewRow(location));
		}

		revalidate();
		repaint();
	}

	private JPanel buildLocationViewRow(WildernessLocation location)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

		JLabel nameLabel = new JLabel(location.name);
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setToolTipText(
			"(" + location.minX + "," + location.minY + ") - (" + location.maxX + "," + location.maxY + "), plane " + location.plane);

		JButton editButton = createSmallButton("✎", "Edit " + location.name);
		editButton.addActionListener(e ->
		{
			editingWildernessLocation = location;
			rebuildSavedLocations();
		});

		JButton deleteButton = createSmallButton("✕", "Remove " + location.name);
		deleteButton.setForeground(new Color(220, 80, 80));
		deleteButton.addActionListener(e -> plugin.removeWildernessLocation(location));

		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(editButton);
		buttons.add(deleteButton);

		row.add(nameLabel, BorderLayout.CENTER);
		row.add(buttons, BorderLayout.EAST);
		return row;
	}

	/**
	 * In-place edit form for a saved location: name plus the same two-corner model as the
	 * "Set Corner A/B" flow above (not pre-sorted min/max) - PrepToolsPlugin#updateWildernessLocation
	 * normalizes them the same way saveLocationFromPanel() does, so typing the corners in either
	 * order/direction works exactly like drawing the area fresh does.
	 */
	private JPanel buildLocationEditRow(WildernessLocation location)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 4, 4, 4)));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JTextField nameField = new JTextField(location.name);
		nameField.setFont(FontManager.getRunescapeSmallFont());
		nameField.setAlignmentX(LEFT_ALIGNMENT);
		nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameField.getPreferredSize().height));

		JTextField minXField = new JTextField(String.valueOf(location.minX));
		JTextField minYField = new JTextField(String.valueOf(location.minY));
		JTextField maxXField = new JTextField(String.valueOf(location.maxX));
		JTextField maxYField = new JTextField(String.valueOf(location.maxY));
		JTextField planeField = new JTextField(String.valueOf(location.plane));

		// Live preview (a distinct colour, drawn by WildernessLocationDebugOverlay) of what Save
		// would produce, refreshed on every change - arrow-key nudge or direct typing - rather than
		// only once you actually click Save. Corners don't need to be pre-sorted here any more than
		// they do for Save itself (see this method's own class doc above) - min()/max() below do
		// that, same as PrepToolsPlugin#updateWildernessLocation does for the real save.
		Runnable pushPreview = () ->
		{
			try
			{
				int x1 = Integer.parseInt(minXField.getText().trim());
				int y1 = Integer.parseInt(minYField.getText().trim());
				int x2 = Integer.parseInt(maxXField.getText().trim());
				int y2 = Integer.parseInt(maxYField.getText().trim());
				int plane = Integer.parseInt(planeField.getText().trim());
				String previewName = nameField.getText();
				plugin.updateLocationPreview(
					previewName == null || previewName.trim().isEmpty() ? location.name : previewName.trim(),
					Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), plane);
			}
			catch (NumberFormatException ex)
			{
				// Invalid/incomplete numeric input mid-edit - leave the last valid preview showing
				// rather than clearing it, so a brief empty field while retyping doesn't flicker.
			}
		};

		installArrowKeyAdjust(minXField, pushPreview);
		installArrowKeyAdjust(minYField, pushPreview);
		installArrowKeyAdjust(maxXField, pushPreview);
		installArrowKeyAdjust(maxYField, pushPreview);
		installArrowKeyAdjust(planeField, pushPreview);
		nameField.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				pushPreview.run();
			}
		});
		pushPreview.run();

		JPanel coordsGrid = new JPanel(new GridLayout(3, 2, 4, 2));
		coordsGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		coordsGrid.setAlignmentX(LEFT_ALIGNMENT);
		coordsGrid.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		// Cardinal + axis, not "Corner A X"/"Corner B X" - these fields already store normalized
		// min/max (see the class doc above), and X increases east/Y increases north in OSRS's own
		// coordinate system, so "West"/"South" (min) and "East"/"North" (max) are both accurate and
		// far more obvious at a glance than an arbitrary "A"/"B" label ever was.
		coordsGrid.add(buildLabeledField("West X (min)", minXField));
		coordsGrid.add(buildLabeledField("South Y (min)", minYField));
		coordsGrid.add(buildLabeledField("East X (max)", maxXField));
		coordsGrid.add(buildLabeledField("North Y (max)", maxYField));
		coordsGrid.add(buildLabeledField("Plane", planeField));

		JButton saveButton = createSmallButton("Save", "Save changes to this area");
		saveButton.addActionListener(e ->
		{
			String newName = nameField.getText();
			if (newName == null || newName.trim().isEmpty())
			{
				return;
			}

			try
			{
				int corner1X = Integer.parseInt(minXField.getText().trim());
				int corner1Y = Integer.parseInt(minYField.getText().trim());
				int corner2X = Integer.parseInt(maxXField.getText().trim());
				int corner2Y = Integer.parseInt(maxYField.getText().trim());
				int plane = Integer.parseInt(planeField.getText().trim());

				editingWildernessLocation = null;
				plugin.clearLocationPreview();
				plugin.updateWildernessLocation(location, newName, corner1X, corner1Y, corner2X, corner2Y, plane);
			}
			catch (NumberFormatException ex)
			{
				// Invalid numeric input - leave the row in edit mode rather than saving garbage.
			}
		});

		JButton cancelButton = createSmallButton("Cancel", "Discard changes");
		cancelButton.addActionListener(e ->
		{
			editingWildernessLocation = null;
			plugin.clearLocationPreview();
			rebuildSavedLocations();
		});

		JPanel buttonsRow = new JPanel(new GridLayout(1, 2, 6, 0));
		buttonsRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonsRow.setAlignmentX(LEFT_ALIGNMENT);
		buttonsRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		buttonsRow.setBorder(new EmptyBorder(4, 0, 0, 0));
		buttonsRow.add(saveButton);
		buttonsRow.add(cancelButton);

		row.add(nameField);
		row.add(Box.createVerticalStrut(4));
		row.add(coordsGrid);
		row.add(buttonsRow);
		return row;
	}

	/**
	 * Up/Down arrow keys nudge a numeric text field's value by +-1 - standard spinner-style
	 * behaviour, applied to plain JTextFields here rather than swapping them for JSpinners, since
	 * these fields already need to accept direct typing too (pasting/typing a whole new coordinate
	 * at once) and a JSpinner's up/down buttons would take real estate this compact edit form
	 * doesn't have room for. Consumes the key event so it doesn't also move the caret. onChange
	 * fires after every arrow-key nudge AND every other key release (typing), so callers get exactly
	 * one place to hook "the value might have changed."
	 */
	private void installArrowKeyAdjust(JTextField field, Runnable onChange)
	{
		field.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() != KeyEvent.VK_UP && e.getKeyCode() != KeyEvent.VK_DOWN)
				{
					return;
				}

				try
				{
					int value = Integer.parseInt(field.getText().trim());
					field.setText(String.valueOf(value + (e.getKeyCode() == KeyEvent.VK_UP ? 1 : -1)));
				}
				catch (NumberFormatException ex)
				{
					// Not currently a valid number - nothing sensible to nudge, leave it as typed.
				}
				e.consume();
			}

			@Override
			public void keyReleased(KeyEvent e)
			{
				onChange.run();
			}
		});
	}

	private JPanel buildLabeledField(String label, JTextField field)
	{
		field.setFont(FontManager.getRunescapeSmallFont());

		JLabel labelComponent = new JLabel(label);
		labelComponent.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		labelComponent.setFont(FontManager.getRunescapeSmallFont());

		JPanel wrapper = new JPanel(new BorderLayout(2, 0));
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.add(labelComponent, BorderLayout.WEST);
		wrapper.add(field, BorderLayout.CENTER);
		return wrapper;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
	}
}
