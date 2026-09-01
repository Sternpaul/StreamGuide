package com.sternpaul.streamguide.core

object ProviderValidation {
    private fun isWebUrl(value: String): Boolean = value.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }

    fun isXtreamValid(server: String, username: String, password: String): Boolean =
        isWebUrl(server) && username.isNotBlank() && password.isNotBlank()

    fun isM3uValid(source: String): Boolean = isWebUrl(source) || source.trim().startsWith("content://", true)
}
