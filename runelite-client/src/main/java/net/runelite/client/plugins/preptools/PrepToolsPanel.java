package net.runelite.client.plugins.preptools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
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
	private final ConfigListEditor lists;
	private final SpriteManager spriteManager;
	private final ItemManager itemManager;

	// --- Tabs ---
	private final JPanel display = new JPanel();
	private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);

	private final JPanel listPanel = new JPanel();
	private final JTextField addCallerField = new JTextField();

	// Caller edit form - a separate panel under the list (not an in-row form like the Location
	// tab's), since listPanel is a GridLayout and one taller row would stretch every row to match.
	private final JPanel callerEditPanel = new JPanel();
	private final JLabel callerEditTitle = new JLabel();
	private final JTextField callerEditNameField = new JTextField();
	private final JTextField callerEditNicknameField = new JTextField();
	// Real name of the caller being edited, or null when the form is hidden.
	private String editingCallerName;
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

	// --- Spam ---
	private final JPanel spamListPanel = new JPanel();
	private final JTextField addSpamField = new JTextField();
	private final JCheckBox spamRandomCheckBox = new JCheckBox("Random message each press");
	private ButtonGroup spamButtonGroup = new ButtonGroup();
	// Same programmatic-change guard as updatingProgrammatically, for the spam radios/checkbox.
	private boolean updatingSpamProgrammatically;

	// --- Clan Info ---
	private final JPanel clanIdsListPanel = new JPanel();
	private final JPanel clanWeaponsPanel = new JPanel();
	private JButton copyClanIdsButton;
	private JButton copyClanWeaponsButton;
	private List<ClanIdRow> lastClanIdRows = Collections.emptyList();
	private boolean clanIdsInitialised;
	private ClanWeaponSummary lastClanWeapons;

	// --- Names (player nicknames) ---
	private final JPanel playerNicknamesPanel = new JPanel();
	private final JTextField nicknamePlayerField = new JTextField();
	private final JTextField nicknameField = new JTextField();
	private final JPanel pendingRenamesPanel = new JPanel();

	// --- Stats (team performance) ---
	private final JPanel performanceListPanel = new JPanel();
	private final JLabel performanceKillsLabel = new JLabel();
	private JButton copyPerformanceButton;
	private PerformanceRound shownPerformanceRound;
	private Boolean lastPerformanceEnabled;
	private final JComboBox<String> performanceRoundBox = new JComboBox<>();
	private List<PerformanceRound> lastPerformanceRounds = Collections.emptyList();
	/**
	 * The round picked in the dropdown by its start time, or null to follow the newest.
	 */
	private Long selectedRoundStart;
	private boolean updatingRoundBox;
	private boolean performanceEnabled;

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
	private final JSpinner newLocationPrioritySpinner = new JSpinner(new SpinnerNumberModel(0, -99, 99, 1));
	// Not final: built by createWidthClampedTextArea() inside buildLocationMapperSection().
	private JTextArea overlapsArea;
	private final JPanel savedLocationsPanel = new JPanel();
	private List<WildernessLocation> lastWildernessLocations = Collections.emptyList();
	// Id (not instance) of the location open in the edit form - the store hands out a fresh merged
	// list on every change, so instance identity doesn't survive a save.
	private String editingWildernessLocationId;
	private int lastHiddenLocationCount;
	private final JLabel hiddenLocationsLabel = new JLabel();
	private final JButton restoreHiddenButton = new JButton();
	private JTextArea exportStatusArea;

	private static final Color LOCATION_EDITED_COLOR = ColorScheme.BRAND_ORANGE;
	private static final Color LOCATION_YOURS_COLOR = new Color(80, 200, 80);

	PrepToolsPanel(PrepToolsPlugin plugin, ConfigListEditor lists, SpriteManager spriteManager, ItemManager itemManager)
	{
		// false: no outer scroll pane - each tab has its own (wrapTabContent()). With both, a tall tab
		// grows inside the outer pane and its own pane swallows the mouse wheel without scrolling.
		// Same as TimeTrackingPanel (RuneLite core).
		super(false);
		this.plugin = plugin;
		this.lists = lists;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		MaterialTab callersTab = new MaterialTab("Callers", tabGroup, wrapTabContent(buildCallersSection()));
		MaterialTab snipeTab = new MaterialTab("Snipe", tabGroup, wrapTabContent(buildSnipeListSection()));
		MaterialTab spamTab = new MaterialTab("Spam", tabGroup, wrapTabContent(buildSpamSection()));
		MaterialTab clanIdsTab = new MaterialTab("Clan Info", tabGroup, wrapTabContent(buildClanIdsSection()));
		MaterialTab fcLogTab = new MaterialTab("FC", tabGroup, wrapTabContent(buildFriendsChatLogSection()));
		MaterialTab capesTab = new MaterialTab("Capes", tabGroup, wrapTabContent(buildTeamCapeNamesSection()));
		MaterialTab locationTab = new MaterialTab("Location", tabGroup, wrapTabContent(buildLocationMapperSection()));
		MaterialTab statsTab = new MaterialTab("Stats", tabGroup, wrapTabContent(buildPerformanceSection()));
		MaterialTab namesTab = new MaterialTab("Names", tabGroup, wrapTabContent(buildPlayerNicknamesSection()));

		// Centre icon+text within each tab's own grid cell (not just the icon relative to the text -
		// see loadTabIcon()/loadTabItemIcon() for that part) - applied up front, before the icon even
		// loads, so nothing shifts once it does.
		for (MaterialTab tab : new MaterialTab[] {callersTab, snipeTab, spamTab, clanIdsTab, fcLogTab, capesTab, locationTab, statsTab, namesTab})
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
		loadTabIcon(spamTab, SpriteID.OPTIONS_CHAT);
		loadTabIcon(clanIdsTab, SpriteID.TAB_STATS);
		loadTabIcon(fcLogTab, SpriteID.TAB_FRIENDS_CHAT);
		loadTabItemIcon(capesTab, TeamCapeOverlay.teamCapeItemId(30));
		loadTabIcon(locationTab, SpriteID.MINIMAP_ORB_WORLD_MAP_PLANET);
		loadTabIcon(statsTab, SpriteID.TAB_COMBAT);
		loadTabIcon(namesTab, SpriteID.TAB_IGNORES);

		// 3 columns, as many rows as needed (3/3/3 for nine tabs). Tried 4 columns when the Spam tab
		// made it seven - at sidebar width that cut every label off ("Loc...", "Cla..."), so wider
		// cells with an uneven last row it is.
		tabGroup.setLayout(new GridLayout(0, 3, 4, 4));
		tabGroup.setBorder(new EmptyBorder(8, 8, 6, 8));
		tabGroup.addTab(callersTab);
		tabGroup.addTab(snipeTab);
		tabGroup.addTab(spamTab);
		tabGroup.addTab(clanIdsTab);
		tabGroup.addTab(fcLogTab);
		tabGroup.addTab(capesTab);
		tabGroup.addTab(locationTab);
		tabGroup.addTab(statsTab);
		tabGroup.addTab(namesTab);
		tabGroup.select(callersTab);

		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		rebuild(new ArrayList<>());
		rebuildSnipeList(new ArrayList<>());
		rebuildSpamMessages(new ArrayList<>(), "", false);
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
		JPanel wrapped = new ViewportWidthPanel();
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
	 * A tab's scrollable content: always exactly as wide as the visible area, so content can
	 * never end up wider than the sidebar and get clipped on the right, and text areas wrap to the
	 * real width. Also scrolls a sensible amount per mouse-wheel notch - Swing's default for a plain
	 * JPanel is 1 pixel.
	 */
	private static class ViewportWidthPanel extends JPanel implements Scrollable
	{
		private static final int SCROLL_UNIT = 16;

		ViewportWidthPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return SCROLL_UNIT;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(SCROLL_UNIT, visibleRect.height - SCROLL_UNIT);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
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
				+ "Untick the box next to a caller to keep them in the list "
				+ "but stop using them (no highlight, never primary) - tick it "
				+ "again to bring them back.\n\n"
				+ "✎ edits a caller's name or nickname.\n\n"
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
		section.add(buildCallerEditPanel());
		section.add(addCallerPanel);
		return section;
	}

	private JPanel buildCallerEditPanel()
	{
		callerEditPanel.setLayout(new BoxLayout(callerEditPanel, BoxLayout.Y_AXIS));
		callerEditPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		callerEditPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 4, 4, 4)));
		callerEditPanel.setAlignmentX(LEFT_ALIGNMENT);
		callerEditPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		callerEditTitle.setForeground(Color.WHITE);
		callerEditTitle.setFont(FontManager.getRunescapeBoldFont());
		callerEditTitle.setAlignmentX(LEFT_ALIGNMENT);

		callerEditNameField.setFont(FontManager.getRunescapeSmallFont());
		callerEditNameField.setToolTipText("In-game name");
		callerEditNameField.addActionListener(e -> saveCallerEdit());
		callerEditNicknameField.setFont(FontManager.getRunescapeSmallFont());
		callerEditNicknameField.setToolTipText("Nickname shown instead of their name - leave blank for none");
		callerEditNicknameField.addActionListener(e -> saveCallerEdit());

		JPanel fields = new JPanel(new GridLayout(2, 1, 0, 2));
		fields.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		fields.setAlignmentX(LEFT_ALIGNMENT);
		fields.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		fields.add(buildLabeledField("Name", callerEditNameField));
		fields.add(buildLabeledField("Nickname", callerEditNicknameField));

		JButton saveButton = createSmallButton("Save", "Save changes to this caller");
		saveButton.addActionListener(e -> saveCallerEdit());
		JButton cancelButton = createSmallButton("Cancel", "Discard changes");
		cancelButton.addActionListener(e -> closeCallerEdit());

		JPanel buttonsRow = new JPanel(new GridLayout(1, 2, 6, 0));
		buttonsRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonsRow.setAlignmentX(LEFT_ALIGNMENT);
		buttonsRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		buttonsRow.setBorder(new EmptyBorder(4, 0, 0, 0));
		buttonsRow.add(saveButton);
		buttonsRow.add(cancelButton);

		callerEditPanel.add(callerEditTitle);
		callerEditPanel.add(Box.createVerticalStrut(4));
		callerEditPanel.add(fields);
		callerEditPanel.add(buttonsRow);
		callerEditPanel.setVisible(false);

		// Wrapper so the gap below the form disappears along with it while hidden.
		JPanel wrapper = new JPanel();
		wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		wrapper.add(callerEditPanel);
		Component gap = Box.createVerticalStrut(TIER_GAP);
		wrapper.add(gap);
		callerEditPanel.addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentShown(ComponentEvent e)
			{
				gap.setVisible(true);
			}

			@Override
			public void componentHidden(ComponentEvent e)
			{
				gap.setVisible(false);
			}
		});
		gap.setVisible(false);
		return wrapper;
	}

	private void openCallerEdit(String realName, String nickname)
	{
		editingCallerName = realName;
		callerEditTitle.setText("Edit " + realName);
		callerEditNameField.setText(realName);
		callerEditNicknameField.setText(nickname == null ? "" : nickname);
		callerEditPanel.setVisible(true);
		callerEditNameField.requestFocusInWindow();
		revalidate();
		repaint();
	}

	private void closeCallerEdit()
	{
		editingCallerName = null;
		callerEditPanel.setVisible(false);
		revalidate();
		repaint();
	}

	private void saveCallerEdit()
	{
		if (editingCallerName == null)
		{
			return;
		}

		String newName = callerEditNameField.getText();
		if (newName == null || newName.trim().isEmpty())
		{
			return;
		}

		// Plugin rejects a rename onto another caller's name - leave the form open in that case.
		if (lists.editCaller(editingCallerName, newName.trim(), callerEditNicknameField.getText()))
		{
			closeCallerEdit();
		}
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

		lists.addCallerFromPanel(text.trim());
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
	void rebuild(List<CallerEntry> entries)
	{
		listPanel.removeAll();
		radiosByKey.clear();
		statusLabelsByKey.clear();
		enabledByKey.clear();
		buttonGroup = new ButtonGroup();

		for (int i = 0; i < entries.size(); i++)
		{
			CallerEntry entry = entries.get(i);
			addRow(Text.standardize(entry.name), entry.name, entry.nickname, entry.enabled, i == 0, i == entries.size() - 1);
		}

		// The caller being edited was removed (or renamed) from somewhere else - close the form.
		if (editingCallerName != null && entries.stream().noneMatch(e -> e.name.equals(editingCallerName)))
		{
			closeCallerEdit();
		}

		revalidate();
		repaint();
	}

	private final Map<String, Boolean> enabledByKey = new HashMap<>();

	/**
	 * One caller row, two lines: the radio button + in-game name on top, and the nickname (if any)
	 * plus on-screen status underneath - one line held name, nickname and status together and was
	 * too cramped to read. Edit/delete sit on the right, reorder arrows + enable box on the left.
	 */
	private void addRow(String key, String realName, String nickname, boolean enabled, boolean isFirst, boolean isLast)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 4));

		JRadioButton radio = new JRadioButton(realName);
		radio.setBackground(row.getBackground());
		radio.setForeground(Color.WHITE);
		radio.setFont(FontManager.getRunescapeFont());
		radio.setToolTipText(realName);
		radio.setEnabled(enabled);
		if (!enabled)
		{
			radio.setForeground(Color.GRAY);
		}
		radio.addActionListener(e ->
		{
			if (updatingProgrammatically)
			{
				return;
			}
			lists.setPinnedCaller(realName);
		});

		buttonGroup.add(radio);
		radiosByKey.put(key, radio);
		enabledByKey.put(key, enabled);

		JCheckBox enabledBox = new JCheckBox();
		enabledBox.setSelected(enabled);
		enabledBox.setBackground(row.getBackground());
		enabledBox.setFocusable(false);
		enabledBox.setToolTipText(enabled
			? "Enabled - untick to keep " + realName + " in the list without using them as a caller"
			: "Disabled - tick to use " + realName + " as a caller again");
		enabledBox.addActionListener(e -> lists.setCallerEnabled(realName, enabledBox.isSelected()));

		JPanel westPanel = new JPanel();
		westPanel.setLayout(new BoxLayout(westPanel, BoxLayout.X_AXIS));
		westPanel.setBackground(row.getBackground());
		westPanel.add(createReorderButtons(realName, isFirst, isLast));
		westPanel.add(enabledBox);

		westPanel.setBackground(row.getBackground());
		for (Component c : westPanel.getComponents())
		{
			c.setBackground(row.getBackground());
		}

		JLabel status = new JLabel();
		status.setFont(FontManager.getRunescapeSmallFont());
		statusLabelsByKey.put(key, status);

		JLabel nicknameLabel = new JLabel(nickname != null ? nickname : "");
		nicknameLabel.setFont(FontManager.getRunescapeSmallFont());
		nicknameLabel.setForeground(enabled ? CALLER_HIGHLIGHT_COLOR : Color.GRAY);

		// Second line, indented to line up with the radio's text rather than its circle.
		Icon radioIcon = UIManager.getIcon("RadioButton.icon");
		int indent = (radioIcon != null ? radioIcon.getIconWidth() : 16) + radio.getIconTextGap();
		JPanel detailLine = new JPanel(new BorderLayout(6, 0));
		detailLine.setBackground(row.getBackground());
		detailLine.setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0));
		detailLine.add(nicknameLabel, BorderLayout.CENTER);
		detailLine.add(status, BorderLayout.EAST);

		JPanel centerPanel = new JPanel(new GridLayout(2, 1, 0, 0));
		centerPanel.setBackground(row.getBackground());
		centerPanel.add(radio);
		centerPanel.add(detailLine);

		JPanel eastPanel = new JPanel();
		eastPanel.setLayout(new BoxLayout(eastPanel, BoxLayout.X_AXIS));
		eastPanel.setBackground(row.getBackground());
		JButton editButton = createSmallButton("✎", "Edit " + realName + "'s name/nickname");
		editButton.addActionListener(e -> openCallerEdit(realName, nickname));
		eastPanel.add(editButton);
		eastPanel.add(Box.createHorizontalStrut(2));
		eastPanel.add(createDeleteButton(realName, lists::removeCaller));

		row.add(westPanel, BorderLayout.WEST);
		row.add(centerPanel, BorderLayout.CENTER);
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
			upButton.addActionListener(e -> lists.moveCaller(realName, -1));
			buttons.add(upButton);
		}

		if (!isLast)
		{
			JButton downButton = createSmallButton("▼", "Move down (later fallback priority)");
			downButton.addActionListener(e -> lists.moveCaller(realName, 1));
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

				if (!enabledByKey.getOrDefault(key, true))
				{
					status.setForeground(Color.GRAY);
					status.setText("disabled");
				}
				else if (selected)
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

		lists.addSnipeFromPanel(text.trim());
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
		eastPanel.add(createDeleteButton(realName, lists::removeSnipedName));

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
	// Spam - saved public-chat messages for the Spammer hotkey (PrepToolsConfig#spamHotkey, F10 by
	// default). Same row idiom as the caller list: click a message to select it (what the hotkey
	// sends), ✕ to delete, or tick Random to send a random one each press instead.
	// ============================================================================================

	private JPanel buildSpamSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Spammer", 0);

		JTextArea instructions = createDescriptionArea(
			"Press the Spammer hotkey (F10 by default - change it in the plugin's "
				+ "Spammer settings) to type the selected message into your chatbox "
				+ "for public chat - press Enter to send it.\n\n"
				+ "Click a message to select it, ✕ to remove it, or tick Random to "
				+ "use a random one each press instead. Max "
				+ ConfigListEditor.SPAM_MESSAGE_MAX_LENGTH + " characters, and at most one "
				+ "message every 5 seconds.");

		spamRandomCheckBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spamRandomCheckBox.setForeground(Color.WHITE);
		spamRandomCheckBox.setFont(FontManager.getRunescapeSmallFont());
		spamRandomCheckBox.setFocusable(false);
		spamRandomCheckBox.setAlignmentX(LEFT_ALIGNMENT);
		spamRandomCheckBox.addActionListener(e ->
		{
			if (!updatingSpamProgrammatically)
			{
				lists.setSpamRandom(spamRandomCheckBox.isSelected());
			}
		});

		spamListPanel.setLayout(new GridLayout(0, 1, 0, 6));
		spamListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spamListPanel.setAlignmentX(LEFT_ALIGNMENT);
		spamListPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		addSpamField.setToolTipText("Message to save, max " + ConfigListEditor.SPAM_MESSAGE_MAX_LENGTH + " characters");
		addSpamField.setFont(FontManager.getRunescapeSmallFont());
		limitTextFieldLength(addSpamField, ConfigListEditor.SPAM_MESSAGE_MAX_LENGTH);
		addSpamField.addActionListener(e -> addSpamFromField());

		JButton addSpamButton = createSmallButton("+ Add", "Save this message");
		addSpamButton.addActionListener(e -> addSpamFromField());

		JPanel addSpamPanel = new JPanel(new BorderLayout(4, 0));
		addSpamPanel.setAlignmentX(LEFT_ALIGNMENT);
		addSpamPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		addSpamPanel.add(addSpamField, BorderLayout.CENTER);
		addSpamPanel.add(addSpamButton, BorderLayout.EAST);
		styleInputRow(addSpamPanel);

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(spamRandomCheckBox);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(spamListPanel);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(addSpamPanel);
		return section;
	}

	private void addSpamFromField()
	{
		String text = addSpamField.getText();
		if (text == null || text.trim().isEmpty())
		{
			return;
		}

		lists.addSpamMessageFromPanel(text.trim());
		addSpamField.setText("");
	}

	/**
	 * Rebuilds the saved spam message rows. Called at startup and whenever the saved messages, the
	 * selected message, or the Random setting change.
	 */
	void rebuildSpamMessages(List<String> messages, String selectedMessage, boolean random)
	{
		updatingSpamProgrammatically = true;
		try
		{
			spamRandomCheckBox.setSelected(random);
			spamListPanel.removeAll();
			spamButtonGroup = new ButtonGroup();

			if (messages.isEmpty())
			{
				JLabel empty = new JLabel("(no saved messages)");
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setFont(FontManager.getRunescapeSmallFont());
				spamListPanel.add(empty);
			}

			// Same fallback the hotkey itself uses: first message if the saved selection is gone.
			String effectiveSelection = messages.contains(selectedMessage) ? selectedMessage
				: (messages.isEmpty() ? null : messages.get(0));

			for (String message : messages)
			{
				addSpamRow(message, message.equals(effectiveSelection), random);
			}
		}
		finally
		{
			updatingSpamProgrammatically = false;
		}

		revalidate();
		repaint();
	}

	private static final int SPAM_ROW_DISPLAY_LENGTH = 22;

	private void addSpamRow(String message, boolean selected, boolean random)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

		// Display text is shortened (full text in the tooltip) - a JRadioButton doesn't wrap or
		// truncate itself, so a long message would push the delete button off the sidebar.
		String display = message.length() > SPAM_ROW_DISPLAY_LENGTH
			? message.substring(0, SPAM_ROW_DISPLAY_LENGTH - 1) + "…"
			: message;
		JRadioButton radio = new JRadioButton(display);
		radio.setBackground(row.getBackground());
		radio.setForeground(random ? Color.LIGHT_GRAY : Color.WHITE);
		radio.setFont(FontManager.getRunescapeFont());
		radio.setToolTipText(random ? message + " (Random is on - selection is ignored)" : message);
		radio.setSelected(selected);
		radio.addActionListener(e ->
		{
			if (!updatingSpamProgrammatically)
			{
				lists.setSelectedSpamMessage(message);
			}
		});
		spamButtonGroup.add(radio);

		row.add(radio, BorderLayout.CENTER);
		row.add(createDeleteButton(message, lists::removeSpamMessage), BorderLayout.EAST);
		spamListPanel.add(row);
	}

	// ============================================================================================
	// Clan Info - the team's weapon styles, then player ids. Ids: see MENU_ORDER_PID_INVESTIGATION.md at the repo root for what a player's id is and
	// why the min/max of a stack matter for right-click menu Attack-option exposure. Clan/FC members
	// only (not enemies) - confirmed with the user that OSRS's own friendly-fire protection means an
	// enemy's menu on your team's pile is built exclusively from your team's ids, so an enemy's own
	// id is never a factor in who on your side is exposed.
	// ============================================================================================

	private static final Color CALLER_HIGHLIGHT_COLOR = new Color(230, 180, 60);
	private static final Color ID_RISK_COLOR = new Color(220, 100, 90);
	private static final Color YOU_HIGHLIGHT_COLOR = new Color(100, 180, 255);
	private static final Color MELEE_COLOR = new Color(230, 90, 80);
	private static final Color RANGED_COLOR = new Color(100, 200, 90);
	private static final Color MAGIC_COLOR = new Color(100, 160, 255);

	private JPanel buildClanIdsSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Clan Info", 0);

		copyClanWeaponsButton = createSmallButton("Copy", "Copy the weapon styles to clipboard, one style per line");
		copyClanWeaponsButton.setEnabled(false);
		copyClanWeaponsButton.addActionListener(e -> copyClanWeaponsToClipboard());
		JTextArea weaponsDescription = createDescriptionArea(
			"Your team on screen (you included) by the weapon they're wielding - the same weapon "
				+ "lists as the infobox's \"Attacking you\" row.");

		clanWeaponsPanel.setLayout(new BoxLayout(clanWeaponsPanel, BoxLayout.Y_AXIS));
		clanWeaponsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		clanWeaponsPanel.setAlignmentX(LEFT_ALIGNMENT);
		clanWeaponsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		copyClanIdsButton = createSmallButton("Copy", "Copy the player ids to clipboard, one player per line");
		copyClanIdsButton.setEnabled(false);
		copyClanIdsButton.addActionListener(e -> copyClanIdsToClipboard());

		JTextArea instructions = createDescriptionArea(
			"You + friends chat and clan members currently on screen, sorted by id. "
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
		section.add(createSubHeader("Weapons", copyClanWeaponsButton));
		section.add(Box.createVerticalStrut(4));
		section.add(weaponsDescription);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(clanWeaponsPanel);
		section.add(Box.createVerticalStrut(TIER_GAP * 2));
		section.add(createSubHeader("Player IDs", copyClanIdsButton));
		section.add(Box.createVerticalStrut(4));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(clanIdsListPanel);
		updateClanIds(Collections.emptyList());
		updateClanWeapons(new ClanWeaponSummary(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0));
		return section;
	}

	/**
	 * A bold sub-heading with a small button (e.g. Copy) on the right - same look as the FC tab's
	 * "Log" header.
	 */
	private JPanel createSubHeader(String text, Component button)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeBoldFont());

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		header.add(label, BorderLayout.WEST);
		header.add(button, BorderLayout.EAST);
		return header;
	}

	/**
	 * Rebuilds the weapon columns - only when the breakdown actually changed since last tick, so
	 * the labels aren't torn down and rebuilt every 600ms for nothing.
	 */
	void updateClanWeapons(ClanWeaponSummary summary)
	{
		if (summary.equals(lastClanWeapons))
		{
			return;
		}
		lastClanWeapons = summary;
		copyClanWeaponsButton.setEnabled(!summary.melee.isEmpty() || !summary.ranged.isEmpty() || !summary.magic.isEmpty());

		clanWeaponsPanel.removeAll();

		// GridLayout: three equal-width columns; a name too long for its column is cut off with
		// "..." by JLabel itself, full name in the tooltip.
		JPanel columns = new JPanel(new GridLayout(1, 3, 4, 0));
		columns.setBackground(ColorScheme.DARK_GRAY_COLOR);
		columns.setAlignmentX(LEFT_ALIGNMENT);
		columns.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		columns.add(buildWeaponColumn("Melee", MELEE_COLOR, summary.melee));
		columns.add(buildWeaponColumn("Ranged", RANGED_COLOR, summary.ranged));
		columns.add(buildWeaponColumn("Magic", MAGIC_COLOR, summary.magic));
		clanWeaponsPanel.add(columns);

		if (summary.unknown > 0)
		{
			JLabel unknown = new JLabel(summary.unknown + " with a weapon not in any list");
			unknown.setFont(FontManager.getRunescapeSmallFont());
			unknown.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			unknown.setAlignmentX(LEFT_ALIGNMENT);
			unknown.setBorder(new EmptyBorder(6, 0, 0, 0));
			clanWeaponsPanel.add(unknown);
		}

		revalidate();
		repaint();
	}

	private JPanel buildWeaponColumn(String style, Color color, List<String> names)
	{
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		column.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(2, 0, 0, 0, color),
			new EmptyBorder(4, 4, 4, 4)));

		JLabel header = new JLabel(style + " " + names.size());
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(color);
		column.add(header);

		for (String name : names)
		{
			JLabel label = new JLabel(name);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setForeground(Color.WHITE);
			label.setToolTipText(name);
			column.add(label);
		}
		return column;
	}

	private void copyClanWeaponsToClipboard()
	{
		ClanWeaponSummary summary = lastClanWeapons;
		if (summary == null)
		{
			return;
		}

		String text = "Melee (" + summary.melee.size() + "): " + String.join(", ", summary.melee)
			+ "\nRanged (" + summary.ranged.size() + "): " + String.join(", ", summary.ranged)
			+ "\nMagic (" + summary.magic.size() + "): " + String.join(", ", summary.magic);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private void copyClanIdsToClipboard()
	{
		if (lastClanIdRows.isEmpty())
		{
			return;
		}

		StringBuilder sb = new StringBuilder();
		for (ClanIdRow row : lastClanIdRows)
		{
			sb.append(clanIdDisplayName(row)).append(" - id ").append(row.id).append('\n');
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString().trim()), null);
	}

	private static String clanIdDisplayName(ClanIdRow row)
	{
		String displayName = row.nickname != null ? row.name + " (" + row.nickname + ")" : row.name;
		return row.isYou ? displayName + " (you)" : displayName;
	}

	/**
	 * Rebuilds the Clan Info tab's player id list - only when the rows differ from last tick's, as
	 * this is called every tick.
	 */
	void updateClanIds(List<ClanIdRow> rows)
	{
		// Same rows as last tick (the usual case) - nothing to rebuild.
		if (clanIdsInitialised && rows.equals(lastClanIdRows))
		{
			return;
		}
		clanIdsInitialised = true;
		lastClanIdRows = new ArrayList<>(rows);
		copyClanIdsButton.setEnabled(!rows.isEmpty());
		clanIdsListPanel.removeAll();

		if (rows.isEmpty())
		{
			JLabel empty = new JLabel("(no friends chat/clan members on screen)");
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

		JLabel nameLabel = new JLabel(clanIdDisplayName(row));
		// You get your own colour, distinct from callers and everyone else - and it wins if you're
		// also a caller, since "where am I in this list" is what you're looking for.
		nameLabel.setFont(row.isYou || row.isCaller ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		nameLabel.setForeground(row.isYou ? YOU_HIGHLIGHT_COLOR : row.isCaller ? CALLER_HIGHLIGHT_COLOR : Color.WHITE);

		JLabel idLabel = new JLabel("id " + row.id);
		idLabel.setFont(FontManager.getRunescapeSmallFont());
		idLabel.setForeground(atRiskId ? ID_RISK_COLOR : ColorScheme.LIGHT_GRAY_COLOR);

		rowPanel.add(nameLabel, BorderLayout.CENTER);
		rowPanel.add(idLabel, BorderLayout.EAST);
		return rowPanel;
	}

	// ============================================================================================
	// Stats tab (experimental) - TeamPerformanceTracker: per teammate, in order of death.
	// ============================================================================================

	private static final Color PERFORMANCE_DIED_COLOR = new Color(230, 90, 80);

	private JPanel buildPerformanceSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Team Performance", 0);
		JTextArea description = createDescriptionArea(
			"Experimental - turn on \"Team performance tracker\" in the Experimental settings. "
				+ "Teammates in the order they died, survivors last: when they died, pizzas eaten, "
				+ "how many of the kills they were around for they were on the pile for, and their "
				+ "average place getting onto those piles (1 = first on; a missed pile counts as last). "
				+ "Each trip through the Clan Wars portal starts a new round - pick earlier ones "
				+ "below. Times count from the end of the Clan Wars countdown, and nothing eaten "
				+ "before it counts (that's boost potions). Only what happens on your screen counts.");

		performanceRoundBox.setFont(FontManager.getRunescapeSmallFont());
		performanceRoundBox.setAlignmentX(LEFT_ALIGNMENT);
		performanceRoundBox.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 26));
		performanceRoundBox.addActionListener(e ->
		{
			if (updatingRoundBox)
			{
				return;
			}
			int index = performanceRoundBox.getSelectedIndex();
			int newest = lastPerformanceRounds.size() - 1;
			selectedRoundStart = index < 0 || index == newest ? null : lastPerformanceRounds.get(index).startMillis;
			showSelectedRound();
		});

		JButton resetButton = createSmallButton("Reset", "Clear every round and start a new one");
		resetButton.addActionListener(e -> plugin.resetPerformance());
		copyPerformanceButton = createSmallButton("Copy", "Copy the table to clipboard, tab-separated (pastes into a spreadsheet)");
		copyPerformanceButton.setEnabled(false);
		copyPerformanceButton.addActionListener(e -> copyPerformanceToClipboard());
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(resetButton);
		buttons.add(copyPerformanceButton);

		performanceListPanel.setLayout(new GridLayout(0, 1, 0, 4));
		performanceListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		performanceListPanel.setAlignmentX(LEFT_ALIGNMENT);
		performanceListPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		performanceKillsLabel.setFont(FontManager.getRunescapeSmallFont());
		performanceKillsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		performanceKillsLabel.setAlignmentX(LEFT_ALIGNMENT);

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(description);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(createSubHeader("Teammates", buttons));
		section.add(Box.createVerticalStrut(4));
		section.add(performanceRoundBox);
		section.add(Box.createVerticalStrut(4));
		section.add(performanceKillsLabel);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(performanceListPanel);
		updatePerformance(Collections.emptyList(), false);
		return section;
	}

	/**
	 * New numbers from the tracker: refreshes the round dropdown (newest first in time order, the
	 * newest labelled "now") and shows the picked round.
	 */
	void updatePerformance(List<PerformanceRound> rounds, boolean enabled)
	{
		lastPerformanceRounds = rounds;
		performanceEnabled = enabled;

		SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");
		updatingRoundBox = true;
		performanceRoundBox.removeAllItems();
		int selected = rounds.size() - 1;
		for (int i = 0; i < rounds.size(); i++)
		{
			PerformanceRound round = rounds.get(i);
			String label = "Round from " + format.format(new Date(round.startMillis)) + (i == rounds.size() - 1 ? " (now)" : "");
			performanceRoundBox.addItem(label);
			if (selectedRoundStart != null && round.startMillis == selectedRoundStart)
			{
				selected = i;
			}
		}
		if (selected >= 0)
		{
			performanceRoundBox.setSelectedIndex(selected);
		}
		updatingRoundBox = false;
		performanceRoundBox.setVisible(rounds.size() > 1);
		showSelectedRound();
	}

	private void showSelectedRound()
	{
		int index = performanceRoundBox.getSelectedIndex();
		PerformanceRound round = index >= 0 && index < lastPerformanceRounds.size() ? lastPerformanceRounds.get(index) : null;
		performanceKillsLabel.setText("Kills this round: " + (round != null ? round.kills : 0));
		showPerformanceRows(round, performanceEnabled);
	}

	/**
	 * Rebuilds the Stats list - only when the round shown or its rows changed.
	 */
	private void showPerformanceRows(PerformanceRound round, boolean enabled)
	{
		if (Objects.equals(round, shownPerformanceRound) && Boolean.valueOf(enabled).equals(lastPerformanceEnabled))
		{
			return;
		}
		shownPerformanceRound = round;
		lastPerformanceEnabled = enabled;
		List<PerformanceRow> rows = round != null ? round.rows : Collections.emptyList();
		copyPerformanceButton.setEnabled(!rows.isEmpty());
		performanceListPanel.removeAll();

		if (rows.isEmpty())
		{
			JLabel empty = new JLabel(enabled ? "(no teammates seen yet)" : "(tracker is off - Experimental settings)");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			performanceListPanel.add(empty);
		}
		for (PerformanceRow row : rows)
		{
			performanceListPanel.add(buildPerformanceRow(row, round.startMillis));
		}

		revalidate();
		repaint();
	}

	private JPanel buildPerformanceRow(PerformanceRow row, long roundStartMillis)
	{
		boolean died = !row.deathMillis.isEmpty();
		JPanel rowPanel = new JPanel();
		rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.Y_AXIS));
		rowPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rowPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, died ? PERFORMANCE_DIED_COLOR : ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 5, 3, 3)));

		JLabel nameLabel = new JLabel(performanceName(row, true));
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(row.isYou ? YOU_HIGHLIGHT_COLOR : row.isCaller ? CALLER_HIGHLIGHT_COLOR : Color.WHITE);
		JLabel diedLabel = new JLabel(deathTimes(row, roundStartMillis));
		diedLabel.setFont(FontManager.getRunescapeSmallFont());
		diedLabel.setForeground(died ? PERFORMANCE_DIED_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setOpaque(false);
		top.add(nameLabel, BorderLayout.CENTER);
		top.add(diedLabel, BorderLayout.EAST);
		top.setAlignmentX(LEFT_ALIGNMENT);
		rowPanel.add(top);

		JLabel detail = new JLabel("Ate " + row.pizzas + (row.pizzas == 1 ? " pizza" : " pizzas")
			+ " (" + row.pizzaHp() + " hp) · On pile " + row.onPile + "/" + row.pilesAround);
		detail.setFont(FontManager.getRunescapeSmallFont());
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		detail.setToolTipText("On the pile for " + row.onPile + " of the " + row.pilesAround + " kills they were around for");
		detail.setAlignmentX(LEFT_ALIGNMENT);
		rowPanel.add(detail);

		double order = row.averagePileOrder();
		int missed = row.pilesAround - row.onPile;
		JLabel orderLabel = new JLabel("Pile order " + (order < 0 ? "-" : String.format("avg %.1f", order))
			+ (missed > 0 ? " · missed " + missed : ""));
		orderLabel.setFont(FontManager.getRunescapeSmallFont());
		orderLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		orderLabel.setToolTipText("Average place getting onto a pile (1 = first teammate on). A missed pile counts as last place.");
		orderLabel.setAlignmentX(LEFT_ALIGNMENT);
		rowPanel.add(orderLabel);
		return rowPanel;
	}

	/**
	 * "Name [Nick] (you)" - the nickname only where there's room for it (the Copy has its own
	 * column).
	 */
	private static String performanceName(PerformanceRow row, boolean withNickname)
	{
		String name = row.name;
		if (withNickname && row.nickname != null)
		{
			name += " [" + row.nickname + "]";
		}
		return row.isYou ? name + " (you)" : row.isCaller ? name + " (caller)" : name;
	}

	/**
	 * "Died 2m 31s" (time into the fight; more than one for the wilderness), or "Alive". Not
	 * "2:31" - a spreadsheet reads that as a time of day (2:31 AM).
	 */
	private static String deathTimes(PerformanceRow row, long roundStartMillis)
	{
		if (row.deathMillis.isEmpty())
		{
			return "Alive";
		}
		List<String> times = new ArrayList<>();
		for (long millis : row.deathMillis)
		{
			times.add(formatDuration(Math.max(0, millis - roundStartMillis) / 1000));
		}
		return "Died " + String.join(", ", times);
	}

	private static String formatDuration(long seconds)
	{
		return String.format("%dm %02ds", seconds / 60, seconds % 60);
	}

	private void copyPerformanceToClipboard()
	{
		PerformanceRound round = shownPerformanceRound;
		if (round == null || round.rows.isEmpty())
		{
			return;
		}

		StringBuilder sb = new StringBuilder("Player\tNickname\tDied (time into fight)\tPizzas ate (hp healed)"
			+ "\tKills on the pile for\tKills nearby\tPiles missed\tAvg pile order (1 = first on)\n");
		for (PerformanceRow row : round.rows)
		{
			sb.append(performanceName(row, false)).append('\t')
				.append(row.nickname != null ? row.nickname : "").append('\t')
				.append(row.deathMillis.isEmpty() ? "Alive" : deathTimes(row, round.startMillis).substring("Died ".length())).append('\t')
				.append(row.pizzas).append(" (").append(row.pizzaHp()).append("hp)").append('\t')
				.append(row.onPile).append('\t')
				.append(row.pilesAround).append('\t')
				.append(row.pilesAround - row.onPile).append('\t')
				.append(row.averagePileOrder() < 0 ? "" : String.format("%.1f", row.averagePileOrder())).append('\n');
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString().trim()), null);
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
		limitTextFieldLength(teamCapeNameField, ConfigLists.TEAM_CAPE_NAME_MAX_LENGTH);
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

		lists.setTeamCapeName(teamNumber, name.trim());
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
			deleteButton.addActionListener(e -> lists.removeTeamCapeName(teamNumber));

			row.add(label, BorderLayout.CENTER);
			row.add(deleteButton, BorderLayout.EAST);
			teamCapeNamesPanel.add(row);
		}

		revalidate();
		repaint();
	}

	// ============================================================================================
	// Names tab (experimental) - your own nicknames for players (PlayerNicknameTracker).
	// ============================================================================================

	private JPanel buildPlayerNicknamesSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = createSectionTitle("Player Nicknames", 0);
		JTextArea instructions = createDescriptionArea(
			"Your own name for any player, shown after their name in the right-click menu and when you "
				+ "hover them (over their head too, if turned on in the Nicknames settings). \"Set nickname\": "
				+ "shift-right-click a player in game, right-click a name in the friends list, friends chat, "
				+ "clan list or chatbox - or type their name and nickname below.");
		JTextArea renameHelp = createDescriptionArea(
			"Name changes: only your friends and ignore lists follow an account through a rename - "
				+ "add a player to one and their nickname follows them, even after several renames "
				+ "while you were logged out (the lists are compared between logins). Anyone else "
				+ "keeps the nickname on the old name. Your Friend Notes notes also count as "
				+ "nicknames, and move with them.");

		nicknamePlayerField.setToolTipText("Player's in-game name");
		nicknamePlayerField.setFont(FontManager.getRunescapeSmallFont());
		nicknameField.setToolTipText("Nickname");
		nicknameField.setFont(FontManager.getRunescapeSmallFont());
		nicknameField.addActionListener(e -> savePlayerNicknameFromFields());
		JButton saveButton = createSmallButton("Save", "Save this player's nickname (an empty nickname removes it)");
		saveButton.addActionListener(e -> savePlayerNicknameFromFields());

		JPanel fields = new JPanel(new GridLayout(1, 2, 4, 0));
		fields.setBackground(ColorScheme.DARK_GRAY_COLOR);
		fields.add(nicknamePlayerField);
		fields.add(nicknameField);
		JPanel entryPanel = new JPanel(new BorderLayout(4, 0));
		entryPanel.setAlignmentX(LEFT_ALIGNMENT);
		entryPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		entryPanel.add(fields, BorderLayout.CENTER);
		entryPanel.add(saveButton, BorderLayout.EAST);
		styleInputRow(entryPanel);

		pendingRenamesPanel.setLayout(new BoxLayout(pendingRenamesPanel, BoxLayout.Y_AXIS));
		pendingRenamesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		pendingRenamesPanel.setAlignmentX(LEFT_ALIGNMENT);
		pendingRenamesPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		pendingRenamesPanel.setVisible(false);

		playerNicknamesPanel.setLayout(new GridLayout(0, 1, 0, 4));
		playerNicknamesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		playerNicknamesPanel.setAlignmentX(LEFT_ALIGNMENT);
		playerNicknamesPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(renameHelp);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(pendingRenamesPanel);
		section.add(entryPanel);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(playerNicknamesPanel);
		return section;
	}

	/**
	 * Nicknamed players who left your friends/ignore list since the last login without a sure
	 * match - pick their new name, or dismiss.
	 */
	void updatePendingRenames(List<FriendRenameTracker.PendingRename> pending)
	{
		pendingRenamesPanel.removeAll();
		pendingRenamesPanel.setVisible(!pending.isEmpty());
		if (!pending.isEmpty())
		{
			pendingRenamesPanel.add(createSubHeader("Possible renames", new JLabel()));
			pendingRenamesPanel.add(Box.createVerticalStrut(4));
			pendingRenamesPanel.add(createDescriptionArea(
				"These nicknamed players left your list since you last logged in, and new names "
					+ "arrived. Pick who they are now, or ✕ if they were just removed."));
			pendingRenamesPanel.add(Box.createVerticalStrut(4));
		}
		for (FriendRenameTracker.PendingRename rename : pending)
		{
			JLabel label = new JLabel(rename.nickname + " (was " + rename.oldName + ")");
			label.setForeground(Color.WHITE);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setAlignmentX(LEFT_ALIGNMENT);

			JComboBox<String> candidates = new JComboBox<>(rename.candidates.toArray(new String[0]));
			candidates.setFont(FontManager.getRunescapeSmallFont());
			JButton moveButton = createSmallButton("Move", "Move the nickname to this name");
			moveButton.addActionListener(e -> plugin.resolvePendingRename(rename.oldName, (String) candidates.getSelectedItem()));
			JButton dismissButton = createSmallButton("✕", "Not a rename - keep the nickname on the old name");
			dismissButton.setForeground(new Color(220, 80, 80));
			dismissButton.addActionListener(e -> plugin.dismissPendingRename(rename.oldName));
			JPanel buttons = new JPanel(new GridLayout(1, 2, 2, 0));
			buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
			buttons.add(moveButton);
			buttons.add(dismissButton);

			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
			row.add(candidates, BorderLayout.CENTER);
			row.add(buttons, BorderLayout.EAST);
			styleInputRow(row);

			pendingRenamesPanel.add(label);
			pendingRenamesPanel.add(row);
		}
		if (!pending.isEmpty())
		{
			pendingRenamesPanel.add(Box.createVerticalStrut(TIER_GAP));
		}
		revalidate();
		repaint();
	}

	private void savePlayerNicknameFromFields()
	{
		String name = nicknamePlayerField.getText();
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		lists.setPlayerNickname(name.trim(), nicknameField.getText());
		nicknamePlayerField.setText("");
		nicknameField.setText("");
	}

	/**
	 * Rebuilds the nickname list, sorted by nickname. Clicking ✎ loads a row into the fields above.
	 */
	void rebuildPlayerNicknames(Map<String, String> nicknames)
	{
		playerNicknamesPanel.removeAll();

		List<Map.Entry<String, String>> sorted = new ArrayList<>(nicknames.entrySet());
		sorted.sort(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER));
		for (Map.Entry<String, String> entry : sorted)
		{
			String name = entry.getKey();
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			row.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

			JLabel label = new JLabel(entry.getValue() + "  -  " + name);
			label.setForeground(Color.WHITE);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setToolTipText(name + " is \"" + entry.getValue() + "\"");

			JButton editButton = createSmallButton("✎", "Edit this nickname");
			editButton.addActionListener(e ->
			{
				nicknamePlayerField.setText(name);
				nicknameField.setText(entry.getValue());
				nicknameField.requestFocusInWindow();
			});
			JButton deleteButton = createSmallButton("✕", "Remove this nickname");
			deleteButton.setForeground(new Color(220, 80, 80));
			deleteButton.addActionListener(e -> lists.setPlayerNickname(name, null));
			JPanel buttons = new JPanel(new GridLayout(1, 2, 2, 0));
			buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
			buttons.add(editButton);
			buttons.add(deleteButton);

			row.add(label, BorderLayout.CENTER);
			row.add(buttons, BorderLayout.EAST);
			playerNicknamesPanel.add(row);
		}

		if (sorted.isEmpty())
		{
			JLabel empty = new JLabel("(no nicknames yet)");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			playerNicknamesPanel.add(empty);
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
				+ "Click ✎ on a saved area below to edit its name, coordinates or priority.");

		JTextArea priorityHelp = createDescriptionArea(
			"Overlapping areas: when you're inside more than one box, the one with the "
				+ "HIGHEST priority (P) wins. On equal priority the smaller box wins (the more "
				+ "specific place), then whichever is higher in the list. New boxes default to P0; "
				+ "the OpenOSRS starter locations are P-1, so mapped ones win over them. "
				+ "\"Also in\" under Current lists the boxes that lost.");

		JTextArea shippedHelp = createDescriptionArea(
			"The list comes with the plugin (the team's shared map, white). Your changes sit on "
				+ "top and are kept when the plugin updates: boxes you add (green), shipped boxes "
				+ "you edit (orange - ↺ undoes the edit), and shipped boxes you remove (hidden - "
				+ "Restore brings them back). Export copies the whole list to the clipboard and "
				+ "saves it to ~/.runelite/pvptools-exports - that file is how the shipped map "
				+ "gets updated, and doubles as a backup.");

		overlapsArea = createWidthClampedTextArea("");
		overlapsArea.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		overlapsArea.setVisible(false);

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
		coordCard.add(overlapsArea);
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

		newLocationPrioritySpinner.setFont(FontManager.getRunescapeSmallFont());
		newLocationPrioritySpinner.setToolTipText("Priority - higher wins where boxes overlap (default 0)");
		((JSpinner.DefaultEditor) newLocationPrioritySpinner.getEditor()).getTextField().setColumns(2);

		JPanel saveLocationEast = new JPanel(new BorderLayout(4, 0));
		saveLocationEast.add(newLocationPrioritySpinner, BorderLayout.WEST);
		saveLocationEast.add(saveLocationButton, BorderLayout.EAST);

		JPanel saveLocationPanel = new JPanel(new BorderLayout(4, 0));
		saveLocationPanel.setAlignmentX(LEFT_ALIGNMENT);
		saveLocationPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		saveLocationPanel.add(newLocationNameField, BorderLayout.CENTER);
		saveLocationPanel.add(saveLocationEast, BorderLayout.EAST);
		styleInputRow(saveLocationPanel);

		JLabel savedTitle = createSectionTitle("Locations", 0);

		JButton exportButton = createSmallButton("Export", "Copy the whole list (shipped file format) to the clipboard and save it to ~/.runelite/pvptools-exports");
		exportButton.addActionListener(e -> exportLocations());

		hiddenLocationsLabel.setFont(FontManager.getRunescapeSmallFont());
		hiddenLocationsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		restoreHiddenButton.setText("Restore");
		restoreHiddenButton.setToolTipText("Bring back every shipped location you've removed");
		restoreHiddenButton.setFont(FontManager.getRunescapeSmallFont());
		restoreHiddenButton.setMargin(new Insets(0, 3, 0, 3));
		restoreHiddenButton.setFocusable(false);
		restoreHiddenButton.addActionListener(e -> plugin.restoreHiddenWildernessLocations());

		JPanel hiddenPanel = new JPanel(new BorderLayout(4, 0));
		hiddenPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		hiddenPanel.add(hiddenLocationsLabel, BorderLayout.CENTER);
		hiddenPanel.add(restoreHiddenButton, BorderLayout.EAST);

		JPanel locationsToolbar = new JPanel(new BorderLayout(6, 0));
		locationsToolbar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		locationsToolbar.setAlignmentX(LEFT_ALIGNMENT);
		locationsToolbar.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		locationsToolbar.add(hiddenPanel, BorderLayout.CENTER);
		locationsToolbar.add(exportButton, BorderLayout.EAST);

		exportStatusArea = createWidthClampedTextArea("");
		exportStatusArea.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		exportStatusArea.setVisible(false);

		// BoxLayout, not GridLayout: GridLayout makes every row as tall as the tallest, so opening
		// one row's edit form stretched every other row to match - fine with a handful of saved
		// areas, unusable with the ~80 the imported starter list brings.
		savedLocationsPanel.setLayout(new BoxLayout(savedLocationsPanel, BoxLayout.Y_AXIS));
		savedLocationsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		savedLocationsPanel.setAlignmentX(LEFT_ALIGNMENT);
		savedLocationsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		section.add(title);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(instructions);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(priorityHelp);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(shippedHelp);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(coordCard);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(saveLocationPanel);
		section.add(Box.createVerticalStrut(TIER_GAP * 2));
		section.add(savedTitle);
		section.add(Box.createVerticalStrut(TIER_GAP));
		section.add(locationsToolbar);
		section.add(exportStatusArea);
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

		plugin.saveLocationFromPanel(text.trim(), (Integer) newLocationPrioritySpinner.getValue());
		newLocationNameField.setText("");
	}

	/**
	 * Updates the live coordinate readout (your own position and the hovered tile) and corner
	 * labels - safe to call every tick.
	 */
	void updateLocationMapper(WorldPoint current, Integer wildernessLevel, String matchedName,
		List<String> overlaps, WorldPoint hovered, String hoveredName, WorldPoint cornerA, WorldPoint cornerB)
	{
		currentCoordLabel.setText("Current: " + formatCoord(current, wildernessLevel, matchedName));
		String overlapsText = overlaps.isEmpty() ? "" : "Also in (lost on priority): " + String.join(", ", overlaps);
		if (!overlapsText.equals(overlapsArea.getText()))
		{
			overlapsArea.setText(overlapsText);
			overlapsArea.setVisible(!overlaps.isEmpty());
			revalidate();
		}
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

	private void exportLocations()
	{
		String json = plugin.exportWildernessLocations();
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(json), null);
		java.io.File file = plugin.saveWildernessLocationsExport(json);
		exportStatusArea.setText("Exported " + lastWildernessLocations.size() + " locations to the clipboard"
			+ (file != null ? " and " + file.getAbsolutePath() : " (couldn't save a file copy - see the log)")
			+ ". To update the shipped map, replace wilderness_locations.json in the plugin with it.");
		exportStatusArea.setVisible(true);
		revalidate();
		repaint();
	}

	/**
	 * Rebuilds the locations list with edit/delete buttons per entry. Called at startup and
	 * whenever a location is added/removed/edited/restored. Caches the list so
	 * rebuildSavedLocations() (the edit/cancel toggle) can rebuild locally without needing a round
	 * trip through the plugin.
	 */
	void updateWildernessLocations(List<WildernessLocation> locations, int hiddenCount)
	{
		lastWildernessLocations = locations;
		lastHiddenLocationCount = hiddenCount;
		rebuildSavedLocations();
	}

	private void rebuildSavedLocations()
	{
		savedLocationsPanel.removeAll();

		hiddenLocationsLabel.setText(lastWildernessLocations.size() + " locations"
			+ (lastHiddenLocationCount > 0 ? ", " + lastHiddenLocationCount + " hidden" : ""));
		restoreHiddenButton.setVisible(lastHiddenLocationCount > 0);

		// Shown highest priority first, then alphabetically - easier to find one among dozens than
		// save order is. Display only: the saved list itself keeps its order (the final tie-break).
		List<WildernessLocation> sorted = new ArrayList<>(lastWildernessLocations);
		sorted.sort(Comparator.<WildernessLocation>comparingInt(l -> -l.priority)
			.thenComparing(l -> l.name, String.CASE_INSENSITIVE_ORDER));

		for (WildernessLocation location : sorted)
		{
			JPanel row = location.id.equals(editingWildernessLocationId)
				? buildLocationEditRow(location)
				: buildLocationViewRow(location);
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, row.getPreferredSize().height));
			savedLocationsPanel.add(row);
			savedLocationsPanel.add(Box.createVerticalStrut(4));
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
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		String originText;
		switch (location.origin)
		{
			case EDITED:
				nameLabel.setForeground(LOCATION_EDITED_COLOR);
				originText = "shipped, edited by you";
				break;
			case YOURS:
				nameLabel.setForeground(LOCATION_YOURS_COLOR);
				originText = "added by you";
				break;
			case SHIPPED:
			default:
				nameLabel.setForeground(Color.WHITE);
				originText = "shipped";
				break;
		}
		nameLabel.setToolTipText(
			"(" + location.minX + "," + location.minY + ") - (" + location.maxX + "," + location.maxY + "), plane "
				+ location.plane + ", priority " + location.priority + " - " + originText);

		JLabel priorityLabel = new JLabel("P" + location.priority);
		priorityLabel.setFont(FontManager.getRunescapeSmallFont());
		priorityLabel.setForeground(location.priority > 0 ? ColorScheme.BRAND_ORANGE
			: location.priority < 0 ? Color.GRAY : ColorScheme.LIGHT_GRAY_COLOR);
		priorityLabel.setToolTipText("Priority - higher wins where boxes overlap");

		JButton editButton = createSmallButton("✎", "Edit " + location.name);
		editButton.addActionListener(e ->
		{
			editingWildernessLocationId = location.id;
			rebuildSavedLocations();
		});

		boolean yours = location.origin == WildernessLocation.Origin.YOURS;
		JButton deleteButton = createSmallButton("✕", yours
			? "Delete " + location.name
			: "Hide " + location.name + " (shipped - Restore brings it back)");
		deleteButton.setForeground(new Color(220, 80, 80));
		deleteButton.addActionListener(e -> plugin.removeWildernessLocation(location.id));

		JPanel buttons = new JPanel(new GridLayout(1, 0, 4, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		if (location.origin == WildernessLocation.Origin.EDITED)
		{
			JButton revertButton = createSmallButton("↺", "Undo your edit - back to the shipped " + location.name);
			revertButton.addActionListener(e -> plugin.revertWildernessLocation(location.id));
			buttons.add(revertButton);
		}
		buttons.add(editButton);
		buttons.add(deleteButton);

		row.add(priorityLabel, BorderLayout.WEST);
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
		JTextField priorityField = new JTextField(String.valueOf(location.priority));

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
		installArrowKeyAdjust(priorityField, pushPreview);
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
		coordsGrid.add(buildLabeledField("Priority", priorityField));

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
				int priority = Integer.parseInt(priorityField.getText().trim());

				editingWildernessLocationId = null;
				plugin.clearLocationPreview();
				plugin.updateWildernessLocation(location.id, newName, corner1X, corner1Y, corner2X, corner2Y, plane, priority);
			}
			catch (NumberFormatException ex)
			{
				// Invalid numeric input - leave the row in edit mode rather than saving garbage.
			}
		});

		JButton cancelButton = createSmallButton("Cancel", "Discard changes");
		cancelButton.addActionListener(e ->
		{
			editingWildernessLocationId = null;
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
}
