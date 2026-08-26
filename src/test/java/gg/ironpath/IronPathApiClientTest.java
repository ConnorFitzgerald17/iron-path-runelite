package gg.ironpath;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IronPathApiClientTest
{
    @Test
    public void acceptsAndNormalizesHttpsOrigins()
    {
        assertEquals("https://www.ironpathosrs.com",
            IronPathApiClient.normalizeApiOrigin(" https://www.ironpathosrs.com/// "));
        assertEquals("https://example.com/base",
            IronPathApiClient.normalizeApiOrigin("https://example.com/base/"));
    }

    @Test
    public void rejectsOriginsThatCouldExposePlayerDataOrTokens()
    {
        assertNull(IronPathApiClient.normalizeApiOrigin("http://localhost:3000"));
        assertNull(IronPathApiClient.normalizeApiOrigin("https://user@example.com"));
        assertNull(IronPathApiClient.normalizeApiOrigin("https://example.com?target=other"));
        assertNull(IronPathApiClient.normalizeApiOrigin("not a URL"));
    }

    @Test
    public void bindsTokensToTheirIssuingOrigin()
    {
        assertTrue(IronPathPlugin.tokenOriginMatches(IronPathConfig.DEFAULT_API_ORIGIN, null));
        assertFalse(IronPathPlugin.tokenOriginMatches("https://example.com", null));
        assertTrue(IronPathPlugin.tokenOriginMatches("https://example.com/", "https://example.com"));
        assertFalse(IronPathPlugin.tokenOriginMatches("https://other.example.com", "https://example.com"));
        assertFalse(IronPathPlugin.tokenOriginMatches("http://example.com", "http://example.com"));
    }
}
