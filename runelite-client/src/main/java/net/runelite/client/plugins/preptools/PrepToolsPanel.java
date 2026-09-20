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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Text;

/**
 * Sidebar panel for switching the primary caller with a single click, in a fixed, stable
 * location - no need to find the right player in a crowded PK fight. Also shows which friends
 * chat members aren't currently in your loaded game view.
 *
 * Caller rows are rebuilt only when the "Callers" config text changes (see
 * PrepToolsPlugin#onConfigChanged); the on-screen/primary status text on each row, and the
 * missing-members list, are refreshed every game tick via updateStatus()/updateMissingMembers()
 * without rebuilding the caller row widgets themselves, so this stays cheap and flicker-free
 * during combat.
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
		add(Box.createVerticalGlue());

		rebuild(new ArrayList<>());
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
		eastPanel.add(createDeleteButton(realName));

		row.add(createReorderButtons(realName, isFirst, isLast), BorderLayout.WEST);
		row.add(radio, BorderLayout.CENTER);
		row.add(eastPanel, BorderLayout.EAST);
		listPanel.add(row);
	}

	/**
	 * Removes a caller from the "Callers" config field entirely - see PrepToolsPlugin#removeCaller,
	 * which (like moveCaller) persists straight to config, so onConfigChanged picks it up and
	 * rebuilds this panel automatically; no separate confirmation dialog, same lightweight
	 * "just re-add the name if you didn't mean it" model as the reorder arrows.
	 */
	private JButton createDeleteButton(String realName)
	{
		JButton deleteButton = createSmallButton("✕", "Remove " + realName + " from Callers");
		deleteButton.setForeground(new Color(220, 80, 80));
		deleteButton.addActionListener(e -> plugin.removeCaller(realName));
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

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
	}
}
