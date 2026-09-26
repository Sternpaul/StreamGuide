package com.sternpaul.streamguide.core
import org.junit.Assert.assertEquals
import org.junit.Test
class SettingsTest {
 @Test fun epgRefreshDefaultsTo24Hours(){ assertEquals(24, AppSettings.DEFAULT_EPG_HOURS) }
 @Test fun startupRefreshPrefersFullPlaylistUpdateWhenEnabled() {
  assertEquals(StartupRefreshAction.FULL_PLAYLIST, RefreshPolicy.onAppStart(hasProvider=true, playlistOnStart=true))
 }
 @Test fun startupRefreshAlwaysUpdatesEpg() {
  assertEquals(StartupRefreshAction.EPG_ONLY, RefreshPolicy.onAppStart(hasProvider=true, playlistOnStart=false))
 }
 @Test fun startupRefreshDoesNothingWithoutProvider() {
  assertEquals(StartupRefreshAction.NONE, RefreshPolicy.onAppStart(hasProvider=false, playlistOnStart=false))
 }
}
