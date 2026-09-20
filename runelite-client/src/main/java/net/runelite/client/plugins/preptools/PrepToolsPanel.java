package net.runelite.client.plugins.preptools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
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
import javax.swing.border.EmptyBorder;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
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
	private final JPanel listPanel = new JPanel();
	private final JTextField addCallerField = new JTextField();
	private final JTextArea missingArea;
	private final JButton copyMissingButton;

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

	// --- Friends chat log ---
	private JTextArea friendsChatLogArea;

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

	PrepToolsPanel(PrepToolsPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Primary Caller");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(new EmptyBorder(0, 0, 6, 0));

		JTextArea instructions = createWidthClampedTextArea(
			"When your primary caller dies or leaves screen, secondary "
				+ "callers take over automatically, top to bottom through the "
				+ "list below.\n\n"
				+ "Use the ▲/▼ arrows to reorder who takes over next, or ✕ "
				+ "to remove someone.\n\n"
				+ "Add a nickname with \":Nickname\" after a name, e.g. "
				+ "Xx_Bob_xX:Bob - works in the Add field below the list too.\n\n"
				+ "To switch primary right now, just click a name below.");
		instructions.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		instructions.setBorder(new EmptyBorder(0, 0, 10, 0));

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
		addCallerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		addCallerPanel.setAlignmentX(LEFT_ALIGNMENT);
		addCallerPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		addCallerPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
		addCallerPanel.add(addCallerField, BorderLayout.CENTER);
		addCallerPanel.add(addCallerButton, BorderLayout.EAST);

		JLabel missingTitle = new JLabel("Missing (Friends Chat)");
		missingTitle.setForeground(Color.WHITE);
		missingTitle.setFont(FontManager.getRunescapeBoldFont());

		copyMissingButton = createSmallButton("Copy", "Copy missing players to clipboard, one per line");
		copyMissingButton.setEnabled(false);
		copyMissingButton.addActionListener(e -> copyMissingToClipboard());

		JPanel missingHeader = new JPanel(new BorderLayout());
		missingHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		missingHeader.setAlignmentX(LEFT_ALIGNMENT);
		missingHeader.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		missingHeader.setBorder(new EmptyBorder(14, 0, 4, 0));
		missingHeader.add(missingTitle, BorderLayout.WEST);
		missingHeader.add(copyMissingButton, BorderLayout.EAST);

		// Width-clamped like `instructions` above, not a plain JLabel - a JLabel doesn't wrap on
		// its own, and this sentence is long enough to run past the sidebar's width and get cut
		// off instead of wrapping to a second line.
		JTextArea missingSubtitle = createWidthClampedTextArea("Friends chat members not in your current game view:");
		missingSubtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		missingSubtitle.setBorder(new EmptyBorder(0, 0, 6, 0));

		missingArea = createWidthClampedTextArea("(none)");
		missingArea.setForeground(Color.WHITE);

		add(title);
		add(instructions);
		add(listPanel);
		add(addCallerPanel);
		add(missingHeader);
		add(missingSubtitle);
		add(missingArea);

		add(buildSnipeListSection());
		add(buildFriendsChatLogSection());
		add(buildTeamCapeNamesSection());
		add(buildLocationMapperSection());

		add(Box.createVerticalGlue());

		rebuild(new ArrayList<>());
		rebuildSnipeList(new ArrayList<>());
		rebuildTeamCapeNames(new HashMap<>());
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

		JLabel title = new JLabel("Snipe List");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(new EmptyBorder(14, 0, 6, 0));

		JTextArea instructions = createWidthClampedTextArea(
			"Always highlighted on sight, regardless of caller/target status. "
				+ "Shift-right-click a player in game and choose 'Add to snipe list', "
				+ "or type a name below.");
		instructions.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		instructions.setBorder(new EmptyBorder(0, 0, 8, 0));

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
		addSnipePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		addSnipePanel.setAlignmentX(LEFT_ALIGNMENT);
		addSnipePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		addSnipePanel.setBorder(new EmptyBorder(8, 0, 0, 0));
		addSnipePanel.add(addSnipeField, BorderLayout.CENTER);
		addSnipePanel.add(addSnipeButton, BorderLayout.EAST);

		section.add(title);
		section.add(instructions);
		section.add(snipeListPanel);
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
	// Friends chat log
	// ============================================================================================

	private JPanel buildFriendsChatLogSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel title = new JLabel("Friends Chat Log");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(new EmptyBorder(14, 0, 6, 0));

		JTextArea subtitle = createWidthClampedTextArea("Newest first. Join/leave/death activity, with a timestamp.");
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setBorder(new EmptyBorder(0, 0, 6, 0));

		friendsChatLogArea = createWidthClampedTextArea("(no activity yet)");
		friendsChatLogArea.setForeground(Color.WHITE);

		// Fixed-height scroll pane rather than letting the log grow the whole sidebar unbounded -
		// with up to friendsChatLogMaxEntries (200 by default) lines, an unclamped panel would make
		// everything below it (the Location Mapper) require excessive scrolling to reach.
		JScrollPane scrollPane = new JScrollPane(friendsChatLogArea,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setAlignmentX(LEFT_ALIGNMENT);
		scrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR));
		scrollPane.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, LOG_SCROLL_HEIGHT));
		scrollPane.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, LOG_SCROLL_HEIGHT));

		section.add(title);
		section.add(subtitle);
		section.add(scrollPane);
		return section;
	}

	/**
	 * Replaces the log display's content - called whenever a new entry is logged (join/leave/
	 * death), not on every tick, since the underlying log itself only changes on those events.
	 */
	void updateFriendsChatLog(Deque<FriendsChatLogEntry> log)
	{
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

		JLabel title = new JLabel("Team Capes");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(new EmptyBorder(14, 0, 6, 0));

		JTextArea instructions = createWidthClampedTextArea(
			"Custom name shown below a team cape's icon (max 5 characters). "
				+ "Team numbers without a name here just show the icon.");
		instructions.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		instructions.setBorder(new EmptyBorder(0, 0, 8, 0));

		teamCapeNumberSpinner.setFont(FontManager.getRunescapeSmallFont());
		((JSpinner.DefaultEditor) teamCapeNumberSpinner.getEditor()).getTextField().setColumns(3);

		teamCapeNameField.setToolTipText("Name, max 5 characters, e.g. EoP");
		teamCapeNameField.setFont(FontManager.getRunescapeSmallFont());
		limitTextFieldLength(teamCapeNameField, PrepToolsPlugin.TEAM_CAPE_NAME_MAX_LENGTH);
		teamCapeNameField.addActionListener(e -> saveTeamCapeNameFromFields());

		JButton saveButton = createSmallButton("Save", "Save this team number's custom name");
		saveButton.addActionListener(e -> saveTeamCapeNameFromFields());

		JPanel entryPanel = new JPanel(new BorderLayout(4, 0));
		entryPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		entryPanel.setAlignmentX(LEFT_ALIGNMENT);
		entryPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		entryPanel.add(teamCapeNumberSpinner, BorderLayout.WEST);
		entryPanel.add(teamCapeNameField, BorderLayout.CENTER);
		entryPanel.add(saveButton, BorderLayout.EAST);

		teamCapeNamesPanel.setLayout(new GridLayout(0, 1, 0, 4));
		teamCapeNamesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		teamCapeNamesPanel.setAlignmentX(LEFT_ALIGNMENT);
		teamCapeNamesPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		teamCapeNamesPanel.setBorder(new EmptyBorder(8, 0, 0, 0));

		section.add(title);
		section.add(instructions);
		section.add(entryPanel);
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

		JLabel title = new JLabel("Location Mapper");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(new EmptyBorder(14, 0, 6, 0));

		JTextArea instructions = createWidthClampedTextArea(
			"Walk to a landmark, click Set Corner A, walk to the opposite "
				+ "corner of the area, click Set Corner B, name it, then Save. "
				+ "The saved box is used to detect and spam your location.");
		instructions.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		instructions.setBorder(new EmptyBorder(0, 0, 8, 0));

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
		cornerButtonsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cornerButtonsPanel.setAlignmentX(LEFT_ALIGNMENT);
		cornerButtonsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		cornerButtonsPanel.setBorder(new EmptyBorder(6, 0, 6, 0));
		cornerButtonsPanel.add(setCornerAButton);
		cornerButtonsPanel.add(setCornerBButton);

		newLocationNameField.setToolTipText("Name for this area, e.g. Ferox Enclave");
		newLocationNameField.setFont(FontManager.getRunescapeSmallFont());
		newLocationNameField.addActionListener(e -> saveLocationFromField());

		JButton saveLocationButton = createSmallButton("Save", "Save this named area (both corners must be set)");
		saveLocationButton.addActionListener(e -> saveLocationFromField());

		JPanel saveLocationPanel = new JPanel(new BorderLayout(4, 0));
		saveLocationPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		saveLocationPanel.setAlignmentX(LEFT_ALIGNMENT);
		saveLocationPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		saveLocationPanel.add(newLocationNameField, BorderLayout.CENTER);
		saveLocationPanel.add(saveLocationButton, BorderLayout.EAST);

		JLabel savedTitle = new JLabel("Saved Locations");
		savedTitle.setForeground(Color.WHITE);
		savedTitle.setFont(FontManager.getRunescapeBoldFont());
		savedTitle.setAlignmentX(LEFT_ALIGNMENT);
		savedTitle.setBorder(new EmptyBorder(10, 0, 6, 0));

		savedLocationsPanel.setLayout(new GridLayout(0, 1, 0, 4));
		savedLocationsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		savedLocationsPanel.setAlignmentX(LEFT_ALIGNMENT);
		savedLocationsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		section.add(title);
		section.add(instructions);
		section.add(currentCoordLabel);
		section.add(hoveredCoordLabel);
		section.add(cornerALabel);
		section.add(cornerBLabel);
		section.add(cornerButtonsPanel);
		section.add(saveLocationPanel);
		section.add(savedTitle);
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
	 * Rebuilds the "Saved Locations" list with a delete button per entry. Called at startup and
	 * whenever a location is added/removed.
	 */
	void updateWildernessLocations(List<WildernessLocation> locations)
	{
		savedLocationsPanel.removeAll();

		for (WildernessLocation location : locations)
		{
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			row.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

			JLabel nameLabel = new JLabel(location.name);
			nameLabel.setForeground(Color.WHITE);
			nameLabel.setFont(FontManager.getRunescapeSmallFont());
			nameLabel.setToolTipText(
				"(" + location.minX + "," + location.minY + ") - (" + location.maxX + "," + location.maxY + "), plane " + location.plane);

			JButton deleteButton = createSmallButton("✕", "Remove " + location.name);
			deleteButton.setForeground(new Color(220, 80, 80));
			deleteButton.addActionListener(e -> plugin.removeWildernessLocation(location));

			row.add(nameLabel, BorderLayout.CENTER);
			row.add(deleteButton, BorderLayout.EAST);
			savedLocationsPanel.add(row);
		}

		revalidate();
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
	}
}
