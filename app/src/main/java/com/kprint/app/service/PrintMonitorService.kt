package com.kprint.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kprint.app.MainActivity
import com.kprint.app.R
import com.kprint.app.data.AppConfig
import com.kprint.app.data.EventLogStore
import com.kprint.app.data.EventType
import com.kprint.app.data.PrintedJobLedger
import com.kprint.app.data.SettingsStore
import com.kprint.app.data.SupabaseQueueClient
import com.kprint.app.printing.BluetoothPrinter
import com.kprint.app.printing.EscPosFormatter
import com.kprint.app.printing.demoOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PrintMonitorService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private lateinit var settings: SettingsStore
    private lateinit var events: EventLogStore
    private lateinit var ledger: PrintedJobLedger
    private lateinit var printer: BluetoothPrinter
    private val queueClient = SupabaseQueueClient()
    private var printedThisSession = 0
    private var lastLoggedError = ""
    private var lastErrorAt = 0L

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        events = EventLogStore(this)
        ledger = PrintedJobLedger(this)
        printer = BluetoothPrinter(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                MonitorPreference.setEnabled(this, false)
                stopMonitor()
                return START_NOT_STICKY
            }
            ACTION_TEST_PRINT -> {
                startAsForeground("Imprimindo teste…")
                executor.execute { runTestPrint() }
                return START_NOT_STICKY
            }
            else -> {
                MonitorPreference.setEnabled(this, true)
                startAsForeground("Iniciando monitor…")
                startMonitorLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        executor.shutdownNow()
        MonitorRuntime.update(MonitorStatus.STOPPED, "Monitor parado")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitorLoop() {
        if (!running.compareAndSet(false, true)) return
        printedThisSession = 0
        MonitorRuntime.update(MonitorStatus.STARTING, "Conectando à fila de pedidos…", 0)
        executor.execute {
            while (running.get()) {
                val config = settings.load()
                if (!config.isReady) {
                    setStatus(MonitorStatus.ERROR, "Conclua a configuração do Supabase e da impressora")
                    sleep(15_000)
                    continue
                }

                try {
                    confirmPendingAcknowledgements(config)
                    val jobs = queueClient.claimJobs(config)
                    if (jobs.isEmpty()) {
                        setStatus(MonitorStatus.LISTENING, "Aguardando novos pedidos")
                    }
                    jobs.forEach { job ->
                        if (!running.get()) return@forEach
                        if (ledger.contains(job.id)) {
                            setStatus(MonitorStatus.LISTENING, "Confirmando pedido #${job.payload.orderNumber}")
                            completePrintedWithRetry(config, job.id)
                            ledger.markAcknowledged(job.id)
                            return@forEach
                        }

                        setStatus(MonitorStatus.PRINTING, "Imprimindo pedido #${job.payload.orderNumber}")
                        try {
                            val bytes = EscPosFormatter.format(job.payload, config.paperWidth)
                            printer.print(config.printerAddress, bytes)
                            // Persist before the remote ACK: a network failure must never print a second copy.
                            ledger.markPrinted(job.id)
                            printedThisSession += 1
                            events.add(
                                title = "Pedido #${job.payload.orderNumber}",
                                detail = "Impresso em ${config.printerName.ifBlank { config.printerAddress }}",
                                type = EventType.PRINTED,
                                id = job.id,
                            )
                        } catch (error: Exception) {
                            val message = friendlyError(error)
                            runCatching {
                                queueClient.completeJob(config, job.id, printed = false, error = message)
                            }
                            logError("Falha no pedido #${job.payload.orderNumber}", message)
                            setStatus(MonitorStatus.ERROR, message)
                            return@forEach
                        }

                        try {
                            completePrintedWithRetry(config, job.id)
                            ledger.markAcknowledged(job.id)
                            setStatus(MonitorStatus.LISTENING, "Pedido #${job.payload.orderNumber} impresso")
                        } catch (_: Exception) {
                            val message = "Cupom impresso; confirmação pendente por falta de conexão"
                            events.add("Confirmação pendente", "Pedido #${job.payload.orderNumber}", EventType.INFO)
                            setStatus(MonitorStatus.ERROR, message)
                        }
                    }
                    sleep(config.pollingSeconds * 1_000L)
                } catch (error: Exception) {
                    val message = friendlyError(error)
                    logError("Falha na conexão", message)
                    setStatus(MonitorStatus.ERROR, message)
                    sleep(15_000)
                }
            }
        }
    }

    private fun confirmPendingAcknowledgements(config: AppConfig) {
        ledger.pendingAcknowledgements().forEach { jobId ->
            completePrintedWithRetry(config, jobId)
            ledger.markAcknowledged(jobId)
        }
    }

    private fun completePrintedWithRetry(config: AppConfig, jobId: String) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                if (queueClient.completeJob(config, jobId, printed = true)) return
            } catch (error: Exception) {
                lastError = error
            }
            if (attempt < 2) sleep(2_000)
        }
        throw lastError ?: IllegalStateException("Supabase não confirmou a impressão")
    }

    private fun runTestPrint() {
        val config = settings.load()
        if (!config.isPrinterReady) {
            logError("Teste não impresso", "Selecione uma impressora nas configurações")
            setStatus(MonitorStatus.ERROR, "Selecione uma impressora")
            stopSelf()
            return
        }
        try {
            printer.print(config.printerAddress, EscPosFormatter.format(demoOrder(), config.paperWidth))
            events.add("Impressão de teste", "Conexão Bluetooth funcionando", EventType.PRINTED)
            MonitorRuntime.update(MonitorStatus.STOPPED, "Teste impresso com sucesso")
        } catch (error: Exception) {
            val message = friendlyError(error)
            logError("Teste não impresso", message)
            MonitorRuntime.update(MonitorStatus.ERROR, message)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun setStatus(status: MonitorStatus, message: String) {
        MonitorRuntime.update(status, message, printedThisSession)
        updateNotification(message)
    }

    private fun logError(title: String, detail: String) {
        val now = System.currentTimeMillis()
        if (detail != lastLoggedError || now - lastErrorAt > 60_000) {
            events.add(title, detail, EventType.ERROR)
            lastLoggedError = detail
            lastErrorAt = now
        }
    }

    private fun friendlyError(error: Exception): String = when (error) {
        is SecurityException -> "Permita o acesso ao Bluetooth para continuar"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Erro inesperado ao imprimir"
    }

    private fun startAsForeground(message: String) {
        startForeground(NOTIFICATION_ID, notification(message))
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_print)
        .setContentTitle("KPrint está ativo")
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "Parar",
            PendingIntent.getService(
                this,
                1,
                Intent(this, PrintMonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopMonitor() {
        running.set(false)
        MonitorRuntime.update(MonitorStatus.STOPPED, "Monitor parado")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sleep(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val CHANNEL_ID = "kprint_monitor"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_START = "com.kprint.app.START"
        private const val ACTION_STOP = "com.kprint.app.STOP"
        private const val ACTION_TEST_PRINT = "com.kprint.app.TEST_PRINT"

        fun start(context: Context) {
            MonitorPreference.setEnabled(context, true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, PrintMonitorService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            MonitorPreference.setEnabled(context, false)
            context.startService(Intent(context, PrintMonitorService::class.java).setAction(ACTION_STOP))
        }

        fun printTest(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PrintMonitorService::class.java).setAction(ACTION_TEST_PRINT),
            )
        }
    }
}
