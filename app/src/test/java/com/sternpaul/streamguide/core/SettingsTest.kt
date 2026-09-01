package com.sternpaul.streamguide.core
import org.junit.Assert.assertEquals
import org.junit.Test
class SettingsTest {
 @Test fun epgRefreshDefaultsTo24Hours(){ assertEquals(24, AppSettings.DEFAULT_EPG_HOURS) }
 @Test fun startupRefreshPrefersFullPlaylistUpdateWhenEnabled() {
  assertEquals(StartupRefreshAction.FULL_PLAYLIST, RefreshPolicy.onAppStart(hasProvider=true, playlistOnStart=true, epgOnStart=true, epgIsStale=true))
 }
 @Test fun startupRefreshCanUpdateOnlyStaleEpg() {
  assertEquals(StartupRefreshAction.EPG_ONLY, RefreshPolicy.onAppStart(hasProvider=true, playlistOnStart=false, epgOnStart=true, epgIsStale=true))
 }
 @Test fun startupRefreshDoesNothingWhenUpdatesAreDisabled() {
  assertEquals(StartupRefreshAction.NONE, RefreshPolicy.onAppStart(hasProvider=true, playlistOnStart=false, epgOnStart=false, epgIsStale=true))
 }
}
