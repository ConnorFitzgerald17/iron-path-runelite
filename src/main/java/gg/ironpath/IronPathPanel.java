package gg.ironpath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
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
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

final class IronPathPanel extends PluginPanel
{
    private static final Color BRASS = new Color(213, 173, 85);
    private static final Color MOSS = new Color(126, 163, 106);
    private static final Color RUST = new Color(183, 90, 77);
    private static final Color BORDER = new Color(61, 67, 59);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter COLLECTION_SYNC_TIME = DateTimeFormatter.ofPattern("MMM d · HH:mm");

    private final JLabel status = new JLabel("Not linked");
    private final JLabel character = new JLabel("No character");
    private final JLabel sessionValue = statValue("0");
    private final JLabel pendingValue = statValue("0");
    private final JLabel syncValue = statValue("NEVER");
    private final JLabel syncMeta = statLabel("AUTO · 2M");
    private final JLabel collectionStatus = new JLabel();
    private final JLabel collectionLastSync = new JLabel("Last full sync: Never");
    private final JPanel recentCollections = verticalPanel();
    private final JPanel recentKills = verticalPanel();
    private final JPanel goals = verticalPanel();
    private final JPanel completedGoals = verticalPanel();
    private final JButton completedToggle = new JButton("COMPLETED (0)  ▸");
    private final JButton connectButton = new JButton("Connect");
    private final JButton syncButton = new JButton("Sync now");
    private final JButton collectionButton = new JButton("Sync full log");
    private final JPanel connectionSection = verticalPanel();
    private final JPanel syncSection = verticalPanel();
    private final JPanel collectionSection = verticalPanel();
    private final JPanel recentCollectionsSection = verticalPanel();
    private final JPanel recentKillsSection = verticalPanel();
    private final JPanel goalsSection = verticalPanel();
    private boolean connected;
    private boolean collectionSyncing;
    private boolean collectionFailed;
    private IronPathDtos.CollectionLogProgress collectionProgress;
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
        connectionSection.add(gap(12));

        JPanel connection = new JPanel(new BorderLayout(8, 3));
        connection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        connection.setBorder(cardBorder(BRASS));
        fullWidth(connection, 54);
        character.setForeground(Color.WHITE);
        character.setFont(character.getFont().deriveFont(Font.BOLD, 12f));
        status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        status.setFont(status.getFont().deriveFont(9f));
        connection.add(character, BorderLayout.NORTH);
        connection.add(status, BorderLayout.SOUTH);
        connectionSection.add(connection);
        connectionSection.add(gap(7));

        connectButton.addActionListener(event -> connectAction.run());
        syncButton.addActionListener(event -> syncAction.run());
        collectionButton.addActionListener(event -> collectionAction.run());
        styleActionButton(connectButton);
        styleActionButton(syncButton);
        styleActionButton(collectionButton);
        collectionButton.setToolTipText("Open the full Collection Log item list, keep it open, then click this button.");
        connectionSection.add(connectButton);
        add(connectionSection);

        syncSection.add(gap(14));
        syncSection.add(sectionHeading("SESSION ACTIVITY"));
        syncSection.add(gap(5));
        syncSection.add(syncButton);
        syncSection.add(gap(5));

        JPanel stats = new JPanel(new GridLayout(1, 3, 4, 0));
        stats.setBackground(ColorScheme.DARK_GRAY_COLOR);
        fullWidth(stats, 52);
        stats.add(statCard(sessionValue, statLabel("SESSION KC")));
        stats.add(statCard(pendingValue, statLabel("PENDING")));
        stats.add(statCard(syncValue, syncMeta));
        syncSection.add(stats);
        add(syncSection);

        collectionSection.add(gap(14));
        collectionSection.add(sectionHeading("COLLECTION LOG"));
        collectionSection.add(gap(5));
        collectionSection.add(collectionButton);
        collectionSection.add(gap(5));
        collectionStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        collectionStatus.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        collectionStatus.setBorder(cardBorder(MOSS));
        collectionStatus.setVerticalAlignment(SwingConstants.CENTER);
        fullWidth(collectionStatus, 62);
        showCollectionMessage(
            "Open the full Collection Log",
            "Iron Path reads the total from its title. Keep the item list open, then click Sync full log.",
            ColorScheme.LIGHT_GRAY_COLOR);
        collectionSection.add(collectionStatus);
        collectionLastSync.setForeground(new Color(145, 150, 145));
        collectionLastSync.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        collectionLastSync.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));
        fullWidth(collectionLastSync, 20);
        collectionSection.add(collectionLastSync);
        add(collectionSection);

        recentCollectionsSection.add(gap(14));
        recentCollectionsSection.add(sectionHeading("RECENT COLLECTIONS LOGGED"));
        recentCollectionsSection.add(gap(5));
        recentCollectionsSection.add(recentCollections);
        add(recentCollectionsSection);
        renderRecentCollections(Collections.emptyList());

        recentKillsSection.add(gap(14));
        recentKillsSection.add(sectionHeading("RECENT KILLS"));
        recentKillsSection.add(gap(5));
        recentKillsSection.add(recentKills);
        add(recentKillsSection);
        renderRecentKills(Collections.emptyList());

        goalsSection.add(gap(14));
        goalsSection.add(sectionHeading("ACTIVE GOALS"));
        goalsSection.add(gap(5));
        goalsSection.add(goals);
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
        goalsSection.add(gap(7));
        goalsSection.add(completedToggle);
        goalsSection.add(gap(4));
        goalsSection.add(completedGoals);
        add(goalsSection);
        renderGoals(Collections.emptyList());

        JButton dashboard = new JButton("Open dashboard  ↗");
        styleActionButton(dashboard);
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

    void setDisplayPreferences(boolean showConnection, boolean showSyncActivity, boolean showCollectionLog,
                               boolean showRecentCollections, boolean showRecentKills, boolean showGoals)
    {
        SwingUtilities.invokeLater(() ->
        {
            connectionSection.setVisible(showConnection);
            syncSection.setVisible(showSyncActivity);
            collectionSection.setVisible(showCollectionLog);
            recentCollectionsSection.setVisible(showRecentCollections);
            recentKillsSection.setVisible(showRecentKills);
            goalsSection.setVisible(showGoals);
            revalidate();
            repaint();
        });
    }

    void setCollectionProgress(IronPathDtos.CollectionLogProgress progress)
    {
        if (progress == null) return;
        SwingUtilities.invokeLater(() ->
        {
            collectionProgress = progress;
            if (collectionSyncing) return;
            showCollectionMessage(
                String.format("Collection Log detected: %,d / %,d", progress.obtainedCount, progress.totalCount),
                "Keep the full item list open, then click Sync full log.",
                MOSS);
        });
    }

    void setCollectionLastSynced(Instant syncedAt)
    {
        SwingUtilities.invokeLater(() -> collectionLastSync.setText(syncedAt == null
            ? "Last full sync: Never"
            : "Last full sync: " + COLLECTION_SYNC_TIME.format(syncedAt.atZone(ZoneId.systemDefault()))));
    }

    void setCollectionLogWaiting()
    {
        SwingUtilities.invokeLater(() -> showCollectionMessage(
            "Waiting for the full Collection Log",
            "Keep the item list open. Iron Path will retry for a few seconds while the interface loads.",
            BRASS));
    }

    void setCollectionLogNeedsFullLog()
    {
        SwingUtilities.invokeLater(() ->
        {
            collectionSyncing = false;
            collectionButton.setEnabled(connected);
            collectionButton.setText("Sync full log");
            showCollectionMessage(
                "Could not read the full Collection Log",
                "Open the full item list—not the character summary—and leave it open before trying again.",
                BRASS);
        });
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
                collectionButton.setText("Reading full log…");
                showCollectionMessage(
                    "Reading the full Collection Log",
                    "Keep the item list open while Iron Path reads every section.",
                    BRASS);
            }
            else if (pending > 0)
            {
                showCollectionMessage(
                    pending + " Collection Log sections queued",
                    "Iron Path will upload the saved sections automatically.",
                    BRASS);
            }
            else if (collectionProgress != null)
            {
                showCollectionMessage(
                    String.format("Collection Log detected: %,d / %,d", collectionProgress.obtainedCount, collectionProgress.totalCount),
                    "Keep the full item list open, then click Sync full log.",
                    MOSS);
            }
            else
            {
                showCollectionMessage(
                    "Open the full Collection Log",
                    "Iron Path reads the total from its title. Keep the item list open, then click Sync full log.",
                    ColorScheme.LIGHT_GRAY_COLOR);
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
            collectionButton.setText("Uploading full log…");
            showCollectionMessage(
                "Uploading " + sections + " Collection Log sections",
                "You may close the in-game Collection Log now.",
                BRASS);
        });
    }

    void setCollectionLogFailed(int sections)
    {
        SwingUtilities.invokeLater(() ->
        {
            collectionSyncing = false;
            collectionFailed = true;
            collectionButton.setEnabled(connected);
            collectionButton.setText("Retry full log");
            showCollectionMessage(
                "Collection Log upload failed",
                sections + " sections are safely queued. Click Retry full log to send them again.",
                RUST);
        });
    }

    void setCollectionLogSynced(int sections)
    {
        SwingUtilities.invokeLater(() ->
        {
            collectionSyncing = false;
            collectionFailed = false;
            collectionButton.setEnabled(connected);
            collectionButton.setText("Sync full log");
            String totals = collectionProgress == null ? "" : String.format(
                " Overview total: %,d / %,d.", collectionProgress.obtainedCount, collectionProgress.totalCount);
            showCollectionMessage(
                "Collection Log sync complete",
                sections + " sections uploaded." + totals,
                MOSS);
        });
    }

    private void showCollectionMessage(String headline, String detail, Color color)
    {
        collectionStatus.setText("<html><b>" + headline + "</b><br>" + detail + "</html>");
        collectionStatus.setForeground(color);
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
        JPanel panel = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private static void fullWidth(JComponent component, int height)
    {
        component.setAlignmentX(LEFT_ALIGNMENT);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        component.setPreferredSize(new Dimension(0, height));
    }

    private static void styleActionButton(JButton button)
    {
        fullWidth(button, 32);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        button.setForeground(Color.WHITE);
        button.setBackground(new Color(42, 45, 42));
        button.setMargin(new Insets(4, 9, 4, 9));
        button.setFocusPainted(false);
        button.setHorizontalAlignment(SwingConstants.CENTER);
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
        Component gap = Box.createRigidArea(new Dimension(0, height));
        if (gap instanceof JComponent) ((JComponent) gap).setAlignmentX(LEFT_ALIGNMENT);
        return gap;
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
            LinkBrowser.browse(IronPathConfig.DEFAULT_API_ORIGIN);
        }
        catch (Exception ignored)
        {
            status.setText("Open www.ironpathosrs.com in your browser");
        }
    }
}
