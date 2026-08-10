package gg.ironpath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class IronPathDtos
{
    private IronPathDtos() {}

    static final class LinkRequest
    {
        final String code;
        final String characterName;
        final String clientVersion;

        LinkRequest(String code, String characterName, String clientVersion)
        {
            this.code = code;
            this.characterName = characterName;
            this.clientVersion = clientVersion;
        }
    }

    static final class LinkResponse
    {
        String token;
        String characterId;
        String error;
    }

    static final class SkillRecord
    {
        final String skill;
        final int level;
        final int xp;

        SkillRecord(String skill, int level, int xp)
        {
            this.skill = skill;
            this.level = level;
            this.xp = xp;
        }
    }

    static final class QuestRecord
    {
        final String quest;
        final String state;

        QuestRecord(String quest, String state)
        {
            this.quest = quest;
            this.state = state;
        }
    }

    static final class ItemRecord
    {
        final int itemId;
        final int quantity;
        final String container;

        ItemRecord(int itemId, int quantity, String container)
        {
            this.itemId = itemId;
            this.quantity = quantity;
            this.container = container;
        }
    }

    static final class Snapshot
    {
        final String capturedAt;
        final String characterName;
        final String accountType;
        final int combatLevel;
        final List<SkillRecord> skills;
        final List<QuestRecord> quests;
        final List<ItemRecord> items;

        Snapshot(String capturedAt, String characterName, String accountType, int combatLevel, List<SkillRecord> skills,
                 List<QuestRecord> quests, List<ItemRecord> items)
        {
            this.capturedAt = capturedAt;
            this.characterName = characterName;
            this.accountType = accountType;
            this.combatLevel = combatLevel;
            this.skills = skills;
            this.quests = quests;
            this.items = items;
        }
    }

    static final class LootItem
    {
        final int itemId;
        final int quantity;

        LootItem(int itemId, int quantity)
        {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }

    static final class LootEvent
    {
        final String eventId;
        final String occurredAt;
        final int npcId;
        final String npcName;
        final List<LootItem> items;

        LootEvent(String eventId, String occurredAt, int npcId, String npcName, List<LootItem> items)
        {
            this.eventId = eventId;
            this.occurredAt = occurredAt;
            this.npcId = npcId;
            this.npcName = npcName;
            this.items = items;
        }
    }

    static final class LootBatch
    {
        final List<LootEvent> events;
        LootBatch(List<LootEvent> events) { this.events = events; }
    }

    static final class KillCount
    {
        final int npcId;
        String npcName;
        int count;
        String lastKilledAt;

        KillCount(int npcId, String npcName, int count, String lastKilledAt)
        {
            this.npcId = npcId;
            this.npcName = npcName;
            this.count = count;
            this.lastKilledAt = lastKilledAt;
        }

        void record(String name, String occurredAt)
        {
            count++;
            if (name != null && !name.trim().isEmpty()) npcName = name;
            lastKilledAt = occurredAt;
        }
    }

    static final class GoalSummary
    {
        String id;
        String kind;
        String title;
        String status;
        boolean isPublic;
        GoalSettings settings;
    }

    static final class GoalStatusUpdate
    {
        final String status;

        GoalStatusUpdate(String status)
        {
            this.status = status;
        }
    }

    static final class GoalSettings
    {
        String monster;
        String targetItemName;
        String skill;
        String state;
        int dropRate;
        int startingKc;
        int observedKc;
        int currentLevel;
        int targetLevel;
        int currentXp;
        int targetXp;
        List<Integer> npcIds = Collections.emptyList();
    }

    static final class CollectionLogSlot
    {
        final int itemId;
        final int quantity;
        final boolean obtained;
        final int slotOrder;

        CollectionLogSlot(int itemId, int quantity, boolean obtained, int slotOrder)
        {
            this.itemId = itemId;
            this.quantity = quantity;
            this.obtained = obtained;
            this.slotOrder = slotOrder;
        }
    }

    static final class CollectionLogSection
    {
        final String key;
        final String category;
        final String name;
        final int obtainedCount;
        final int totalCount;
        final String capturedAt;
        final List<CollectionLogSlot> slots;

        CollectionLogSection(String key, String category, String name, int obtainedCount,
                             int totalCount, String capturedAt, List<CollectionLogSlot> slots)
        {
            this.key = key;
            this.category = category;
            this.name = name;
            this.obtainedCount = obtainedCount;
            this.totalCount = totalCount;
            this.capturedAt = capturedAt;
            this.slots = slots;
        }
    }

    static final class GoalsResponse
    {
        String characterName;
        List<GoalSummary> goals = new ArrayList<>();
    }
}
