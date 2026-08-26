package gg.ironpath;

import org.junit.Test;

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

}
