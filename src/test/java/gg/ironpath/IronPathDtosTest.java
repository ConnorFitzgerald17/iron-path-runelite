package gg.ironpath;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class IronPathDtosTest
{
    @Test
    public void keepsStableLootEventIdentity()
    {
        IronPathDtos.LootEvent event = new IronPathDtos.LootEvent(
            "683a1837-c292-4f8d-8ec2-a38275634a4c", "2026-08-06T12:00:00Z",
            6766, "Lizardman shaman", Collections.singletonList(new IronPathDtos.LootItem(13576, 1)));
        assertEquals("683a1837-c292-4f8d-8ec2-a38275634a4c", event.eventId);
        assertEquals(13576, event.items.get(0).itemId);
    }

    @Test
    public void readsGrindSettingsAndCalculatesTrackedProgress()
    {
        String json = "{\"id\":\"grind-dwh\",\"kind\":\"grind\",\"title\":\"The red hammer\","
            + "\"status\":\"complete\",\"settings\":{\"monster\":\"Lizardman shaman\",\"npcIds\":[6766,6767],"
            + "\"dropRate\":3000,\"startingKc\":2148,\"observedKc\":4}}";
        IronPathDtos.GoalSummary goal = new Gson().fromJson(json, IronPathDtos.GoalSummary.class);
        IronPathDtos.KillCount matching = new IronPathDtos.KillCount(6766, "Lizardman shaman", 12, "2026-08-10T12:00:00Z");
        IronPathDtos.KillCount other = new IronPathDtos.KillCount(1, "Goblin", 99, "2026-08-10T11:00:00Z");

        IronPathGoalProgress progress = IronPathGoalProgress.from(
            goal, Arrays.asList(matching, other), Collections.emptyMap(), Collections.emptyMap());

        assertEquals("2,160 total KC · 12 synced", progress.detail);
        assertEquals("Lizardman shaman · 1/3,000", progress.context);
        assertEquals("grind-dwh", progress.id);
        assertEquals("complete", progress.status);
        assertEquals(100, progress.percent);
    }

    @Test
    public void usesLiveGameStateForQuestAndSkillGoals()
    {
        Gson gson = new Gson();
        IronPathDtos.GoalSummary quest = gson.fromJson(
            "{\"kind\":\"quest\",\"title\":\"Dragon Slayer II\",\"settings\":{\"state\":\"not_started\"}}",
            IronPathDtos.GoalSummary.class);
        IronPathDtos.GoalSummary xp = gson.fromJson(
            "{\"kind\":\"banked_xp\",\"title\":\"Bank 77 Prayer\",\"settings\":{\"skill\":\"Prayer\",\"currentLevel\":70,\"targetLevel\":77}}",
            IronPathDtos.GoalSummary.class);
        HashMap<String, String> quests = new HashMap<>();
        quests.put("dragon slayer ii", "finished");
        HashMap<String, Integer> levels = new HashMap<>();
        levels.put("prayer", 72);

        assertEquals("Completed", IronPathGoalProgress.from(quest, Collections.emptyList(), levels, quests).detail);
        assertEquals("Level 72 / 77", IronPathGoalProgress.from(xp, Collections.emptyList(), levels, quests).detail);
    }

    @Test
    public void usesLiveXpForDerivedSkillGoalProgress()
    {
        IronPathDtos.GoalSummary goal = new Gson().fromJson(
            "{\"id\":\"skill-smithing\",\"kind\":\"skill\",\"title\":\"Train 70 Smithing\",\"status\":\"active\","
                + "\"settings\":{\"skill\":\"Smithing\",\"currentLevel\":69,\"currentXp\":668051,\"targetLevel\":70,\"targetXp\":737627}}",
            IronPathDtos.GoalSummary.class);
        HashMap<String, Integer> levels = new HashMap<>();
        levels.put("smithing", 70);
        HashMap<String, Integer> xps = new HashMap<>();
        xps.put("smithing", 737627);

        IronPathGoalProgress progress = IronPathGoalProgress.from(
            goal, Collections.emptyList(), levels, xps, Collections.emptyMap());

        assertEquals("complete", progress.status);
        assertEquals("Level 70 / 70", progress.detail);
        assertEquals(100, progress.percent);
    }

    @Test
    public void serializesCollectionLogSectionSnapshots()
    {
        IronPathDtos.CollectionLogSection section = new IronPathDtos.CollectionLogSection(
            "bosses-barrows-chests", "Bosses", "Barrows Chests", 1, 2, "2026-08-10T18:00:00Z",
            Arrays.asList(
                new IronPathDtos.CollectionLogSlot(4708, 1, true, 0),
                new IronPathDtos.CollectionLogSlot(4712, 0, false, 1)));
        String json = new Gson().toJson(section);
        IronPathDtos.CollectionLogSection restored = new Gson().fromJson(json, IronPathDtos.CollectionLogSection.class);
        assertEquals("bosses-barrows-chests", restored.key);
        assertEquals(2, restored.slots.size());
        assertEquals(4708, restored.slots.get(0).itemId);
    }

    @Test
    public void serializesAuthoritativeCharacterMetadataInSnapshots()
    {
        IronPathDtos.Snapshot snapshot = new IronPathDtos.Snapshot(
            "2026-08-10T18:00:00Z", "Iron Two", "Group Ironman", 101,
            Collections.singletonList(new IronPathDtos.SkillRecord("Herblore", 70, 737627)),
            Collections.emptyList(), Collections.emptyList());
        String json = new Gson().toJson(snapshot);
        IronPathDtos.Snapshot restored = new Gson().fromJson(json, IronPathDtos.Snapshot.class);
        assertEquals("Group Ironman", restored.accountType);
        assertEquals(101, restored.combatLevel);
        assertEquals(737627, restored.skills.get(0).xp);
    }

    @Test
    public void persistsKillCountsWithGson()
    {
        Gson gson = new Gson();
        IronPathDtos.KillCount original = new IronPathDtos.KillCount(
            6766, "Lizardman shaman", 18, "2026-08-10T12:30:00Z");

        IronPathDtos.KillCount restored = gson.fromJson(gson.toJson(original), IronPathDtos.KillCount.class);

        assertEquals(6766, restored.npcId);
        assertEquals(18, restored.count);
        assertEquals("2026-08-10T12:30:00Z", restored.lastKilledAt);
    }

    @Test
    public void serializesStatusOnlyGoalUpdates()
    {
        String json = new Gson().toJson(new IronPathDtos.GoalStatusUpdate("complete"));
        assertEquals("{\"status\":\"complete\"}", json);
    }

    @Test
    public void parsesPotionStorageDoseLabels()
    {
        assertEquals(1234, IronPathPlugin.parsePotionDoses("Doses: 1,234"));
        assertEquals(42, IronPathPlugin.parsePotionDoses("Quantity: 42"));
        assertEquals(0, IronPathPlugin.parsePotionDoses("Doses: —"));
        assertEquals(4, IronPathPlugin.potionDoseForName("Prayer potion(4)"));
        assertEquals(1, IronPathPlugin.potionDoseForName("Ranarr potion (unf)"));
    }

    @Test
    public void mergesPotionStorageIntoTheBankSnapshot()
    {
        Map<String, Map<Integer, Integer>> snapshots = new HashMap<>();
        snapshots.put("bank", new HashMap<>(Collections.singletonMap(2434, 2)));
        snapshots.put("potion-storage", new HashMap<>(Collections.singletonMap(2434, 3)));
        snapshots.put("inventory", new HashMap<>(Collections.singletonMap(995, 100)));

        List<IronPathDtos.ItemRecord> items = IronPathPlugin.snapshotItems(snapshots);

        assertEquals(5, itemQuantity(items, "bank", 2434));
        assertEquals(100, itemQuantity(items, "inventory", 995));
        assertEquals(0, itemQuantity(items, "potion-storage", 2434));
    }

    private static int itemQuantity(List<IronPathDtos.ItemRecord> items, String container, int itemId)
    {
        for (IronPathDtos.ItemRecord item : items)
        {
            if (container.equals(item.container) && item.itemId == itemId) return item.quantity;
        }
        return 0;
    }
}
