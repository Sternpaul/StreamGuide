package com.sternpaul.streamguide.core

import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    @Test fun comparesNumericVersionsRatherThanText() {
        assertTrue(AppUpdatePolicy.isNewer("v0.7.10", "0.7.3"))
        assertTrue(AppUpdatePolicy.isNewer("1.0.0", "0.99.99"))
        assertFalse(AppUpdatePolicy.isNewer("0.7.3", "0.7.3"))
        assertFalse(AppUpdatePolicy.isNewer("0.7.2", "0.7.3"))
        assertFalse(AppUpdatePolicy.isNewer("0.6.99", "0.7.3"))
    }
    @Test fun rejectsMalformedAndPrereleaseVersions() {
        for (value in listOf("latest", "0.7.4-beta", "0.7", "99999999999.1.1", "-1.0.0")) {
            assertFalse(AppUpdatePolicy.isNewer(value, "0.7.3"))
        }
    }
    @Test fun restrictsInstallersToTheOfficialReleaseAsset() {
        assertTrue(AppUpdatePolicy.isTrustedDownload("https://github.com/Sternpaul/StreamGuide/releases/download/v0.7.4/StreamGuide-firetv.apk"))
        assertFalse(AppUpdatePolicy.isTrustedDownload("http://github.com/Sternpaul/StreamGuide/releases/download/v0.7.4/StreamGuide-firetv.apk"))
        assertFalse(AppUpdatePolicy.isTrustedDownload("https://github.com/other/StreamGuide/releases/download/v0.7.4/StreamGuide-firetv.apk"))
        assertFalse(AppUpdatePolicy.isTrustedDownload("https://github.com.evil.test/Sternpaul/StreamGuide/releases/download/v0.7.4/StreamGuide-firetv.apk"))
        assertFalse(AppUpdatePolicy.isTrustedDownload("https://github.com/Sternpaul/StreamGuide/releases/download/v0.7.4/other.apk"))
    }
}
