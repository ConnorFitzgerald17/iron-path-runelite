package gg.ironpath;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IronPathPanelTest
{
    @Test
    public void keepsPrimaryActionsFullWidthAndLeftAligned() throws Exception
    {
        AtomicReference<IronPathPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            IronPathPanel panel = new IronPathPanel();
            panel.setSize(225, 900);
            layoutTree(panel);
            reference.set(panel);
        });

        JButton connect = findButton(reference.get(), "Connect");
        JButton sync = findButton(reference.get(), "Sync now");
        JButton collection = findButton(reference.get(), "Sync full log");
        assertNotNull(connect);
        assertNotNull(sync);
        assertNotNull(collection);
        assertTrue(connect.getWidth() >= 190);
        assertEquals(absoluteX(connect), absoluteX(sync));
        assertEquals(absoluteX(connect), absoluteX(collection));
    }

    @Test
    public void hidesOptionalSidebarSections() throws Exception
    {
        AtomicReference<IronPathPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new IronPathPanel()));
        reference.get().setDisplayPreferences(true, true, true, false, false, false);
        SwingUtilities.invokeAndWait(() -> {});

        JLabel collections = findLabel(reference.get(), "RECENT COLLECTIONS LOGGED");
        JLabel kills = findLabel(reference.get(), "RECENT KILLS");
        JLabel goals = findLabel(reference.get(), "ACTIVE GOALS");
        assertNotNull(collections);
        assertNotNull(kills);
        assertNotNull(goals);
        assertFalse(collections.getParent().isVisible());
        assertFalse(kills.getParent().isVisible());
        assertFalse(goals.getParent().isVisible());
    }

    @Test
    public void explainsOverviewCaptureAndFullLogSync() throws Exception
    {
        AtomicReference<IronPathPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new IronPathPanel()));

        assertNotNull(findLabelContaining(reference.get(), "Totals and recent items capture automatically"));

        reference.get().setCollectionOverviewProgress(new IronPathDtos.CollectionLogProgress(460, 1712));
        SwingUtilities.invokeAndWait(() -> {});
        assertNotNull(findLabelContaining(reference.get(), "Overview captured: 460 / 1,712"));
        assertNotNull(findLabelContaining(reference.get(), "Open the full Collection Log item list"));

        reference.get().setCollectionLogNeedsFullLog();
        SwingUtilities.invokeAndWait(() -> {});
        assertNotNull(findLabelContaining(reference.get(), "The full Collection Log is not open"));
        assertNotNull(findLabelContaining(reference.get(), "Your overview is saved"));
    }

    private static void layoutTree(Container container)
    {
        container.doLayout();
        for (Component component : container.getComponents())
        {
            if (component instanceof Container) layoutTree((Container) component);
        }
    }

    private static JButton findButton(Container container, String text)
    {
        for (Component component : container.getComponents())
        {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) return (JButton) component;
            if (component instanceof Container)
            {
                JButton found = findButton((Container) component, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel findLabel(Container container, String text)
    {
        for (Component component : container.getComponents())
        {
            if (component instanceof JLabel && text.equals(((JLabel) component).getText())) return (JLabel) component;
            if (component instanceof Container)
            {
                JLabel found = findLabel((Container) component, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel findLabelContaining(Container container, String text)
    {
        for (Component component : container.getComponents())
        {
            if (component instanceof JLabel && ((JLabel) component).getText().contains(text)) return (JLabel) component;
            if (component instanceof Container)
            {
                JLabel found = findLabelContaining((Container) component, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int absoluteX(Component component)
    {
        int x = component.getX();
        Container parent = component.getParent();
        while (parent != null)
        {
            x += parent.getX();
            parent = parent.getParent();
        }
        return x;
    }
}
