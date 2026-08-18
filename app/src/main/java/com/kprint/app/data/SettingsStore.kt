package com.kprint.app.data

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig(
        supabaseUrl = preferences.getString(KEY_URL, "").orEmpty(),
        supabaseAnonKey = preferences.getString(KEY_ANON_KEY, "").orEmpty(),
        storeId = preferences.getString(KEY_STORE_ID, "").orEmpty(),
        deviceId = preferences.getString(KEY_DEVICE_ID, "").orEmpty(),
        deviceToken = SecretCipher.decrypt(preferences.getString(KEY_DEVICE_TOKEN, "").orEmpty()),
        printerAddress = preferences.getString(KEY_PRINTER_ADDRESS, "").orEmpty(),
        printerName = preferences.getString(KEY_PRINTER_NAME, "").orEmpty(),
        paperWidth = preferences.getInt(KEY_PAPER_WIDTH, 58),
        pollingSeconds = preferences.getInt(KEY_POLLING_SECONDS, 5).coerceIn(3, 60),
        autoStart = preferences.getBoolean(KEY_AUTO_START, true),
    )

    fun save(config: AppConfig) {
        preferences.edit()
            .putString(KEY_URL, config.supabaseUrl.trim().trimEnd('/'))
            .putString(KEY_ANON_KEY, config.supabaseAnonKey.trim())
            .putString(KEY_STORE_ID, config.storeId.trim())
            .putString(KEY_DEVICE_ID, config.deviceId.trim())
            .putString(KEY_DEVICE_TOKEN, SecretCipher.encrypt(config.deviceToken.trim()))
            .putString(KEY_PRINTER_ADDRESS, config.printerAddress)
            .putString(KEY_PRINTER_NAME, config.printerName)
            .putInt(KEY_PAPER_WIDTH, config.paperWidth)
            .putInt(KEY_POLLING_SECONDS, config.pollingSeconds.coerceIn(3, 60))
            .putBoolean(KEY_AUTO_START, config.autoStart)
            .apply()
    }

    companion object {
        private const val PREFS = "kprint_settings"
        private const val KEY_URL = "supabase_url"
        private const val KEY_ANON_KEY = "supabase_anon_key"
        private const val KEY_STORE_ID = "store_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PRINTER_ADDRESS = "printer_address"
        private const val KEY_PRINTER_NAME = "printer_name"
        private const val KEY_PAPER_WIDTH = "paper_width"
        private const val KEY_POLLING_SECONDS = "polling_seconds"
        private const val KEY_AUTO_START = "auto_start"
    }
}
