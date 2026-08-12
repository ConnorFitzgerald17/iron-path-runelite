package gg.ironpath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class IronPathPanel extends PluginPanel
{
    private static final Color BRASS = new Color(213, 173, 85);
    private static final Color MOSS = new Color(126, 163, 106);
    private static final Color RUST = new Color(183, 90, 77);
    private static final Color BORDER = new Color(61, 67, 59);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final JLabel status = new JLabel("Not linked");
    private final JLabel character = new JLabel("No character");
    private final JLabel sessionValue = statValue("0");
    private final JLabel pendingValue = statValue("0");
    private final JLabel syncValue = statValue("NEVER");
    private final JLabel syncMeta = statLabel("AUTO · 2M");
    private final JLabel collectionStatus = new JLabel("Open Collection Log, then click Sync Log");
    private final JPanel recentCollections = verticalPanel();
    private final JPanel recentKills = verticalPanel();
    private final JPanel goals = verticalPanel();
    private final JPanel completedGoals = verticalPanel();
    private final JButton completedToggle = new JButton("COMPLETED (0)  ▸");
    private final JButton connectButton = new JButton("Connect");
    private final JButton syncButton = new JButton("Sync now");
    private final JButton collectionButton = new JButton("Sync Collection Log");
    private boolean connected;
    private boolean collectionSyncing;
    private boolean collectionFailed;
    private Runnable connectAction = () -> {};
    private Runnable syncAction = () -> {};
    private Runnable collectionAction = () -> {};
    private GoalStatusAction goalStatusAction = (goalId, nextStatus, callback) -> callback.accept(false);
    private List<IronPathGoalProgress> currentGoalProgress = Collections.emptyList();
    private boolean completedExpanded;

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

        JLabel subtitle = new JLabel("GRIND COMPANION");
        subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        subtitle.setFont(subtitle.getFont().deriveFont(9f));
        subtitle.setAlignmentX(LEFT_ALIGNMENT);
        add(subtitle);
        add(gap(12));

        JPanel connection = new JPanel(new BorderLayout(8, 3));
        connection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        connection.setBorder(cardBorder(BRASS));
        connection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        character.setForeground(Color.WHITE);
        character.setFont(character.getFont().deriveFont(Font.BOLD, 12f));
        status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        status.setFont(status.getFont().deriveFont(9f));
        connection.add(character, BorderLayout.NORTH);
        connection.add(status, BorderLayout.SOUTH);
        add(connection);
        add(gap(7));

        JPanel actions = new JPanel(new GridLayout(1, 2, 5, 0));
        actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        connectButton.addActionListener(event -> connectAction.run());
        syncButton.addActionListener(event -> syncAction.run());
        collectionButton.addActionListener(event -> collectionAction.run());
        actions.add(connectButton);
        actions.add(syncButton);
        connectButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        syncButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        add(actions);
        collectionButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        collectionButton.setAlignmentX(LEFT_ALIGNMENT);
        collectionButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        add(gap(5));
        add(collectionButton);

        add(gap(14));
        add(sectionHeading("SESSION ACTIVITY"));
        add(gap(5));

        JPanel stats = new JPanel(new GridLayout(1, 3, 4, 0));
        stats.setBackground(ColorScheme.DARK_GRAY_COLOR);
        stats.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        stats.add(statCard(sessionValue, statLabel("SESSION KC")));
        stats.add(statCard(pendingValue, statLabel("PENDING")));
        stats.add(statCard(syncValue, syncMeta));
        add(stats);

        add(gap(10));
        collectionStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        collectionStatus.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        collectionStatus.setBorder(cardBorder(MOSS));
        collectionStatus.setAlignmentX(LEFT_ALIGNMENT);
        collectionStatus.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        add(collectionStatus);

        add(gap(14));
        add(sectionHeading("RECENT COLLECTIONS LOGGED"));
        add(gap(5));
        add(recentCollections);
        renderRecentCollections(Collections.emptyList());

        add(gap(14));
        add(sectionHeading("RECENT KILLS"));
        add(gap(5));
        add(recentKills);
        renderRecentKills(Collections.emptyList());

        add(gap(14));
        add(sectionHeading("ACTIVE GOALS"));
        add(gap(5));
        add(goals);
        completedToggle.setAlignmentX(LEFT_ALIGNMENT);
        completedToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        completedToggle.setMargin(new Insets(2, 7, 2, 7));
        completedToggle.setHorizontalAlignment(SwingConstants.LEFT);
        completedToggle.setVisible(false);
        completedToggle.addActionListener(event ->
        {
            completedExpanded = !completedExpanded;
            renderGoals(currentGoalProgress);
        });
        add(gap(7));
        add(completedToggle);
        add(gap(4));
        add(completedGoals);
        renderGoals(Collections.emptyList());

        JButton dashboard = new JButton("Open dashboard  ↗");
        dashboard.setAlignmentX(LEFT_ALIGNMENT);
        dashboard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        dashboard.addActionListener(event -> openDashboard());
        add(gap(10));
        add(dashboard);
    }

    void setActions(Runnable connectAction, Runnable syncAction, Runnable collectionAction, GoalStatusAction goalStatusAction)
    {
        this.connectAction = connectAction;
        this.syncAction = syncAction;
        this.collectionAction = collectionAction;
        this.goalStatusAction = goalStatusAction;
    }

    void setConnected(boolean connected, String characterName, String detail)
    {
        SwingUtilities.invokeLater(() ->
        {
            this.connected = connected;
            character.setText(characterName == null ? "No character linked" : characterName);
            status.setText(detail);
            status.setForeground(connected ? MOSS : ColorScheme.LIGHT_GRAY_COLOR);
            connectButton.setText(connected ? "Relink" : "Connect");
            syncButton.setEnabled(connected);
            collectionButton.setEnabled(connected && !collectionSyncing);
        });
    }

    void setRecentCollections(List<String> itemNames)
    {
        SwingUtilities.invokeLater(() -> renderRecentCollections(itemNames == null ? Collections.emptyList() : itemNames));
    }

    void setSyncState(Instant lastSyncedAt, boolean syncing, int pending, boolean autoSync)
    {
        SwingUtilities.invokeLater(() ->
        {
            pendingValue.setText(Integer.toString(pending));
            pendingValue.setForeground(pending == 0 ? MOSS : BRASS);
            syncValue.setText(syncing ? "…" : lastSyncedAt == null
                ? "NEVER" : CLOCK.format(lastSyncedAt.atZone(ZoneId.systemDefault())));
            syncValue.setForeground(syncing ? BRASS : MOSS);
            syncMeta.setText(autoSync ? "AUTO · 2M" : "MANUAL");
            syncButton.setEnabled(connected && !syncing);
            syncButton.setText(syncing ? "Syncing…" : "Sync now");
        });
    }

    void setKillActivity(int sessionKills, int pending, List<IronPathDtos.KillCount> recent)
    {
        SwingUtilities.invokeLater(() ->
        {
            sessionValue.setText(Integer.toString(sessionKills));
            pendingValue.setText(Integer.toString(pending));
            pendingValue.setForeground(pending == 0 ? MOSS : BRASS);
            renderRecentKills(recent == null ? Collections.emptyList() : recent);
        });
    }

    void setCollectionLogState(int sections, int pending, boolean awaitingSearch)
    {
        SwingUtilities.invokeLater(() ->
        {
            if ((collectionSyncing && !awaitingSearch) || (collectionFailed && pending > 0)) return;
            if (awaitingSearch)
            {
                collectionSyncing = true;
                collectionFailed = false;
                collectionButton.setEnabled(false);
                collectionButton.setText("Reading Collection Log…");
                collectionStatus.setText("Collection Log · reading progress…");
                collectionStatus.setForeground(BRASS);
            }
            else if (pending > 0)
            {
                collectionStatus.setText("Collection Log · " + pending + " sections queued");
                collectionStatus.setForeground(BRASS);
            }
            else if (sections > 0)
            {
                collectionStatus.setText("Collection Log ready · click Sync Log");
                collectionStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            }
            else
            {
                collectionStatus.setText("Open Collection Log, then click Sync Log");
                collectionStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            }
        });
    }

    void setCollectionLogUploading(int sections)
    {
        SwingUtilities.invokeLater(() ->
        {
            collectionSyncing = true;
            collectionFailed = false;
            collectionButton.setEnabled(false);
            collectionButton.setText("Uploading Collection Log…");
            collectionStatus.setText("Uploading " + sections + " Collection Log sections…");
            collectionStatus.setForeground(BRASS);
        });
    }

    void setCollectionLogFailed(int sections)
    {
        SwingUtilities.invokeLater(() ->
        {
            collectionSyncing = false;
            collectionFailed = true;
            collectionButton.setEnabled(connected);
            collectionButton.setText("Retry Collection Log Sync");
            collectionStatus.setText("Upload failed · " + sections + " sections queued");
            collectionStatus.setForeground(RUST);
        });
    }

    void setCollectionLogSynced(int sections)
    {
        SwingUtilities.invokeLater(() ->
        {
            collectionSyncing = false;
            collectionFailed = false;
            collectionButton.setEnabled(connected);
            collectionButton.setText("Sync Collection Log");
            collectionStatus.setText("Collection Log · " + sections + " sections synced");
            collectionStatus.setForeground(MOSS);
        });
    }

    void setGoals(List<IronPathGoalProgress> progress)
    {
        SwingUtilities.invokeLater(() ->
        {
            currentGoalProgress = progress == null ? Collections.emptyList() : new ArrayList<>(progress);
            renderGoals(currentGoalProgress);
        });
    }

    private void renderRecentKills(List<IronPathDtos.KillCount> recent)
    {
        recentKills.removeAll();
        if (recent.isEmpty())
        {
            recentKills.add(emptyState("Your next NPC drop will appear here"));
        }
        else
        {
            for (int index = 0; index < Math.min(3, recent.size()); index++)
            {
                IronPathDtos.KillCount kill = recent.get(index);
                JPanel row = new JPanel(new BorderLayout(6, 0));
                row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                row.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
                JLabel name = new JLabel(kill.npcName == null ? "Unknown NPC" : kill.npcName);
                name.setForeground(Color.WHITE);
                JLabel count = new JLabel("×" + kill.count);
                count.setForeground(BRASS);
                count.setFont(count.getFont().deriveFont(Font.BOLD));
                row.add(name, BorderLayout.CENTER);
                row.add(count, BorderLayout.EAST);
                recentKills.add(row);
                if (index < Math.min(3, recent.size()) - 1) recentKills.add(gap(3));
            }
        }
        recentKills.revalidate();
        recentKills.repaint();
    }

    private void renderRecentCollections(List<String> itemNames)
    {
        recentCollections.removeAll();
        if (itemNames.isEmpty())
        {
            recentCollections.add(emptyState("Open your Collection Log overview to load recent items"));
        }
        else
        {
            for (int index = 0; index < Math.min(3, itemNames.size()); index++)
            {
                JLabel item = new JLabel(itemNames.get(index));
                item.setForeground(Color.WHITE);
                item.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                item.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                item.setOpaque(true);
                item.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                item.setAlignmentX(LEFT_ALIGNMENT);
                item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                item.setPreferredSize(new Dimension(0, 30));
                recentCollections.add(item);
                if (index < Math.min(3, itemNames.size()) - 1) recentCollections.add(gap(3));
            }
        }
        recentCollections.revalidate();
        recentCollections.repaint();
    }

    private void renderGoals(List<IronPathGoalProgress> progress)
    {
        List<IronPathGoalProgress> active = new ArrayList<>();
        List<IronPathGoalProgress> complete = new ArrayList<>();
        for (IronPathGoalProgress goal : progress)
        {
            if ("complete".equals(goal.status)) complete.add(goal);
            else active.add(goal);
        }

        renderGoalList(goals, active, progress.isEmpty()
            ? "Create a goal on the dashboard" : "No active goals");
        completedToggle.setText("COMPLETED (" + complete.size() + ")  " + (completedExpanded ? "▾" : "▸"));
        completedToggle.setVisible(!complete.isEmpty());
        completedGoals.setVisible(completedExpanded && !complete.isEmpty());
        renderGoalList(completedGoals, complete, "No completed goals");
        revalidate();
        repaint();
    }

    private void renderGoalList(JPanel target, List<IronPathGoalProgress> progress, String emptyMessage)
    {
        target.removeAll();
        if (progress.isEmpty())
        {
            target.add(emptyState(emptyMessage));
        }
        for (int index = 0; index < progress.size(); index++)
        {
            IronPathGoalProgress goal = progress.get(index);
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
            row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            row.setBorder(cardBorder(kindColor(goal.kind)));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 102));

            JLabel title = new JLabel(goal.title);
            title.setForeground(Color.WHITE);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));

            String nextStatus = "complete".equals(goal.status) ? "active" : "complete";
            boolean derivedStatus = "skill".equals(goal.kind);
            JButton statusButton = new JButton(derivedStatus ? ("complete".equals(goal.status) ? "Target reached" : "Synced") : ("complete".equals(goal.status) ? "Reopen" : "Complete"));
            statusButton.setFont(statusButton.getFont().deriveFont(8f));
            statusButton.setMargin(new Insets(1, 5, 1, 5));
            statusButton.setEnabled(!derivedStatus && goal.id != null && !goal.id.isEmpty());
            statusButton.addActionListener(event ->
            {
                String originalText = statusButton.getText();
                statusButton.setEnabled(false);
                statusButton.setText("Saving…");
                goalStatusAction.update(goal.id, nextStatus, success -> SwingUtilities.invokeLater(() ->
                {
                    if (!success)
                    {
                        statusButton.setText(originalText);
                        statusButton.setEnabled(true);
                    }
                }));
            });

            JPanel heading = new JPanel(new BorderLayout(5, 0));
            heading.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
            heading.setAlignmentX(LEFT_ALIGNMENT);
            heading.add(title, BorderLayout.CENTER);
            heading.add(statusButton, BorderLayout.EAST);

            JLabel detail = new JLabel(goal.detail);
            detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            detail.setFont(detail.getFont().deriveFont(9f));
            detail.setAlignmentX(LEFT_ALIGNMENT);
            JLabel context = new JLabel(goal.context);
            context.setForeground(new Color(130, 135, 130));
            context.setFont(context.getFont().deriveFont(8f));
            context.setAlignmentX(LEFT_ALIGNMENT);
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setValue(goal.percent);
            bar.setForeground(kindColor(goal.kind));
            bar.setBackground(new Color(45, 48, 45));
            bar.setBorderPainted(false);
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 5));
            bar.setAlignmentX(LEFT_ALIGNMENT);
            bar.setToolTipText(goal.context);

            row.add(heading);
            row.add(Box.createRigidArea(new Dimension(0, 2)));
            row.add(detail);
            row.add(context);
            row.add(Box.createRigidArea(new Dimension(0, 5)));
            row.add(bar);
            target.add(row);
            if (index < progress.size() - 1) target.add(gap(4));
        }
        target.revalidate();
        target.repaint();
    }

    @FunctionalInterface
    interface GoalStatusAction
    {
        void update(String goalId, String status, Consumer<Boolean> callback);
    }

    private static JPanel statCard(JLabel value, JLabel label)
    {
        JPanel panel = new JPanel(new GridLayout(2, 1));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 3, 4, 3));
        panel.add(value);
        panel.add(label);
        return panel;
    }

    private static JLabel statValue(String text)
    {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setForeground(MOSS);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        return label;
    }

    private static JLabel statLabel(String text)
    {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setFont(label.getFont().deriveFont(8f));
        return label;
    }

    private static JLabel sectionHeading(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 9f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel emptyState(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setBorder(BorderFactory.createEmptyBorder(9, 5, 9, 5));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JPanel verticalPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private static javax.swing.border.Border cardBorder(Color accent)
    {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 2, 0, 0, accent),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(7, 8, 7, 7)));
    }

    private static Component gap(int height)
    {
        return Box.createRigidArea(new Dimension(0, height));
    }

    private static Color kindColor(String kind)
    {
        if ("grind".equals(kind)) return RUST;
        if ("banked_xp".equals(kind)) return MOSS;
        if ("skill".equals(kind)) return MOSS;
        return BRASS;
    }

    private void openDashboard()
    {
        try
        {
            if (Desktop.isDesktopSupported())
            {
                Desktop.getDesktop().browse(new URI(IronPathConfig.DEFAULT_API_ORIGIN));
            }
        }
        catch (Exception ignored)
        {
            status.setText("Open www.ironpathosrs.com in your browser");
        }
    }
}
