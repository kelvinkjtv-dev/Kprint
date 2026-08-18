package com.kprint.app.data

data class AppConfig(
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val storeId: String = "",
    val deviceId: String = "",
    val deviceToken: String = "",
    val printerAddress: String = "",
    val printerName: String = "",
    val paperWidth: Int = 58,
    val pollingSeconds: Int = 5,
    val autoStart: Boolean = true,
) {
    val isCloudReady: Boolean
        get() = supabaseUrl.startsWith("https://") &&
            supabaseAnonKey.isNotBlank() &&
            storeId.isNotBlank() &&
            deviceId.isNotBlank() &&
            deviceToken.isNotBlank()

    val isPrinterReady: Boolean
        get() = printerAddress.isNotBlank()

    val isReady: Boolean
        get() = isCloudReady && isPrinterReady
}
