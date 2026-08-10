package gg.ironpath;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class IronPathGoalProgress
{
    final String id;
    final String title;
    final String kind;
    final String status;
    final String detail;
    final String context;
    final int percent;

    private IronPathGoalProgress(String id, String title, String kind, String status,
                                 String detail, String context, int percent)
    {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.status = "complete".equals(status) ? "complete" : "active";
        this.detail = detail;
        this.context = context;
        this.percent = "complete".equals(this.status) ? 100 : Math.max(0, Math.min(100, percent));
    }

    static IronPathGoalProgress from(
        IronPathDtos.GoalSummary goal,
        Collection<IronPathDtos.KillCount> kills,
        Map<String, Integer> skillLevels,
        Map<String, String> questStates)
    {
        return from(goal, kills, skillLevels, Collections.emptyMap(), questStates);
    }

    static IronPathGoalProgress from(
        IronPathDtos.GoalSummary goal,
        Collection<IronPathDtos.KillCount> kills,
        Map<String, Integer> skillLevels,
        Map<String, Integer> skillXps,
        Map<String, String> questStates)
    {
        String title = goal.title == null || goal.title.trim().isEmpty() ? "Untitled goal" : goal.title;
        String kind = goal.kind == null ? "goal" : goal.kind;
        String status = "complete".equals(goal.status) ? "complete" : "active";
        IronPathDtos.GoalSettings settings = goal.settings;

        if ("grind".equals(kind) && settings != null)
        {
            List<Integer> npcIds = settings.npcIds == null ? Collections.emptyList() : settings.npcIds;
            int locallyTracked = kills.stream()
                .filter(kill -> npcIds.contains(kill.npcId))
                .mapToInt(kill -> kill.count)
                .sum();
            int observed = Math.max(settings.observedKc, locallyTracked);
            int total = Math.max(0, settings.startingKc) + observed;
            int rate = Math.max(0, settings.dropRate);
            int percent = rate == 0 ? 0 : (int) Math.round(observed * 100d / rate);
            String monster = nonEmpty(settings.monster, "Tracked monster");
            String context = rate == 0 ? monster : monster + " · 1/" + formatNumber(rate);
            return new IronPathGoalProgress(goal.id, title, kind, status,
                formatNumber(total) + " total KC · " + formatNumber(observed) + " synced",
                context, percent);
        }

        if ("banked_xp".equals(kind) && settings != null)
        {
            int current = skillLevels.getOrDefault(normalize(settings.skill), settings.currentLevel);
            int target = settings.targetLevel;
            int percent = target <= 1 ? 0 : (int) Math.round((Math.max(1, current) - 1) * 100d / (target - 1));
            return new IronPathGoalProgress(goal.id, title, kind, status,
                "Level " + current + " / " + target,
                nonEmpty(settings.skill, "Banked experience"), percent);
        }

        if ("skill".equals(kind) && settings != null)
        {
            int currentLevel = skillLevels.getOrDefault(normalize(settings.skill), settings.currentLevel);
            int currentXp = skillXps.getOrDefault(normalize(settings.skill), settings.currentXp);
            int targetXp = Math.max(1, settings.targetXp);
            int percent = (int) Math.round(Math.min(1d, currentXp / (double) targetXp) * 100d);
            return new IronPathGoalProgress(goal.id, title, kind,
                currentXp >= targetXp ? "complete" : status,
                "Level " + currentLevel + " / " + settings.targetLevel,
                formatNumber(Math.max(0, targetXp - currentXp)) + " XP remaining", percent);
        }

        if ("quest".equals(kind))
        {
            String state = questStates.get(normalize(title));
            if (state == null && settings != null) state = settings.state;
            String detail = "in_progress".equals(state) ? "In progress"
                : "finished".equals(state) ? "Completed" : "Not started";
            int percent = "finished".equals(state) ? 100 : "in_progress".equals(state) ? 50 : 0;
            return new IronPathGoalProgress(goal.id, title, kind, status, detail, "Quest", percent);
        }

        return new IronPathGoalProgress(goal.id, title, kind, status,
            goal.status == null ? "Active" : capitalize(goal.status), capitalize(kind.replace('_', ' ')), 0);
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH).replace('_', ' ').replace('-', ' ')
            .replaceAll("\\s+", " ");
    }

    private static String nonEmpty(String value, String fallback)
    {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String capitalize(String value)
    {
        if (value == null || value.isEmpty()) return "Goal";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String formatNumber(int value)
    {
        return String.format(Locale.ENGLISH, "%,d", value);
    }
}
