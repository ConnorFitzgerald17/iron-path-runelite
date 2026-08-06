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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(
    name = "Iron Path",
    description = "Sync quest goals, item grinds, notable drops, and banked experience",
    tags = {"ironman", "goals", "quests", "loot", "banked xp"}
)
public class IronPathPlugin extends Plugin
{
    private static final Logger LOGGER = Logger.getLogger(IronPathPlugin.class.getName());
    private static final String TOKEN_KEY = "deviceToken";
    private static final String QUEUE_KEY = "pendingLoot";
    private static final int MAX_PENDING_LOOT = 250;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ConfigManager configManager;
    @Inject private ScheduledExecutorService executor;
    @Inject private IronPathConfig config;
    @Inject private IronPathApiClient apiClient;
    @Inject private Gson gson;

    private final Map<String, Map<Integer, Integer>> containerSnapshots = new HashMap<>();
    private final List<IronPathDtos.LootEvent> pendingLoot = new ArrayList<>();
    private IronPathPanel panel;
    private NavigationButton navigationButton;
    private ScheduledFuture<?> periodicSync;
    private ScheduledFuture<?> bankDebounce;
    private boolean lootUploadInFlight;
    private int observedKills;

    @Provides
    IronPathConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(IronPathConfig.class);
    }

    @Override
    protected void startUp()
    {
        panel = new IronPathPanel();
        panel.setActions(this::connect, () -> clientThread.invokeLater(this::syncSnapshot));
        navigationButton = NavigationButton.builder()
            .tooltip("Iron Path")
            .icon(createPanelIcon())
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);
        loadPendingLoot();
        refreshConnectionState();

        periodicSync = executor.scheduleWithFixedDelay(() ->
        {
            if (config.autoSync())
            {
                clientThread.invokeLater(this::syncSnapshot);
                refreshGoals();
                flushPendingLoot();
            }
        }, 20, 5, TimeUnit.MINUTES);
    }

    @Override
    protected void shutDown()
    {
        if (periodicSync != null) periodicSync.cancel(false);
        if (bankDebounce != null) bankDebounce.cancel(false);
        savePendingLoot();
        if (navigationButton != null) clientToolbar.removeNavigation(navigationButton);
        panel = null;
        navigationButton = null;
        containerSnapshots.clear();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            executor.schedule(() -> clientThread.invokeLater(() ->
            {
                captureLiveContainers();
                syncSnapshot();
                refreshGoals();
                flushPendingLoot();
            }), 3, TimeUnit.SECONDS);
        }
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        synchronized (pendingLoot)
        {
            pendingLoot.clear();
            loadPendingLoot();
        }
        containerSnapshots.clear();
        observedKills = 0;
        if (panel != null) panel.setObservedKills(0);
        refreshConnectionState();
        refreshGoals();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        ItemContainer container = event.getItemContainer();
        int id = container.getId();
        if (id == InventoryID.BANK.getId())
        {
            captureContainer("bank", container);
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
    public void onNpcLootReceived(NpcLootReceived event)
    {
        List<IronPathDtos.LootItem> items = new ArrayList<>();
        for (ItemStack stack : event.getItems())
        {
            if (stack.getId() > 0 && stack.getQuantity() > 0)
            {
                items.add(new IronPathDtos.LootItem(stack.getId(), stack.getQuantity()));
            }
        }
        IronPathDtos.LootEvent loot = new IronPathDtos.LootEvent(
            UUID.randomUUID().toString(), Instant.now().toString(), event.getNpc().getId(),
            event.getNpc().getName() == null ? "Unknown NPC" : event.getNpc().getName(), items);
        synchronized (pendingLoot)
        {
            pendingLoot.add(loot);
            while (pendingLoot.size() > MAX_PENDING_LOOT) pendingLoot.remove(0);
            savePendingLoot();
        }
        observedKills++;
        if (panel != null) panel.setObservedKills(observedKills);
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
        panel.setConnected(false, client.getLocalPlayer().getName(), "Exchanging linking code…");
        apiClient.exchangeCode(code, client.getLocalPlayer().getName(), response ->
        {
            if (response == null || response.token == null)
            {
                panel.setConnected(false, client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName(), response == null || response.error == null ? "Connection failed" : response.error);
                return;
            }
            configManager.setRSProfileConfiguration(IronPathConfig.GROUP, TOKEN_KEY, response.token);
            panel.setConnected(true, client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName(), "Connected · first sync pending");
            clientThread.invokeLater(this::syncSnapshot);
            refreshGoals();
            flushPendingLoot();
        });
    }

    private void refreshConnectionState()
    {
        if (panel == null) return;
        String token = token();
        String name = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
        panel.setConnected(token != null, name, token == null ? "Not linked" : "Connected · waiting for game data");
    }

    private void syncSnapshot()
    {
        String token = token();
        if (token == null || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;
        captureLiveContainers();

        List<IronPathDtos.SkillRecord> skills = new ArrayList<>();
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            skills.add(new IronPathDtos.SkillRecord(skill.getName(), client.getRealSkillLevel(skill), client.getSkillExperience(skill)));
        }

        List<IronPathDtos.QuestRecord> quests = new ArrayList<>();
        for (Quest quest : Quest.values())
        {
            quests.add(new IronPathDtos.QuestRecord(quest.getName(), questState(quest.getState(client))));
        }

        List<IronPathDtos.ItemRecord> items = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, Integer>> entry : containerSnapshots.entrySet())
        {
            entry.getValue().forEach((itemId, quantity) -> items.add(new IronPathDtos.ItemRecord(itemId, quantity, entry.getKey())));
        }

        IronPathDtos.Snapshot snapshot = new IronPathDtos.Snapshot(
            Instant.now().toString(), client.getLocalPlayer().getName(), skills, quests, items);
        panel.setConnected(true, client.getLocalPlayer().getName(), "Uploading snapshot…");
        apiClient.sendSnapshot(token, snapshot, success -> panel.setConnected(
            success, client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName(),
            success ? "Synced just now" : "Sync failed · will retry"));
    }

    private void refreshGoals()
    {
        String token = token();
        if (token == null || panel == null) return;
        apiClient.fetchGoals(token, response ->
        {
            if (response != null)
            {
                panel.setGoals(response.goals);
                if (response.characterName != null) panel.setConnected(true, response.characterName, "Connected");
            }
        });
    }

    private void flushPendingLoot()
    {
        String token = token();
        if (token == null || lootUploadInFlight) return;
        List<IronPathDtos.LootEvent> batch;
        synchronized (pendingLoot)
        {
            if (pendingLoot.isEmpty()) return;
            batch = new ArrayList<>(pendingLoot);
            lootUploadInFlight = true;
        }
        apiClient.sendLoot(token, batch, success ->
        {
            synchronized (pendingLoot)
            {
                if (success)
                {
                    int remove = Math.min(batch.size(), pendingLoot.size());
                    pendingLoot.subList(0, remove).clear();
                    savePendingLoot();
                }
                lootUploadInFlight = false;
            }
        });
    }

    private void captureLiveContainers()
    {
        captureIfPresent("inventory", client.getItemContainer(InventoryID.INVENTORY));
        captureIfPresent("equipment", client.getItemContainer(InventoryID.EQUIPMENT));
        captureIfPresent("bank", client.getItemContainer(InventoryID.BANK));
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
            if (stored != null) pendingLoot.addAll(stored);
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
