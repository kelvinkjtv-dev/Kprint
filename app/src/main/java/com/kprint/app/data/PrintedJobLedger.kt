package com.kprint.app.data

import android.content.Context

/**
 * Local acknowledgement written immediately after the printer accepts the bytes.
 * It prevents a duplicate receipt if Supabase is temporarily unavailable during acknowledgement.
 */
class PrintedJobLedger(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun contains(jobId: String): Boolean = load().any { it.substringBefore('|') == jobId }

    @Synchronized
    fun markPrinted(jobId: String) {
        val entry = "$jobId|${System.currentTimeMillis()}"
        val updated = (listOf(entry) + load().filterNot { it.substringBefore('|') == jobId })
            .take(MAX_JOB_IDS)
        val pending = pendingAcknowledgements() + jobId
        preferences.edit()
            .putStringSet(KEY_IDS, updated.toSet())
            .putStringSet(KEY_PENDING_ACKS, pending)
            .commit()
    }

    @Synchronized
    fun markAcknowledged(jobId: String) {
        preferences.edit()
            .putStringSet(KEY_PENDING_ACKS, pendingAcknowledgements() - jobId)
            .commit()
    }

    @Synchronized
    fun pendingAcknowledgements(): Set<String> =
        preferences.getStringSet(KEY_PENDING_ACKS, emptySet()).orEmpty().toSet()

    private fun load(): List<String> = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty()
        .sortedByDescending { it.substringAfter('|', "0").toLongOrNull() ?: 0L }

    companion object {
        private const val PREFS = "kprint_ledger"
        private const val KEY_IDS = "printed_job_ids"
        private const val KEY_PENDING_ACKS = "pending_acknowledgements"
        private const val MAX_JOB_IDS = 500
    }
}
