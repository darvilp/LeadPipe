package org.schabi.newpipe;

enum LeadPipeLaunchNotice {
    NONE,
    WELCOME,
    UPGRADE;

    static LeadPipeLaunchNotice forVersions(final int lastShown, final int installed) {
        if (lastShown == 0) {
            return WELCOME;
        }
        // APK version codes reserve the last two digits for ABI variants.
        return installed / 100 > lastShown / 100 ? UPGRADE : NONE;
    }
}
