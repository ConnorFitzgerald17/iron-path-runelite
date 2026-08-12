package gg.ironpath;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.vars.AccountType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemStack;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(
    name = "Iron Path",
    description = "Sync characters, skills, goals, loot, and Collection Log progress with Iron Path",
    tags = {"ironman", "goals", "quests", "skills", "loot", "collection log", "banked xp", "tracker"}
)
public class IronPathPlugin extends Plugin
{
    private static final Logger LOGGER = Logger.getLogger(IronPathPlugin.class.getName());
    private static final String TOKEN_KEY = "deviceToken";
    private static final String QUEUE_KEY = "pendingLoot";
    private static final String KILL_COUNTS_KEY = "killCounts";
    private static final String ABSOLUTE_KILL_COUNTS_KEY = "absoluteKillCounts";
    private static final String LAST_SYNC_KEY = "lastSuccessfulSync";
    private static final String COLLECTION_QUEUE_KEY = "pendingCollectionLog";
    private static final String COLLECTION_RECENT_KEY = "pendingCollectionRecent";
    private static final int MAX_PENDING_LOOT = 500;
    private static final int LOOT_BATCH_SIZE = 100;
    private static final int SYNC_INTERVAL_MINUTES = 2;
    private static final int GOAL_REFRESH_INTERVAL_SECONDS = 15;
    private static final String BANK_CONTAINER = "bank";
    private static final String POTION_STORAGE_CONTAINER = "potion-storage";
    private static final Pattern COMPLETION_COUNT = Pattern.compile(
        "Your (?<pre>completion count for |subdued |completed )?(?<boss>.+?) "
            + "(?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)"
            + "is:\\s*(?<kc>[0-9,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLLECTION_HEADER_KC = Pattern.compile("(?i)(?<boss>.+?) kills?: (?<kc>[0-9,]+)");
    private static final Pattern COLLECTION_HEADER_COMPLETION = Pattern.compile("(?i)(?<boss>.+?) completions?: (?<kc>[0-9,]+)");

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ConfigManager configManager;
    @Inject private ScheduledExecutorService executor;
    @Inject private IronPathConfig config;
    @Inject private IronPathApiClient apiClient;
    @Inject private HiscoreClient hiscoreClient;
    @Inject private Gson gson;

    private final Map<String, Map<Integer, Integer>> containerSnapshots = new HashMap<>();
    private final List<IronPathDtos.LootEvent> pendingLoot = new ArrayList<>();
    private final Map<Integer, IronPathDtos.KillCount> trackedKills = new HashMap<>();
    private final Map<String, IronPathDtos.AbsoluteKillCount> absoluteKillCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> liveSkillLevels = new ConcurrentHashMap<>();
    private final Map<String, Integer> liveSkillXps = new ConcurrentHashMap<>();
    private final Map<String, String> liveQuestStates = new ConcurrentHashMap<>();
    private final Map<String, IronPathDtos.CollectionLogSection> pendingCollectionLog = new HashMap<>();
    private final List<Integer> pendingCollectionRecent = new ArrayList<>();
    private final AtomicBoolean snapshotUploadInFlight = new AtomicBoolean();
    private final AtomicBoolean goalRefreshInFlight = new AtomicBoolean();
    private final AtomicLong goalRefreshSequence = new AtomicLong();
    private IronPathPanel panel;
    private NavigationButton navigationButton;
    private ScheduledFuture<?> periodicSync;
    private ScheduledFuture<?> periodicGoalRefresh;
    private ScheduledFuture<?> bankDebounce;
    private volatile boolean lootUploadInFlight;
    private volatile boolean collectionUploadInFlight;
    private volatile List<IronPathDtos.GoalSummary> currentGoals = Collections.emptyList();
    private volatile Instant lastSuccessfulSync;
    private volatile long profileGeneration;
    private volatile boolean running;
    private int observedKills;
    private int recentCollectionCaptureTicks;
    private IronPathCollectionLog collectionLog;

    @Provides
    IronPathConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(IronPathConfig.class);
    }

    @Override
    protected void startUp()
    {
        running = true;
        panel = new IronPathPanel();
        collectionLog = new IronPathCollectionLog(client, this::queueCollectionLog);
        panel.setActions(this::connect, this::requestManualSync, this::requestCollectionLogSync, this::updateGoalStatus);
        navigationButton = NavigationButton.builder()
            .tooltip("Iron Path")
            .icon(createPanelIcon())
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);
        loadProfileState();
        refreshConnectionState();
        updatePanelData();

        periodicSync = executor.scheduleWithFixedDelay(() ->
        {
            if (config.autoSync())
            {
                requestFullSync();
            }
        }, SYNC_INTERVAL_MINUTES, SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES);
        periodicGoalRefresh = executor.scheduleWithFixedDelay(() ->
        {
            if (config.autoSync())
            {
                refreshGoals();
            }
        }, GOAL_REFRESH_INTERVAL_SECONDS, GOAL_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        refreshGoals();
    }

    @Override
    protected void shutDown()
    {
        running = false;
        if (periodicSync != null) periodicSync.cancel(false);
        if (periodicGoalRefresh != null) periodicGoalRefresh.cancel(false);
        if (bankDebounce != null) bankDebounce.cancel(false);
        goalRefreshSequence.incrementAndGet();
        goalRefreshInFlight.set(false);
        savePendingLoot();
        saveKillCounts();
        saveAbsoluteKillCounts();
        savePendingCollectionLog();
        if (navigationButton != null) clientToolbar.removeNavigation(navigationButton);
        panel = null;
        navigationButton = null;
        containerSnapshots.clear();
        trackedKills.clear();
        absoluteKillCounts.clear();
        liveSkillLevels.clear();
        liveSkillXps.clear();
        liveQuestStates.clear();
        pendingCollectionLog.clear();
        pendingCollectionRecent.clear();
        recentCollectionCaptureTicks = 0;
        if (collectionLog != null) collectionLog.reset();
        currentGoals = Collections.emptyList();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            executor.schedule(this::requestManualSync, 3, TimeUnit.SECONDS);
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (collectionLog == null) return;
        if (event.getGroupId() == InterfaceID.COLLECTION_OVERVIEW)
        {
            // The widget group is announced before its dynamic item children
            // have always been populated. Retry briefly on game ticks instead
            // of caching an empty recent-items list immediately.
            recentCollectionCaptureTicks = 5;
            return;
        }
        if (event.getGroupId() != InterfaceID.COLLECTION) return;
        clientThread.invokeLater(() ->
        {
            collectionLog.collectionOpened();
            if (panel != null) panel.setCollectionLogState(collectionLog.sectionCount(), pendingCollectionLogSize(), collectionLog.isAwaitingSearch());
            return true;
        });
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event)
    {
        if (collectionLog != null) collectionLog.onScriptPreFired(event.getScriptId(), event.getScriptEvent());
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() == net.runelite.api.ScriptID.COLLECTION_DRAW_LIST)
        {
            captureCollectionHeaderKillCount();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
        captureAbsoluteKillCount(event.getMessage(), COMPLETION_COUNT);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (capturePotionStorage()) debounceBankSync();
        if (recentCollectionCaptureTicks > 0)
        {
            if (refreshRecentCollectionPanel()) recentCollectionCaptureTicks = 0;
            else recentCollectionCaptureTicks--;
        }
        if (collectionLog != null && collectionLog.onGameTick() && panel != null)
        {
            panel.setCollectionLogState(collectionLog.sectionCount(), pendingCollectionLogSize(), false);
        }
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        profileGeneration++;
        snapshotUploadInFlight.set(false);
        lootUploadInFlight = false;
        collectionUploadInFlight = false;
        goalRefreshSequence.incrementAndGet();
        goalRefreshInFlight.set(false);
        synchronized (pendingLoot)
        {
            pendingLoot.clear();
        }
        synchronized (trackedKills)
        {
            trackedKills.clear();
        }
        absoluteKillCounts.clear();
        synchronized (pendingCollectionLog)
        {
            pendingCollectionLog.clear();
            pendingCollectionRecent.clear();
        }
        loadProfileState();
        containerSnapshots.clear();
        liveSkillLevels.clear();
        liveSkillXps.clear();
        liveQuestStates.clear();
        if (collectionLog != null) collectionLog.reset();
        recentCollectionCaptureTicks = 0;
        currentGoals = Collections.emptyList();
        observedKills = 0;
        refreshConnectionState();
        updatePanelData();
        requestManualSync();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (IronPathConfig.GROUP.equals(event.getGroup()))
        {
            updatePanelData();
            if (config.autoSync()) refreshGoals();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        ItemContainer container = event.getItemContainer();
        int id = container.getId();
        if (id == InventoryID.BANK.getId())
        {
            captureContainer(BANK_CONTAINER, container);
            debounceBankSync();
        }
        else if (id == InventoryID.INVENTORY.getId())
        {
            captureContainer("inventory", container);
        }
        else if (id == InventoryID.EQUIPMENT.getId())
        {
            captureContainer("equipment", container);
        }
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        List<IronPathDtos.LootItem> items = new ArrayList<>();
        for (ItemStack stack : event.getItems())
        {
            if (stack.getId() > 0 && stack.getQuantity() > 0)
            {
                items.add(new IronPathDtos.LootItem(stack.getId(), stack.getQuantity()));
            }
        }
        String occurredAt = Instant.now().toString();
        int npcId = event.getComposition().getId();
        String npcName = event.getComposition().getName() == null ? "Unknown NPC" : event.getComposition().getName();
        IronPathDtos.LootEvent loot = new IronPathDtos.LootEvent(
            UUID.randomUUID().toString(), occurredAt, npcId, npcName, items);
        synchronized (pendingLoot)
        {
            pendingLoot.add(loot);
            while (pendingLoot.size() > MAX_PENDING_LOOT) pendingLoot.remove(0);
            savePendingLoot();
        }
        synchronized (trackedKills)
        {
            IronPathDtos.KillCount count = trackedKills.computeIfAbsent(npcId,
                ignored -> new IronPathDtos.KillCount(npcId, npcName, 0, occurredAt));
            count.record(npcName, occurredAt);
            saveKillCounts();
        }
        IronPathDtos.AbsoluteKillCount absolute = absoluteKillCounts.get(normalize(npcName));
        if (absolute != null) mergeAbsoluteKillCount(npcName, absolute.count + 1, occurredAt);
        observedKills++;
        updatePanelData();
        flushPendingLoot();
    }

    private void connect()
    {
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            panel.setConnected(false, null, "Log in before linking");
            return;
        }
        String code = config.linkingCode().trim();
        if (code.isEmpty())
        {
            panel.setConnected(false, client.getLocalPlayer().getName(), "Add the website linking code in settings");
            return;
        }
        String characterName = client.getLocalPlayer().getName();
        long requestGeneration = profileGeneration;
        panel.setConnected(false, characterName, "Exchanging linking code…");
        apiClient.exchangeCode(code, characterName, response ->
        {
            if (requestGeneration != profileGeneration) return;
            if (response == null || response.token == null)
            {
                panel.setConnected(false, characterName,
                    response == null || response.error == null ? "Connection failed" : response.error);
                return;
            }
            configManager.setRSProfileConfiguration(IronPathConfig.GROUP, TOKEN_KEY, response.token);
            panel.setConnected(true, characterName, "Connected · first sync pending");
            requestManualSync();
        });
    }

    private void refreshConnectionState()
    {
        if (panel == null) return;
        String token = token();
        String name = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
        panel.setConnected(token != null, name, token == null ? "Not linked" : connectedDetail());
        panel.setSyncState(lastSuccessfulSync, snapshotUploadInFlight.get(), pendingLootSize(), config.autoSync());
    }

    private void requestFullSync()
    {
        if (!running) return;
        clientThread.invokeLater(this::syncSnapshot);
        refreshGoals();
        flushPendingLoot();
    }

    private void requestManualSync()
    {
        if (!running) return;
        refreshRuneLiteKillCounts();
        String playerName = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
        if (playerName == null)
        {
            requestFullSync();
            return;
        }
        long requestGeneration = profileGeneration;
        hiscoreClient.lookupAsync(playerName, HiscoreEndpoint.NORMAL).whenComplete((result, error) ->
        {
            if (requestGeneration == profileGeneration && error == null && result != null)
            {
                mergeHiscoreKillCounts(result);
            }
            requestFullSync();
        });
    }

    private void syncSnapshot()
    {
        String token = token();
        if (token == null || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;
        if (!snapshotUploadInFlight.compareAndSet(false, true)) return;
        captureLiveContainers();

        List<IronPathDtos.SkillRecord> skills = new ArrayList<>();
        liveSkillLevels.clear();
        liveSkillXps.clear();
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            int level = client.getRealSkillLevel(skill);
            int xp = client.getSkillExperience(skill);
            skills.add(new IronPathDtos.SkillRecord(skill.getName(), level, xp));
            liveSkillLevels.put(normalize(skill.getName()), level);
            liveSkillXps.put(normalize(skill.getName()), xp);
        }

        List<IronPathDtos.QuestRecord> quests = new ArrayList<>();
        liveQuestStates.clear();
        for (Quest quest : Quest.values())
        {
            String state = questState(quest.getState(client));
            quests.add(new IronPathDtos.QuestRecord(quest.getName(), state));
            liveQuestStates.put(normalize(quest.getName()), state);
        }

        List<IronPathDtos.ItemRecord> items = snapshotItems(containerSnapshots);

        String characterName = client.getLocalPlayer().getName();
        long requestGeneration = profileGeneration;
        IronPathDtos.Snapshot snapshot = new IronPathDtos.Snapshot(
            Instant.now().toString(), characterName, accountType(client.getAccountType()),
            client.getLocalPlayer().getCombatLevel(), skills, quests, items,
            new ArrayList<>(absoluteKillCounts.values()));
        panel.setConnected(true, characterName, "Uploading snapshot…");
        panel.setSyncState(lastSuccessfulSync, true, pendingLootSize(), config.autoSync());
        updateGoalProgress();
        apiClient.sendSnapshot(token, snapshot, success ->
        {
            if (requestGeneration != profileGeneration) return;
            snapshotUploadInFlight.set(false);
            if (success)
            {
                lastSuccessfulSync = Instant.now();
                saveLastSuccessfulSync();
            }
            if (panel != null)
            {
                panel.setConnected(true, characterName, success
                    ? "Connected · dashboard is up to date" : "Sync failed · use Sync now to retry");
                panel.setSyncState(lastSuccessfulSync, false, pendingLootSize(), config.autoSync());
            }
        });
    }

    private void refreshGoals()
    {
        String token = token();
        if (!running || token == null || panel == null) return;
        if (!goalRefreshInFlight.compareAndSet(false, true)) return;
        long requestGeneration = profileGeneration;
        long requestSequence = goalRefreshSequence.incrementAndGet();
        apiClient.fetchGoals(token, response ->
        {
            if (requestSequence != goalRefreshSequence.get()) return;
            goalRefreshInFlight.set(false);
            if (requestGeneration != profileGeneration) return;
            if (response != null)
            {
                currentGoals = response.goals == null ? Collections.emptyList() : new ArrayList<>(response.goals);
                updateGoalProgress();
                if (response.characterName != null)
                {
                    panel.setConnected(true, response.characterName,
                        snapshotUploadInFlight.get() ? "Syncing dashboard…" : connectedDetail());
                }
            }
        });
    }

    private void updateGoalStatus(String goalId, String status, java.util.function.Consumer<Boolean> callback)
    {
        String token = token();
        if (token == null || goalId == null || (!"active".equals(status) && !"complete".equals(status)))
        {
            callback.accept(false);
            return;
        }

        goalRefreshSequence.incrementAndGet();
        goalRefreshInFlight.set(false);
        long requestGeneration = profileGeneration;
        apiClient.updateGoalStatus(token, goalId, status, success ->
        {
            if (requestGeneration != profileGeneration)
            {
                callback.accept(false);
                return;
            }
            if (success)
            {
                List<IronPathDtos.GoalSummary> updated = new ArrayList<>(currentGoals);
                for (IronPathDtos.GoalSummary goal : updated)
                {
                    if (goalId.equals(goal.id)) goal.status = status;
                }
                currentGoals = updated;
                updateGoalProgress();
                callback.accept(true);
                refreshGoals();
            }
            else
            {
                if (panel != null)
                {
                    String name = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
                    panel.setConnected(true, name, "Goal update failed · retry the action");
                }
                callback.accept(false);
            }
        });
    }

    private void flushPendingLoot()
    {
        String token = token();
        if (token == null) return;
        List<IronPathDtos.LootEvent> batch;
        synchronized (pendingLoot)
        {
            if (pendingLoot.isEmpty() || lootUploadInFlight) return;
            batch = new ArrayList<>(pendingLoot.subList(0, Math.min(LOOT_BATCH_SIZE, pendingLoot.size())));
            lootUploadInFlight = true;
        }
        long requestGeneration = profileGeneration;
        apiClient.sendLoot(token, batch, success ->
        {
            if (requestGeneration != profileGeneration) return;
            boolean hasMore;
            synchronized (pendingLoot)
            {
                if (success)
                {
                    Set<String> acceptedIds = new HashSet<>();
                    for (IronPathDtos.LootEvent event : batch) acceptedIds.add(event.eventId);
                    pendingLoot.removeIf(event -> acceptedIds.contains(event.eventId));
                    savePendingLoot();
                }
                lootUploadInFlight = false;
                hasMore = success && !pendingLoot.isEmpty();
            }
            updatePanelData();
            if (hasMore) flushPendingLoot();
        });
    }

    private void queueCollectionLog(List<IronPathDtos.CollectionLogSection> sections)
    {
        synchronized (pendingCollectionLog)
        {
            for (IronPathDtos.CollectionLogSection section : sections) pendingCollectionLog.put(section.key, section);
            pendingCollectionRecent.clear();
            pendingCollectionRecent.addAll(collectionLog.recentItemIds());
            savePendingCollectionLog();
        }
        if (panel != null) panel.setCollectionLogState(sections.size(), pendingCollectionLogSize(), false);
        flushPendingCollectionLog();
    }

    private void requestCollectionLogSync()
    {
        if (!running || token() == null || collectionLog == null) return;
        synchronized (pendingCollectionLog)
        {
            if (!pendingCollectionLog.isEmpty())
            {
                flushPendingCollectionLog();
                return;
            }
        }
        clientThread.invokeLater(() ->
        {
            boolean started = collectionLog.requestSync();
            if (panel != null)
            {
                panel.setCollectionLogState(collectionLog.sectionCount(), 0, started);
                if (!started) panel.setConnected(true,
                    client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName(),
                    "Open your Collection Log, then click Sync Log");
            }
            return true;
        });
    }

    private void flushPendingCollectionLog()
    {
        String token = token();
        if (token == null) return;
        IronPathDtos.CollectionLogSync sync;
        synchronized (pendingCollectionLog)
        {
            if (collectionUploadInFlight || pendingCollectionLog.isEmpty()) return;
            List<IronPathDtos.CollectionLogSection> sections = new ArrayList<>(pendingCollectionLog.values());
            sections.sort(Comparator.comparing(section -> section.key));
            String capturedAt = sections.stream().map(section -> section.capturedAt).max(String::compareTo)
                .orElse(Instant.now().toString());
            sync = new IronPathDtos.CollectionLogSync(capturedAt, sections, new ArrayList<>(pendingCollectionRecent));
            collectionUploadInFlight = true;
        }
        if (panel != null) panel.setCollectionLogUploading(sync.sections.size());
        long requestGeneration = profileGeneration;
        apiClient.sendCollectionLog(token, sync, success ->
        {
            if (requestGeneration != profileGeneration) return;
            synchronized (pendingCollectionLog)
            {
                if (success)
                {
                    for (IronPathDtos.CollectionLogSection uploaded : sync.sections)
                    {
                        IronPathDtos.CollectionLogSection current = pendingCollectionLog.get(uploaded.key);
                        if (current != null && current.capturedAt.equals(uploaded.capturedAt)) pendingCollectionLog.remove(uploaded.key);
                    }
                    if (pendingCollectionLog.isEmpty()) pendingCollectionRecent.clear();
                    savePendingCollectionLog();
                }
                collectionUploadInFlight = false;
            }
            if (panel != null)
            {
                if (success && pendingCollectionLogSize() == 0) panel.setCollectionLogSynced(sync.sections.size());
                else if (!success) panel.setCollectionLogFailed(pendingCollectionLogSize());
                else panel.setCollectionLogState(collectionLog == null ? 0 : collectionLog.sectionCount(), pendingCollectionLogSize(), false);
            }
        });
    }

    private boolean refreshRecentCollectionPanel()
    {
        if (collectionLog == null || panel == null) return false;
        List<String> names = new ArrayList<>();
        List<Integer> recentItemIds = collectionLog.recentItemIds();
        for (int itemId : recentItemIds)
        {
            String name = client.getItemDefinition(itemId).getName();
            if (name != null && !name.trim().isEmpty()) names.add(name);
            if (names.size() == 3) break;
        }
        panel.setRecentCollections(names);
        return !recentItemIds.isEmpty();
    }

    private void captureCollectionHeaderKillCount()
    {
        Widget root = client.getWidget(InterfaceID.COLLECTION, 0);
        if (root != null) captureWidgetKillCount(root);
    }

    private void captureWidgetKillCount(Widget widget)
    {
        captureAbsoluteKillCount(widget.getText(), COLLECTION_HEADER_KC);
        captureAbsoluteKillCount(widget.getText(), COLLECTION_HEADER_COMPLETION);
        captureWidgetKillCount(widget.getDynamicChildren());
        captureWidgetKillCount(widget.getStaticChildren());
        captureWidgetKillCount(widget.getNestedChildren());
    }

    private void captureWidgetKillCount(Widget[] widgets)
    {
        if (widgets == null) return;
        for (Widget widget : widgets) if (widget != null) captureWidgetKillCount(widget);
    }

    private void captureAbsoluteKillCount(String rawText, Pattern pattern)
    {
        if (rawText == null) return;
        String text = net.runelite.client.util.Text.removeTags(rawText).trim();
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return;
        if (pattern == COMPLETION_COUNT
            && (matcher.group("pre") == null || matcher.group("pre").isEmpty())
            && (matcher.group("post") == null || matcher.group("post").isEmpty())) return;
        String name = matcher.group("boss").trim();
        try
        {
            mergeAbsoluteKillCount(name, Integer.parseInt(matcher.group("kc").replace(",", "")), Instant.now().toString());
        }
        catch (NumberFormatException ignored)
        {
            // Ignore malformed game text.
        }
    }

    private void mergeAbsoluteKillCount(String sourceName, int count, String capturedAt)
    {
        if (sourceName == null || sourceName.trim().isEmpty() || count < 0) return;
        String key = normalize(sourceName);
        absoluteKillCounts.compute(key, (ignored, current) -> current == null || count > current.count
            ? new IronPathDtos.AbsoluteKillCount(sourceName.trim(), count, capturedAt) : current);
        saveAbsoluteKillCounts();
        updateGoalProgress();
    }

    private void mergeHiscoreKillCounts(HiscoreResult result)
    {
        String capturedAt = Instant.now().toString();
        for (HiscoreSkill skill : HiscoreSkill.values())
        {
            if (skill.getType() != HiscoreSkillType.BOSS) continue;
            net.runelite.client.hiscore.Skill value = result.getSkill(skill);
            if (value != null && value.getLevel() >= 0)
            {
                mergeAbsoluteKillCount(skill.getName(), value.getLevel(), capturedAt);
            }
        }
    }

    private void refreshRuneLiteKillCounts()
    {
        String profile = configManager.getRSProfileKey();
        if (profile == null) return;
        String capturedAt = Instant.now().toString();
        for (String key : configManager.getRSProfileConfigurationKeys("killcount", profile, ""))
        {
            String value = configManager.getConfiguration("killcount", profile, key);
            try
            {
                mergeAbsoluteKillCount(key, Integer.parseInt(value), capturedAt);
            }
            catch (NumberFormatException ignored)
            {
                // RuneLite may keep non-KC values in the same group.
            }
        }
    }

    private void captureLiveContainers()
    {
        captureIfPresent("inventory", client.getItemContainer(InventoryID.INVENTORY));
        captureIfPresent("equipment", client.getItemContainer(InventoryID.EQUIPMENT));
        captureIfPresent(BANK_CONTAINER, client.getItemContainer(InventoryID.BANK));
        capturePotionStorage();
    }

    private void captureIfPresent(String name, ItemContainer container)
    {
        if (container != null) captureContainer(name, container);
    }

    private void captureContainer(String name, ItemContainer container)
    {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Item item : container.getItems())
        {
            if (item.getId() > 0 && item.getQuantity() > 0)
            {
                counts.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }
        containerSnapshots.put(name, counts);
    }

    private boolean capturePotionStorage()
    {
        Widget potionItems = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
        if (potionItems == null || potionItems.getDynamicChildren() == null) return false;

        Widget[] children = potionItems.getDynamicChildren();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int index = 0; index + 4 < children.length; index += 5)
        {
            Widget itemWidget = children[index + 1];
            Widget doseWidget = children[index + 3];
            if (itemWidget == null || doseWidget == null || itemWidget.getItemId() < 1) continue;

            int itemId = itemWidget.getItemId();
            int totalDoses = parsePotionDoses(doseWidget.getText());
            int withdrawDoses = potionDoseForName(client.getItemDefinition(itemId).getName());
            int quantity = withdrawDoses == 0 ? 0 : totalDoses / withdrawDoses;
            if (quantity > 0) counts.merge(itemId, quantity, Integer::sum);
        }
        Map<Integer, Integer> previous = containerSnapshots.put(POTION_STORAGE_CONTAINER, counts);
        return !counts.equals(previous);
    }

    static int parsePotionDoses(String text)
    {
        if (text == null) return 0;
        int separator = text.lastIndexOf(':');
        String quantity = (separator >= 0 ? text.substring(separator + 1) : text).replace(",", "").trim();
        try
        {
            return Math.max(0, Integer.parseInt(quantity));
        }
        catch (NumberFormatException ignored)
        {
            return 0;
        }
    }

    static int potionDoseForName(String itemName)
    {
        if (itemName == null) return 0;
        int length = itemName.length();
        if (length >= 3 && itemName.charAt(length - 3) == '(' && itemName.charAt(length - 1) == ')')
        {
            char dose = itemName.charAt(length - 2);
            if (dose >= '1' && dose <= '4') return dose - '0';
        }
        return 1;
    }

    static List<IronPathDtos.ItemRecord> snapshotItems(Map<String, Map<Integer, Integer>> snapshots)
    {
        Map<String, Map<Integer, Integer>> logicalSnapshots = new HashMap<>();
        for (Map.Entry<String, Map<Integer, Integer>> entry : snapshots.entrySet())
        {
            if (POTION_STORAGE_CONTAINER.equals(entry.getKey())) continue;
            logicalSnapshots.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        Map<Integer, Integer> potionStorage = snapshots.get(POTION_STORAGE_CONTAINER);
        if (potionStorage != null)
        {
            Map<Integer, Integer> bank = logicalSnapshots.computeIfAbsent(BANK_CONTAINER, ignored -> new HashMap<>());
            potionStorage.forEach((itemId, quantity) -> bank.merge(itemId, quantity, Integer::sum));
        }

        List<IronPathDtos.ItemRecord> items = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, Integer>> entry : logicalSnapshots.entrySet())
        {
            entry.getValue().forEach((itemId, quantity) ->
                items.add(new IronPathDtos.ItemRecord(itemId, quantity, entry.getKey())));
        }
        return items;
    }

    private void debounceBankSync()
    {
        if (!config.autoSync()) return;
        if (bankDebounce != null) bankDebounce.cancel(false);
        bankDebounce = executor.schedule(() -> clientThread.invokeLater(this::syncSnapshot), 2, TimeUnit.SECONDS);
    }

    private String token()
    {
        String value = configManager.getRSProfileConfiguration(IronPathConfig.GROUP, TOKEN_KEY);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private void loadPendingLoot()
    {
        String json = configManager.getRSProfileConfiguration(IronPathConfig.GROUP, QUEUE_KEY);
        if (json == null || json.isEmpty()) return;
        try
        {
            List<IronPathDtos.LootEvent> stored = gson.fromJson(json, new TypeToken<List<IronPathDtos.LootEvent>>() {}.getType());
            if (stored != null)
            {
                int from = Math.max(0, stored.size() - MAX_PENDING_LOOT);
                pendingLoot.addAll(stored.subList(from, stored.size()));
            }
        }
        catch (RuntimeException error)
        {
            LOGGER.log(Level.WARNING, "Discarding malformed Iron Path loot queue", error);
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, QUEUE_KEY);
        }
    }

    private void savePendingLoot()
    {
        if (configManager.getRSProfileKey() == null) return;
        if (pendingLoot.isEmpty())
        {
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, QUEUE_KEY);
        }
        else
        {
            configManager.setRSProfileConfiguration(IronPathConfig.GROUP, QUEUE_KEY, gson.toJson(pendingLoot));
        }
    }

    private void loadProfileState()
    {
        loadPendingLoot();
        loadPendingCollectionLog();
        loadKillCounts();
        loadAbsoluteKillCounts();
        String storedSync = configManager.getRSProfileConfiguration(IronPathConfig.GROUP, LAST_SYNC_KEY);
        try
        {
            lastSuccessfulSync = storedSync == null ? null : Instant.parse(storedSync);
        }
        catch (RuntimeException ignored)
        {
            lastSuccessfulSync = null;
        }
    }

    private void loadPendingCollectionLog()
    {
        String json = configManager.getRSProfileConfiguration(IronPathConfig.GROUP, COLLECTION_QUEUE_KEY);
        if (json == null || json.isEmpty()) return;
        try
        {
            List<IronPathDtos.CollectionLogSection> stored = gson.fromJson(json,
                new TypeToken<List<IronPathDtos.CollectionLogSection>>() {}.getType());
            if (stored != null)
            {
                synchronized (pendingCollectionLog)
                {
                    for (IronPathDtos.CollectionLogSection section : stored)
                    {
                        if (section != null && section.key != null) pendingCollectionLog.put(section.key, section);
                    }
                }
            }
            List<Integer> recent = gson.fromJson(
                configManager.getRSProfileConfiguration(IronPathConfig.GROUP, COLLECTION_RECENT_KEY),
                new TypeToken<List<Integer>>() {}.getType());
            if (recent != null) pendingCollectionRecent.addAll(recent);
        }
        catch (RuntimeException error)
        {
            LOGGER.log(Level.WARNING, "Discarding malformed collection-log queue", error);
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, COLLECTION_QUEUE_KEY);
        }
    }

    private void savePendingCollectionLog()
    {
        if (configManager.getRSProfileKey() == null) return;
        if (pendingCollectionLog.isEmpty())
        {
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, COLLECTION_QUEUE_KEY);
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, COLLECTION_RECENT_KEY);
        }
        else
        {
            configManager.setRSProfileConfiguration(IronPathConfig.GROUP, COLLECTION_QUEUE_KEY,
                gson.toJson(new ArrayList<>(pendingCollectionLog.values())));
            configManager.setRSProfileConfiguration(IronPathConfig.GROUP, COLLECTION_RECENT_KEY,
                gson.toJson(pendingCollectionRecent));
        }
    }

    private void loadAbsoluteKillCounts()
    {
        String json = configManager.getRSProfileConfiguration(IronPathConfig.GROUP, ABSOLUTE_KILL_COUNTS_KEY);
        if (json == null || json.isEmpty()) return;
        try
        {
            List<IronPathDtos.AbsoluteKillCount> stored = gson.fromJson(json,
                new TypeToken<List<IronPathDtos.AbsoluteKillCount>>() {}.getType());
            if (stored != null)
            {
                for (IronPathDtos.AbsoluteKillCount count : stored)
                {
                    if (count != null) mergeAbsoluteKillCount(count.sourceName, count.count, count.capturedAt);
                }
            }
        }
        catch (RuntimeException error)
        {
            LOGGER.log(Level.WARNING, "Discarding malformed absolute kill counts", error);
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, ABSOLUTE_KILL_COUNTS_KEY);
        }
    }

    private void saveAbsoluteKillCounts()
    {
        if (configManager.getRSProfileKey() == null) return;
        configManager.setRSProfileConfiguration(IronPathConfig.GROUP, ABSOLUTE_KILL_COUNTS_KEY,
            gson.toJson(new ArrayList<>(absoluteKillCounts.values())));
    }

    private void loadKillCounts()
    {
        String json = configManager.getRSProfileConfiguration(IronPathConfig.GROUP, KILL_COUNTS_KEY);
        if (json == null || json.isEmpty()) return;
        try
        {
            List<IronPathDtos.KillCount> stored = gson.fromJson(json,
                new TypeToken<List<IronPathDtos.KillCount>>() {}.getType());
            if (stored == null) return;
            synchronized (trackedKills)
            {
                for (IronPathDtos.KillCount count : stored)
                {
                    if (count != null && count.npcId > 0 && count.count > 0)
                    {
                        trackedKills.put(count.npcId, count);
                    }
                }
            }
        }
        catch (RuntimeException error)
        {
            LOGGER.log(Level.WARNING, "Discarding malformed Iron Path kill counts", error);
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, KILL_COUNTS_KEY);
        }
    }

    private void saveKillCounts()
    {
        if (configManager.getRSProfileKey() == null) return;
        if (trackedKills.isEmpty())
        {
            configManager.unsetRSProfileConfiguration(IronPathConfig.GROUP, KILL_COUNTS_KEY);
        }
        else
        {
            configManager.setRSProfileConfiguration(IronPathConfig.GROUP, KILL_COUNTS_KEY,
                gson.toJson(new ArrayList<>(trackedKills.values())));
        }
    }

    private void saveLastSuccessfulSync()
    {
        if (configManager.getRSProfileKey() != null && lastSuccessfulSync != null)
        {
            configManager.setRSProfileConfiguration(IronPathConfig.GROUP, LAST_SYNC_KEY,
                lastSuccessfulSync.toString());
        }
    }

    private void updatePanelData()
    {
        IronPathPanel currentPanel = panel;
        if (currentPanel == null) return;
        List<IronPathDtos.KillCount> recent;
        synchronized (trackedKills)
        {
            recent = new ArrayList<>(trackedKills.values());
        }
        recent.sort(Comparator.comparing((IronPathDtos.KillCount kill) ->
            kill.lastKilledAt == null ? "" : kill.lastKilledAt).reversed());
        currentPanel.setKillActivity(observedKills, pendingLootSize(), recent);
        currentPanel.setSyncState(lastSuccessfulSync, snapshotUploadInFlight.get(), pendingLootSize(), config.autoSync());
        currentPanel.setCollectionLogState(collectionLog == null ? 0 : collectionLog.sectionCount(), pendingCollectionLogSize(), collectionLog != null && collectionLog.isAwaitingSearch());
        clientThread.invokeLater(() ->
        {
            refreshRecentCollectionPanel();
            return true;
        });
        updateGoalProgress();
    }

    private void updateGoalProgress()
    {
        IronPathPanel currentPanel = panel;
        if (currentPanel == null) return;
        List<IronPathDtos.KillCount> kills;
        synchronized (trackedKills)
        {
            kills = new ArrayList<>(trackedKills.values());
        }
        List<IronPathGoalProgress> progress = new ArrayList<>();
        for (IronPathDtos.GoalSummary goal : currentGoals)
        {
            progress.add(IronPathGoalProgress.from(goal, kills,
                new HashMap<>(absoluteKillCounts), new HashMap<>(liveSkillLevels),
                new HashMap<>(liveSkillXps), new HashMap<>(liveQuestStates)));
        }
        currentPanel.setGoals(progress);
    }

    private int pendingLootSize()
    {
        synchronized (pendingLoot)
        {
            return pendingLoot.size();
        }
    }

    private int pendingCollectionLogSize()
    {
        synchronized (pendingCollectionLog)
        {
            return pendingCollectionLog.size();
        }
    }

    private String connectedDetail()
    {
        return config.autoSync() ? "Connected · automatic sync enabled" : "Connected · manual sync only";
    }

    private static String accountType(AccountType type)
    {
        if (type == AccountType.IRONMAN) return "Ironman";
        if (type == AccountType.HARDCORE_IRONMAN) return "Hardcore Ironman";
        if (type == AccountType.ULTIMATE_IRONMAN) return "Ultimate Ironman";
        if (type == AccountType.GROUP_IRONMAN) return "Group Ironman";
        if (type == AccountType.HARDCORE_GROUP_IRONMAN) return "Hardcore Group Ironman";
        return "Normal";
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ENGLISH)
            .replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
    }

    private static String questState(QuestState state)
    {
        if (state == QuestState.FINISHED) return "finished";
        if (state == QuestState.IN_PROGRESS) return "in_progress";
        return "not_started";
    }

    private static BufferedImage createPanelIcon()
    {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(213, 173, 85));
        graphics.fillPolygon(new int[]{8, 14, 13, 8, 3, 2}, new int[]{1, 4, 12, 15, 12, 4}, 6);
        graphics.setColor(new Color(31, 36, 31));
        graphics.fillPolygon(new int[]{8, 12, 11, 8, 5, 4}, new int[]{3, 5, 11, 13, 11, 5}, 6);
        graphics.setColor(new Color(213, 173, 85));
        graphics.drawLine(6, 6, 10, 10);
        graphics.drawLine(10, 6, 6, 10);
        graphics.dispose();
        return image;
    }
}
