package gg.ironpath;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptEvent;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class IronPathCollectionLog
{
    private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
    private static final int COLLECTION_TAB_ENUM = 2102;
    private static final int TAB_PAGE_ENUM_PARAM = 683;
    private static final int PAGE_NAME_PARAM = 689;
    private static final int PAGE_ITEM_ENUM_PARAM = 690;
    private static final int SETTLE_TICKS = 3;
    private static final String[] CATEGORIES = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
    private static final Pattern PROGRESS_FRACTION = Pattern.compile("([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)");
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]*");

    private final Client client;
    private final Consumer<List<IronPathDtos.CollectionLogSection>> listener;
    private final List<PageDefinition> definitions = new ArrayList<>();
    private final Map<Integer, Integer> harvest = new HashMap<>();
    private final List<Integer> latestItems = new ArrayList<>();
    private IronPathDtos.CollectionLogProgress latestProgress;
    private boolean awaitingSearch;
    private boolean receiving;
    private int lastTransmitTick = -1;

    IronPathCollectionLog(Client client, Consumer<List<IronPathDtos.CollectionLogSection>> listener)
    {
        this.client = client;
        this.listener = listener;
    }

    void collectionOpened()
    {
        if (isAnotherPlayersLog()) return;
        loadDefinitions();
        awaitingSearch = false;
        receiving = false;
        harvest.clear();
        lastTransmitTick = -1;
    }

    boolean requestSync()
    {
        if (isAnotherPlayersLog() || !loadDefinitions()) return false;
        Widget search = client.getWidget(InterfaceID.Collection.SEARCH_BUTTON);
        if (search == null || search.isHidden()) return false;
        harvest.clear();
        receiving = true;
        awaitingSearch = true;
        lastTransmitTick = client.getTickCount();
        client.menuAction(search.getIndex(), search.getId(), MenuAction.CC_OP, 1,
            search.getItemId(), "Search", "");
        return true;
    }

    List<Integer> recentItemIds()
    {
        Widget latest = client.getWidget(InterfaceID.CollectionOverview.LATEST_ITEMS_DATA);
        if (latest == null) return new ArrayList<>(latestItems);
        Set<Integer> ids = new LinkedHashSet<>();
        collectItems(latest, ids);
        List<Integer> result = new ArrayList<>(ids);
        List<Integer> limited = result.size() <= 10 ? result : new ArrayList<>(result.subList(0, 10));
        latestItems.clear();
        latestItems.addAll(limited);
        return new ArrayList<>(latestItems);
    }

    IronPathDtos.CollectionLogProgress overviewProgress()
    {
        loadDefinitions();
        int definitionTotal = uniqueDefinitionItemCount();
        Widget root = client.getWidget(InterfaceID.CollectionOverview.UNIVERSE);
        IronPathDtos.CollectionLogProgress progress = findProgress(root, definitionTotal, null);
        if (progress == null)
        {
            Integer obtained = widgetNumber(client.getWidget(InterfaceID.CollectionOverview.PROGRESS_LEFT_TEXT));
            Integer total = widgetNumber(client.getWidget(InterfaceID.CollectionOverview.PROGRESS_RIGHT_TEXT));
            if (validProgress(obtained, total, definitionTotal))
            {
                progress = new IronPathDtos.CollectionLogProgress(obtained, total);
            }
        }
        if (progress != null) latestProgress = progress;
        return latestProgress;
    }

    boolean onScriptPreFired(int scriptId, ScriptEvent event)
    {
        if (scriptId != COLLECTION_DELAYED_TRANSMIT || !awaitingSearch || isAnotherPlayersLog()) return false;
        Object[] arguments = event == null ? null : event.getArguments();
        if (arguments == null || arguments.length < 3 || !(arguments[1] instanceof Integer) || !(arguments[2] instanceof Integer)) return false;
        int itemId = (Integer) arguments[1];
        int quantity = (Integer) arguments[2];
        if (itemId <= 0 || quantity <= 0) return false;
        receiving = true;
        harvest.put(itemId, quantity);
        lastTransmitTick = client.getTickCount();
        return true;
    }

    boolean onGameTick()
    {
        if (!receiving || lastTransmitTick < 0 || lastTransmitTick + SETTLE_TICKS >= client.getTickCount()) return false;
        List<IronPathDtos.CollectionLogSection> sections = snapshot();
        receiving = false;
        awaitingSearch = false;
        lastTransmitTick = -1;
        if (!sections.isEmpty()) listener.accept(sections);
        return !sections.isEmpty();
    }

    int sectionCount()
    {
        return definitions.size();
    }

    boolean isAwaitingSearch()
    {
        return awaitingSearch;
    }

    void reset()
    {
        definitions.clear();
        harvest.clear();
        latestItems.clear();
        latestProgress = null;
        awaitingSearch = false;
        receiving = false;
        lastTransmitTick = -1;
    }

    private boolean loadDefinitions()
    {
        EnumComposition tabEnum = client.getEnum(COLLECTION_TAB_ENUM);
        int[] tabStructIds = tabEnum == null ? null : tabEnum.getIntVals();
        if (tabStructIds == null || tabStructIds.length == 0) return false;
        List<PageDefinition> loaded = new ArrayList<>();
        for (int tabIndex = 0; tabIndex < Math.min(tabStructIds.length, CATEGORIES.length); tabIndex++)
        {
            StructComposition tab = client.getStructComposition(tabStructIds[tabIndex]);
            EnumComposition pages = client.getEnum(tab.getIntValue(TAB_PAGE_ENUM_PARAM));
            int[] pageStructIds = pages == null ? null : pages.getIntVals();
            if (pageStructIds == null) continue;
            for (int pageStructId : pageStructIds)
            {
                StructComposition page = client.getStructComposition(pageStructId);
                EnumComposition items = client.getEnum(page.getIntValue(PAGE_ITEM_ENUM_PARAM));
                int[] itemIds = items == null ? null : items.getIntVals();
                String rawName = page.getStringValue(PAGE_NAME_PARAM);
                String name = rawName == null ? "" : Text.removeTags(rawName).trim();
                if (itemIds != null && itemIds.length > 0 && !name.isEmpty()) loaded.add(new PageDefinition(CATEGORIES[tabIndex], name, itemIds));
            }
        }
        definitions.clear();
        definitions.addAll(loaded);
        return !definitions.isEmpty();
    }

    private List<IronPathDtos.CollectionLogSection> snapshot()
    {
        String capturedAt = Instant.now().toString();
        List<IronPathDtos.CollectionLogSection> sections = new ArrayList<>();
        for (PageDefinition definition : definitions)
        {
            List<IronPathDtos.CollectionLogSlot> slots = new ArrayList<>();
            int obtained = 0;
            int slotOrder = 0;
            Set<Integer> unique = new LinkedHashSet<>();
            for (int itemId : definition.itemIds) if (itemId > 0) unique.add(itemId);
            for (int itemId : unique)
            {
                int quantity = harvest.getOrDefault(itemId, 0);
                boolean unlocked = quantity > 0;
                if (unlocked) obtained++;
                slots.add(new IronPathDtos.CollectionLogSlot(itemId, quantity, unlocked, slotOrder++));
            }
            sections.add(new IronPathDtos.CollectionLogSection(
                key(definition.category, definition.name), definition.category, definition.name,
                obtained, slots.size(), capturedAt, slots));
        }
        return sections;
    }

    private boolean isAnotherPlayersLog()
    {
        return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
    }

    private static void collectItems(Widget widget, Set<Integer> itemIds)
    {
        if (widget.getItemId() > 0) itemIds.add(widget.getItemId());
        collectItems(widget.getDynamicChildren(), itemIds);
        collectItems(widget.getStaticChildren(), itemIds);
        collectItems(widget.getNestedChildren(), itemIds);
    }

    private static void collectItems(Widget[] widgets, Set<Integer> itemIds)
    {
        if (widgets == null) return;
        for (Widget widget : widgets)
        {
            if (widget != null) collectItems(widget, itemIds);
        }
    }

    private int uniqueDefinitionItemCount()
    {
        Set<Integer> ids = new LinkedHashSet<>();
        for (PageDefinition definition : definitions)
        {
            for (int itemId : definition.itemIds) if (itemId > 0) ids.add(itemId);
        }
        return ids.size();
    }

    private static IronPathDtos.CollectionLogProgress findProgress(
        Widget widget, int definitionTotal, IronPathDtos.CollectionLogProgress best)
    {
        if (widget == null) return best;
        String text = Text.removeTags(widget.getText() == null ? "" : widget.getText());
        Matcher matcher = PROGRESS_FRACTION.matcher(text);
        while (matcher.find())
        {
            Integer obtained = parseNumber(matcher.group(1));
            Integer total = parseNumber(matcher.group(2));
            if (validProgress(obtained, total, definitionTotal)
                && (best == null || total > best.totalCount))
            {
                best = new IronPathDtos.CollectionLogProgress(obtained, total);
            }
        }
        best = findProgress(widget.getDynamicChildren(), definitionTotal, best);
        best = findProgress(widget.getStaticChildren(), definitionTotal, best);
        return findProgress(widget.getNestedChildren(), definitionTotal, best);
    }

    private static IronPathDtos.CollectionLogProgress findProgress(
        Widget[] widgets, int definitionTotal, IronPathDtos.CollectionLogProgress best)
    {
        if (widgets == null) return best;
        for (Widget widget : widgets) best = findProgress(widget, definitionTotal, best);
        return best;
    }

    private static Integer widgetNumber(Widget widget)
    {
        if (widget == null || widget.getText() == null) return null;
        Matcher matcher = NUMBER.matcher(Text.removeTags(widget.getText()));
        return matcher.find() ? parseNumber(matcher.group()) : null;
    }

    private static Integer parseNumber(String value)
    {
        try
        {
            return Integer.parseInt(value.replace(",", ""));
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    private static boolean validProgress(Integer obtained, Integer total, int definitionTotal)
    {
        if (obtained == null || total == null || obtained < 0 || total <= 0 || obtained > total) return false;
        if (definitionTotal <= 0) return true;
        int tolerance = Math.max(32, definitionTotal / 20);
        return Math.abs(total - definitionTotal) <= tolerance;
    }

    private static String key(String category, String name)
    {
        return (category + ":" + name).toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static final class PageDefinition
    {
        private final String category;
        private final String name;
        private final int[] itemIds;

        private PageDefinition(String category, String name, int[] itemIds)
        {
            this.category = category;
            this.name = name;
            this.itemIds = itemIds;
        }
    }
}
