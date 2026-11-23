package org.schabi.newpipe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LeadPipeLaunchNoticeTest {
    @Test
    public void welcomesFreshInstall() {
        assertEquals(LeadPipeLaunchNotice.WELCOME,
                LeadPipeLaunchNotice.forVersions(0, 113001));
    }

    @Test
    public void showsUpgradeForNewerReleaseWithLegacyVersionCode() {
        assertEquals(LeadPipeLaunchNotice.UPGRADE,
                LeadPipeLaunchNotice.forVersions(112904, 113001));
    }

    @Test
    public void skipsSameReleaseAcrossAbisAndOlderReleases() {
        assertEquals(LeadPipeLaunchNotice.NONE,
                LeadPipeLaunchNotice.forVersions(113001, 113001));
        assertEquals(LeadPipeLaunchNotice.NONE,
                LeadPipeLaunchNotice.forVersions(113001, 113004));
        assertEquals(LeadPipeLaunchNotice.NONE,
                LeadPipeLaunchNotice.forVersions(113004, 112901));
    }
}
