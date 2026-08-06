package gg.ironpath;

import java.util.Collections;
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
}
