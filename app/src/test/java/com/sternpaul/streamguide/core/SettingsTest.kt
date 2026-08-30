package com.sternpaul.streamguide.core
import org.junit.Assert.assertEquals
import org.junit.Test
class SettingsTest { @Test fun epgRefreshDefaultsTo24Hours(){ assertEquals(24, AppSettings.DEFAULT_EPG_HOURS) } }
