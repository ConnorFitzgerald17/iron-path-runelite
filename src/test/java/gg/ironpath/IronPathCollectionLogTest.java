package gg.ironpath;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class IronPathCollectionLogTest
{
    @Test
    public void readsAuthoritativeProgressFromTheFullLogTitle()
    {
        IronPathDtos.CollectionLogProgress progress = IronPathCollectionLog.parseProgressText(
            "Collection Log - 460/1712", 1716);

        assertEquals(460, progress.obtainedCount);
        assertEquals(1712, progress.totalCount);
    }

    @Test
    public void ignoresUnrelatedProgressFractions()
    {
        assertNull(IronPathCollectionLog.parseProgressText("Abyssal Sire - 3/9", 1716));
    }

    @Test
    public void usesApprovedCollectionLogSearchAction()
    {
        AtomicReference<Object[]> menuAction = new AtomicReference<>();
        AtomicReference<Object[]> scriptArguments = new AtomicReference<>();
        Client client = (Client) Proxy.newProxyInstance(
            Client.class.getClassLoader(),
            new Class<?>[]{Client.class},
            (proxy, method, arguments) ->
            {
                if (method.getName().equals("menuAction")) menuAction.set(arguments);
                if (method.getName().equals("runScript")) scriptArguments.set((Object[]) arguments[0]);
                return null;
            });

        IronPathCollectionLog.triggerCollectionLogSearch(client);

        assertArrayEquals(new Object[]{
            -1,
            InterfaceID.Collection.SEARCH_TOGGLE,
            MenuAction.CC_OP,
            1,
            -1,
            "Search",
            null
        }, menuAction.get());
        assertArrayEquals(new Object[]{ScriptID.COLLECTION_DRAW_LIST}, scriptArguments.get());
    }
}
