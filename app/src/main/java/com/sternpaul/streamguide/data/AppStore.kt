package com.sternpaul.streamguide.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sternpaul.streamguide.core.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class AppStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("streamguide", Context.MODE_PRIVATE)
    private val masterKey by lazy { MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val secure by lazy {
        EncryptedSharedPreferences.create(
            context, "streamguide_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val channelsFile = File(context.filesDir, "channels.json")
    private val programsFile = File(context.filesDir, "programs.json")

    fun getProvider(): ProviderConfig? = secure.getString("provider", null)?.let(::providerFromJson)
    fun saveProvider(provider: ProviderConfig) { secure.edit().putString("provider", providerToJson(provider).toString()).apply() }
    fun clearProvider() { secure.edit().remove("provider").apply(); prefs.edit().remove("group_order").apply(); channelsFile.delete(); programsFile.delete() }

    fun getChannels(): List<Channel> = readModels(channelsFile, ::channelFromJson)
    fun saveChannels(channels: List<Channel>) = JsonLinesStore.write(channelsFile, channels.asSequence()) { channelToJson(it).toString() }
    fun getPrograms(): List<Program> = readModels(programsFile, ::programFromJson)
    fun savePrograms(programs: List<Program>) = JsonLinesStore.write(programsFile, programs.asSequence()) { programToJson(it).toString() }
    fun openContentUri(uri: String) = context.contentResolver.openInputStream(android.net.Uri.parse(uri)) ?: error("Cannot open selected playlist file")

    fun epgHours(): Int = prefs.getInt("epg_hours", AppSettings.DEFAULT_EPG_HOURS).takeIf { it in AppSettings.allowedEpgHours } ?: AppSettings.DEFAULT_EPG_HOURS
    fun setEpgHours(hours: Int) { require(hours in AppSettings.allowedEpgHours); prefs.edit().putInt("epg_hours", hours).apply() }
    fun timelineHours(): Int = prefs.getInt("timeline_hours", AppSettings.DEFAULT_TIMELINE_HOURS).takeIf { it in AppSettings.allowedTimelineHours } ?: AppSettings.DEFAULT_TIMELINE_HOURS
    fun setTimelineHours(hours: Int) { require(hours in AppSettings.allowedTimelineHours); prefs.edit().putInt("timeline_hours", hours).apply() }
    fun epgAutoUpdate(): Boolean = prefs.getBoolean("epg_auto_update", true)
    fun setEpgAutoUpdate(enabled: Boolean) { prefs.edit().putBoolean("epg_auto_update", enabled).apply() }
    fun updateEpgOnStart(): Boolean = prefs.getBoolean("epg_update_on_start", true)
    fun setUpdateEpgOnStart(enabled: Boolean) { prefs.edit().putBoolean("epg_update_on_start", enabled).apply() }
    fun updatePlaylistOnStart(): Boolean = prefs.getBoolean("playlist_update_on_start", false)
    fun setUpdatePlaylistOnStart(enabled: Boolean) { prefs.edit().putBoolean("playlist_update_on_start", enabled).apply() }
    fun groupOrder(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("group_order", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
    fun saveGroupOrder(groups: List<String>) { prefs.edit().putString("group_order", JSONArray(groups.distinct()).toString()).apply() }
    fun lastRefresh(): Long = prefs.getLong("last_refresh", 0L)
    fun setLastRefresh(value: Long) { prefs.edit().putLong("last_refresh", value).apply() }
    fun lastError(): String = prefs.getString("last_error", "").orEmpty()
    fun setLastError(value: String) { prefs.edit().putString("last_error", value).apply() }
    fun recentChannelIds(): List<String> = prefs.getString("recent_channels", "").orEmpty().split('|').filter { it.isNotBlank() }
    fun saveRecentChannelIds(ids: List<String>) { prefs.edit().putString("recent_channels", ids.take(30).joinToString("|")).apply() }
    fun multiviewChannelIds(): List<String> = prefs.getString("multiview_channels", "").orEmpty().split('|').filter { it.isNotBlank() }
    fun saveMultiviewChannelIds(ids: List<String>) { prefs.edit().putString("multiview_channels", ids.take(4).joinToString("|")).apply() }

    fun hasParentalPin(): Boolean = secure.contains("pin_hash") && secure.contains("pin_salt")
    fun setParentalPin(pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        secure.edit()
            .putString("pin_salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString("pin_hash", android.util.Base64.encodeToString(PinHasher.hash(pin, salt), android.util.Base64.NO_WRAP))
            .apply()
    }
    fun verifyParentalPin(pin: String): Boolean = runCatching {
        val salt = android.util.Base64.decode(secure.getString("pin_salt", ""), android.util.Base64.NO_WRAP)
        val hash = android.util.Base64.decode(secure.getString("pin_hash", ""), android.util.Base64.NO_WRAP)
        PinHasher.verify(pin, salt, hash)
    }.getOrDefault(false)

    private fun <T : Any> readModels(file: File, transform: (JSONObject) -> T?): List<T> = runCatching {
        if (!file.exists()) return emptyList()
        val first = file.bufferedReader().use { reader ->
            generateSequence { reader.read() }.map(Int::toChar).firstOrNull { !it.isWhitespace() }
        }
        if (first == '[') {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { transform(array.getJSONObject(it)) }
        } else {
            JsonLinesStore.read(file) { line -> runCatching { transform(JSONObject(line)) }.getOrNull() }
        }
    }.getOrElse { emptyList() }

    private fun providerToJson(p: ProviderConfig) = JSONObject().put("type", p.type.name).put("name", p.name).put("playlistUrl", p.playlistUrl).put("serverUrl", p.serverUrl).put("username", p.username).put("password", p.password).put("epgUrl", p.epgUrl).put("userAgent",p.userAgent).put("referer",p.referer)
    private fun providerFromJson(raw: String): ProviderConfig? = runCatching { JSONObject(raw).let { ProviderConfig(ProviderType.valueOf(it.getString("type")), it.optString("name", "Playlist"), it.optString("playlistUrl"), it.optString("serverUrl"), it.optString("username"), it.optString("password"), it.optString("epgUrl"), it.optString("userAgent"), it.optString("referer")) } }.getOrNull()
    private fun channelToJson(c: Channel) = JSONObject().put("id",c.id).put("name",c.name).put("url",c.url).put("group",c.group).put("tvgId",c.tvgId).put("logo",c.logoUrl).put("providerOrder",c.providerOrder).put("manualRank",c.manualRank ?: JSONObject.NULL).put("favorite",c.favorite).put("hidden",c.hidden).put("locked",c.locked).put("catchupSource",c.catchupSource).put("catchupDays",c.catchupDays).put("customName",c.customName).put("customGroup",c.customGroup)
    private fun channelFromJson(o: JSONObject): Channel? = runCatching { Channel(o.getString("id"),o.getString("name"),o.getString("url"),o.optString("group","Other"),o.optString("tvgId"),o.optString("logo"),o.optInt("providerOrder"),if(o.isNull("manualRank")) null else o.getLong("manualRank"),o.optBoolean("favorite"),o.optBoolean("hidden"),o.optBoolean("locked"),o.optString("catchupSource"),o.optInt("catchupDays"),o.optString("customName"),o.optString("customGroup")) }.getOrNull()
    private fun programToJson(p: Program) = JSONObject().put("channelId",p.channelId).put("title",p.title).put("description",p.description).put("start",p.startEpochMs).put("end",p.endEpochMs)
    private fun programFromJson(o: JSONObject): Program? = runCatching { Program(o.getString("channelId"),o.getString("title"),o.optString("description"),o.getLong("start"),o.getLong("end")) }.getOrNull()
}
