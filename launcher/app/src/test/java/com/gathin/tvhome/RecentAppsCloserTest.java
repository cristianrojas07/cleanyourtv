package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class RecentAppsCloserTest {
    private static final String SELF = "com.gathin.tvhome";

    @Test public void aNonTclLiveTvAppAndAnInputProviderAreKeptAndOnlyTheOrdinaryAppIsClosable() {
        Set<String> keep = RecentAppsCloser.keep(SELF, "com.mediatek.wwtv.tvcenter",
                Collections.singletonList("com.mediatek.tvinput"));
        List<String> recent = Arrays.asList("com.mediatek.wwtv.tvcenter", "com.netflix.ninja",
                "com.mediatek.tvinput", SELF, "com.android.tv.settings");
        assertEquals(Collections.singletonList("com.netflix.ninja"), RecentAppsCloser.closable(recent, keep));
    }

    @Test public void appsComeLatestFirstAndOnceEach() {
        Set<String> keep = RecentAppsCloser.keep(SELF, "", Collections.emptyList());
        List<String> recent = Arrays.asList("com.google.android.youtube.tv", "com.netflix.ninja",
                "com.google.android.youtube.tv");
        assertEquals(Arrays.asList("com.google.android.youtube.tv", "com.netflix.ninja"),
                RecentAppsCloser.closable(recent, keep));
    }

    @Test public void theStorePlayServicesAndThisLauncherAreAlwaysKept() {
        Set<String> keep = RecentAppsCloser.keep(SELF, null, Collections.emptyList());
        assertTrue(keep.contains("com.android.vending"));
        assertTrue(keep.contains("com.google.android.gms"));
        assertTrue(keep.contains(SELF));
        assertFalse(keep.contains(null));
    }
}
