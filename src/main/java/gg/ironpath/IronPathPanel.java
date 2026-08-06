package gg.ironpath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class IronPathPanel extends PluginPanel
{
    private static final Color BRASS = new Color(213, 173, 85);
    private static final Color MOSS = new Color(126, 163, 106);
    private final JLabel status = new JLabel("Not linked");
    private final JLabel character = new JLabel("No character");
    private final JLabel observed = new JLabel("0 observed kills this session");
    private final JPanel goals = new JPanel();
    private final JButton connectButton = new JButton("Connect account");
    private Runnable connectAction = () -> {};
    private Runnable syncAction = () -> {};

    IronPathPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel brand = new JLabel("IRON PATH");
        brand.setForeground(BRASS);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 16f));
        brand.setAlignmentX(LEFT_ALIGNMENT);
        add(brand);

        JLabel subtitle = new JLabel("FIELD JOURNAL");
        subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        subtitle.setFont(subtitle.getFont().deriveFont(9f));
        subtitle.setAlignmentX(LEFT_ALIGNMENT);
        add(subtitle);
        add(Box.createRigidArea(new Dimension(0, 12)));

        JPanel connection = new JPanel(new BorderLayout(8, 3));
        connection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        connection.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(61, 67, 59)),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        connection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        character.setForeground(Color.WHITE);
        status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        status.setFont(status.getFont().deriveFont(9f));
        connection.add(character, BorderLayout.NORTH);
        connection.add(status, BorderLayout.SOUTH);
        add(connection);
        add(Box.createRigidArea(new Dimension(0, 8)));

        connectButton.setAlignmentX(LEFT_ALIGNMENT);
        connectButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        connectButton.addActionListener(event -> connectAction.run());
        add(connectButton);

        JButton sync = new JButton("Sync now");
        sync.setAlignmentX(LEFT_ALIGNMENT);
        sync.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        sync.addActionListener(event -> syncAction.run());
        add(Box.createRigidArea(new Dimension(0, 4)));
        add(sync);
        add(Box.createRigidArea(new Dimension(0, 12)));

        JLabel heading = new JLabel("ACTIVE GOALS");
        heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        heading.setFont(heading.getFont().deriveFont(9f));
        heading.setAlignmentX(LEFT_ALIGNMENT);
        add(heading);
        add(Box.createRigidArea(new Dimension(0, 5)));

        goals.setLayout(new BoxLayout(goals, BoxLayout.Y_AXIS));
        goals.setBackground(ColorScheme.DARK_GRAY_COLOR);
        goals.setAlignmentX(LEFT_ALIGNMENT);
        add(goals);
        add(Box.createVerticalGlue());

        observed.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        observed.setFont(observed.getFont().deriveFont(9f));
        observed.setAlignmentX(LEFT_ALIGNMENT);
        add(observed);

        JButton dashboard = new JButton("Open web dashboard");
        dashboard.setAlignmentX(LEFT_ALIGNMENT);
        dashboard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        dashboard.addActionListener(event -> openDashboard());
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(dashboard);
    }

    void setActions(Runnable connectAction, Runnable syncAction)
    {
        this.connectAction = connectAction;
        this.syncAction = syncAction;
    }

    void setConnected(boolean connected, String characterName, String detail)
    {
        SwingUtilities.invokeLater(() ->
        {
            character.setText(characterName == null ? "No character" : characterName);
            status.setText(detail);
            status.setForeground(connected ? MOSS : ColorScheme.LIGHT_GRAY_COLOR);
            connectButton.setText(connected ? "Reconnect account" : "Connect account");
        });
    }

    void setObservedKills(int count)
    {
        SwingUtilities.invokeLater(() -> observed.setText(count + " observed kills this session"));
    }

    void setGoals(List<IronPathDtos.GoalSummary> summaries)
    {
        SwingUtilities.invokeLater(() ->
        {
            goals.removeAll();
            List<IronPathDtos.GoalSummary> safe = summaries == null ? Collections.emptyList() : summaries;
            if (safe.isEmpty())
            {
                JLabel empty = new JLabel("No active goals yet");
                empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                empty.setBorder(BorderFactory.createEmptyBorder(10, 4, 10, 4));
                goals.add(empty);
            }
            for (IronPathDtos.GoalSummary goal : safe)
            {
                JPanel row = new JPanel(new GridLayout(2, 1));
                row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 2, 0, 0, kindColor(goal.kind)),
                    BorderFactory.createEmptyBorder(7, 8, 7, 7)));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 49));
                JLabel title = new JLabel(goal.title == null ? "Untitled goal" : goal.title);
                title.setForeground(Color.WHITE);
                JLabel kind = new JLabel(goal.kind == null ? "GOAL" : goal.kind.replace('_', ' ').toUpperCase());
                kind.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                kind.setFont(kind.getFont().deriveFont(8f));
                row.add(title);
                row.add(kind);
                goals.add(row);
                goals.add(Box.createRigidArea(new Dimension(0, 4)));
            }
            goals.revalidate();
            goals.repaint();
        });
    }

    private static Color kindColor(String kind)
    {
        if ("grind".equals(kind)) return new Color(183, 90, 77);
        if ("banked_xp".equals(kind)) return MOSS;
        return BRASS;
    }

    private void openDashboard()
    {
        try
        {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI("https://ironpath.gg"));
        }
        catch (Exception ignored)
        {
            status.setText("Open ironpath.gg in your browser");
        }
    }
}
