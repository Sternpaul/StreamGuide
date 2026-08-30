package com.sternpaul.streamguide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sternpaul.streamguide.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { GUIDE, SEARCH, SETTINGS, PLAYER, SETUP }
enum class ChannelSort { MANUAL, ALPHABETICAL, PROVIDER }

data class UiState(
    val screen: AppScreen = AppScreen.GUIDE,
    val provider: ProviderConfig? = null,
    val channels: List<Channel> = emptyList(),
    val programs: List<Program> = emptyList(),
    val selectedGroup: String = "All channels",
    val selectedChannelId: String? = null,
    val playingChannelId: String? = null,
    val favoritesOnly: Boolean = false,
    val sort: ChannelSort = ChannelSort.MANUAL,
    val loading: Boolean = false,
    val error: String? = null,
    val status: RefreshStatus = RefreshStatus(),
    val epgHours: Int = AppSettings.DEFAULT_EPG_HOURS,
    val query: String = ""
) {
    val groups: List<String> get() = listOf("All channels", "Favorites") + channels.filterNot { it.hidden }.map { it.group }.distinct().sorted()
    val visibleChannels: List<Channel> get() {
        val filtered = channels.filterNot { it.hidden }.filter {
            when {
                favoritesOnly || selectedGroup == "Favorites" -> it.favorite
                selectedGroup != "All channels" -> it.group == selectedGroup
                else -> true
            }
        }.filter { query.isBlank() || it.name.contains(query, true) || programs.any { p -> (p.channelId == it.tvgId || p.channelId == it.id) && p.title.contains(query, true) } }
        return when (sort) {
            ChannelSort.MANUAL -> filtered.sortedWith(ChannelOrdering.manual)
            ChannelSort.ALPHABETICAL -> filtered.sortedWith(ChannelOrdering.alphabetical)
            ChannelSort.PROVIDER -> filtered.sortedWith(ChannelOrdering.provider)
        }
    }
    val playingChannel: Channel? get() = channels.firstOrNull { it.id == playingChannelId }
}

class MainViewModel(private val app: StreamGuideApp) : ViewModel() {
    private val store = app.container.store
    private val repository = app.container.repository
    var state by mutableStateOf(loadState())
        private set

    init {
        if (state.provider != null && (store.lastRefresh() == 0L || System.currentTimeMillis() - store.lastRefresh() > state.epgHours * 3_600_000L)) refresh()
    }

    private fun loadState(): UiState {
        val provider = store.getProvider(); val channels = store.getChannels(); val programs = store.getPrograms()
        return UiState(
            screen = if (provider == null) AppScreen.SETUP else AppScreen.GUIDE,
            provider = provider, channels = channels, programs = programs,
            selectedChannelId = channels.firstOrNull()?.id,
            epgHours = store.epgHours(),
            status = RefreshStatus(false, store.lastRefresh(), store.lastError().ifBlank { if (store.lastRefresh() > 0) "Guide is up to date" else "Refresh required" }, channels.size, programs.size)
        )
    }

    fun navigate(screen: AppScreen) { state = state.copy(screen = screen, query = if (screen == AppScreen.SEARCH) state.query else "") }
    fun selectGroup(group: String) { state = state.copy(selectedGroup = group, favoritesOnly = group == "Favorites", selectedChannelId = state.channels.firstOrNull { group == "All channels" || (group == "Favorites" && it.favorite) || it.group == group }?.id) }
    fun selectChannel(id: String) { state = state.copy(selectedChannelId = id) }
    fun play(id: String) { state = state.copy(playingChannelId = id, selectedChannelId = id, screen = AppScreen.PLAYER) }
    fun playAdjacent(delta: Int) {
        val list = state.visibleChannels
        if (list.isEmpty()) return
        val current = list.indexOfFirst { it.id == state.playingChannelId }.let { if (it < 0) 0 else it }
        play(list[(current + delta).coerceIn(0, list.lastIndex)].id)
    }
    fun closePlayer() { state = state.copy(screen = AppScreen.GUIDE) }
    fun setQuery(value: String) { state = state.copy(query = value) }
    fun setSort(sort: ChannelSort) { state = state.copy(sort = sort) }
    fun clearError() { state = state.copy(error = null) }

    fun saveProvider(provider: ProviderConfig) {
        store.saveProvider(provider); state = state.copy(provider = provider, screen = AppScreen.GUIDE); app.scheduleEpg(); refresh()
    }

    fun refresh() {
        if (state.loading || state.provider == null) return
        state = state.copy(loading = true, error = null, status = state.status.copy(running = true, message = "Updating playlist and EPG…"))
        viewModelScope.launch {
            runCatching { repository.refreshAll() }
                .onSuccess { status -> state = state.copy(loading = false, channels = store.getChannels(), programs = store.getPrograms(), status = status, selectedChannelId = state.selectedChannelId ?: store.getChannels().firstOrNull()?.id) }
                .onFailure { error -> state = state.copy(loading = false, error = error.message ?: "Refresh failed", status = state.status.copy(running = false, message = error.message ?: "Refresh failed")) }
        }
    }

    fun toggleFavorite(id: String) = mutateChannels { list -> list.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } }
    fun moveChannel(id: String, delta: Int) = mutateChannels { ChannelReconciler.move(it, id, delta) }
    fun hideChannel(id: String) = mutateChannels { list -> list.map { if (it.id == id) it.copy(hidden = true) else it } }
    private fun mutateChannels(change: (List<Channel>) -> List<Channel>) {
        val changed = change(state.channels); state = state.copy(channels = changed); viewModelScope.launch(Dispatchers.IO) { store.saveChannels(changed) }
    }

    fun setEpgHours(hours: Int) { store.setEpgHours(hours); state = state.copy(epgHours = hours); app.scheduleEpg() }
    fun clearProvider() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clearProvider() }
            state = UiState(screen = AppScreen.SETUP)
        }
    }
}

class MainViewModelFactory(private val app: StreamGuideApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
}
