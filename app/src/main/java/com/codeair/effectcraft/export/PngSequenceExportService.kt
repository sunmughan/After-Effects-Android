package com.codeair.effectcraft.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import com.codeair.effectcraft.MainActivity
import com.codeair.effectcraft.data.EcprojDocumentStore
import com.codeair.effectcraft.data.ForegroundPngSequenceExport
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import com.codeair.effectcraft.data.SafDocumentWriter
import com.codeair.effectcraft.engine.NativeSession
import com.codeair.effectcraft.engine.RenderedFramePng
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for the PNG sequence ZIP export only.
 *
 * The export runs on a dedicated worker thread, never the UI thread, and
 * posts a low-importance notification with live progress and a Cancel
 * action. Cancel is cooperative: the flag is checked before every frame
 * render and inside the destination copy loop, and a cancelled export
 * deletes its staging file and marker and rolls back the destination,
 * so no partial ZIP is left as a finished export.
 *
 * Interruption, stated plainly: this service is START_NOT_STICKY on
 * purpose. If the process is killed, Android does not restart the
 * export, render progress is lost, and the export must be restarted by
 * the user from frame 0. What the kill leaves behind is detectable:
 * the staging ZIP plus its `.exporting` marker in cacheDir, which
 * startup recovery cleans up (see [ForegroundPngSequenceExport.recover]).
 * The foreground service only lowers the chance of a kill while the
 * export is visible; it does not make the export resumable, and no
 * resume is claimed. A kill during the final SAF copy can also leave a
 * provider-side partial document that this process cannot roll back;
 * that case belongs to the separate M5 provider-copy line.
 *
 * No codec or audio work happens here: the only output is the PNG
 * sequence ZIP produced by [ForegroundPngSequenceExport].
 *
 * Notification note: on API 33 and later the notification is subject to
 * the POST_NOTIFICATIONS runtime grant; progress is still tracked in
 * [status] either way, and the emulator CI target (API 30) shows the
 * notification without that grant.
 */
class PngSequenceExportService : Service() {
    data class Status(
        val phase: ForegroundPngSequenceExport.Phase,
        val completedFrames: Int,
        val frameCount: Int,
        val percent: Int,
        val message: String,
        val finished: Boolean,
        val succeeded: Boolean,
        val copiedBytes: Long = 0L,
        val totalBytes: Long = 0L
    )

    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                val destination = intent.getStringExtra(EXTRA_DESTINATION)?.let(Uri::parse)
                val compositionId = intent.getLongExtra(EXTRA_COMPOSITION_ID, -1L)
                // This service exports PNG sequences only, so the format is
                // explicit here: the app-wide default is MP4 H.264.
                val settings = RenderQueueSettings(
                    width = intent.getIntExtra(EXTRA_WIDTH, 0),
                    height = intent.getIntExtra(EXTRA_HEIGHT, 0),
                    frameRate = intent.getIntExtra(EXTRA_FPS, 0),
                    startFrame = intent.getIntExtra(EXTRA_START_FRAME, 0),
                    endFrame = intent.getIntExtra(EXTRA_END_FRAME, -1),
                    outputFormat = RenderOutputFormat.PNG_SEQUENCE_ZIP
                )
                val compositionFrames = intent.getIntExtra(EXTRA_COMPOSITION_FRAMES, 0)
                if (destination == null || compositionId < 0L) {
                    updateStatus(ForegroundPngSequenceExport.Phase.FAILED, 0, 0, 0,
                        "PNG sequence export failed: missing destination or composition", finished = true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                isRunning = true
                cancelled.set(false)
                updateStatus(ForegroundPngSequenceExport.Phase.RENDERING, 0, 0, 0,
                    "Rendering PNG sequence: 0%", finished = false)
                createChannel()
                startForeground(NOTIFICATION_ID, buildNotification(0, 0, "Rendering PNG sequence: 0%"))
                val thread = Thread({ runExport(destination, compositionId, settings, compositionFrames) },
                    "png-sequence-export")
                worker = thread
                thread.start()
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }

    private fun runExport(
        destination: Uri,
        compositionId: Long,
        settings: RenderQueueSettings,
        compositionFrames: Int
    ) {
        var session: NativeSession? = null
        try {
            val source = EcprojDocumentStore(this).load()
                ?: error("No canonical project is cached for export")
            val plan = RenderQueuePlan.create(settings, compositionFrames)
            updateStatus(ForegroundPngSequenceExport.Phase.RENDERING, 0, plan.frameCount, 0,
                "Rendering PNG sequence: 0%", finished = false)
            val nativeRenderSide = minOf(
                maxOf(settings.width, settings.height),
                RenderedFramePng.MAX_NATIVE_RENDER_SIDE
            )
            session = NativeSession.open(source, nativeRenderSide)
                ?: error("Native renderer unavailable for this project")
            val result = ForegroundPngSequenceExport.run(
                plan = plan,
                stagingDir = cacheDir,
                renderPng = { frame ->
                    if (cancelled.get()) throw CancellationException("PNG sequence export cancelled")
                    val rgba = session?.render(compositionId, frame.toLong())
                        ?: error("Native renderer returned no frame at $frame")
                    ByteArrayOutputStream().use { pngOutput ->
                        RenderedFramePng.writeScaled(rgba, pngOutput, settings.width, settings.height)
                        pngOutput.toByteArray()
                    }
                },
                copyToDestination = { staging, ensureActive, onBytesCopied ->
                    SafDocumentWriter.copyFileToDocument(
                        context = this,
                        sourceFile = staging,
                        destination = destination,
                        ensureActive = {
                            if (cancelled.get()) throw CancellationException("PNG sequence export cancelled")
                            ensureActive()
                        },
                        onBytesCopied = onBytesCopied
                    )
                },
                rollbackDestination = {
                    runCatching {
                        DocumentsContract.deleteDocument(contentResolver, destination)
                    }.recoverCatching {
                        contentResolver.delete(destination, null, null)
                    }
                },
                isCancelled = { cancelled.get() },
                onProgress = { progress ->
                    val message = when (progress.phase) {
                        ForegroundPngSequenceExport.Phase.RENDERING ->
                            "Rendering PNG sequence: ${progress.completedFrames}/${plan.frameCount} (${progress.percent}%)"
                        ForegroundPngSequenceExport.Phase.VALIDATING ->
                            "Validating completed PNG sequence"
                        ForegroundPngSequenceExport.Phase.COPYING ->
                            "Copying ZIP to destination: ${progress.percent}% (${progress.completedBytes}/${progress.totalBytes} bytes)"
                        ForegroundPngSequenceExport.Phase.DONE ->
                            "Exported ${plan.frameCount} PNG frames: ${settings.width}x${settings.height} @ ${settings.frameRate} FPS"
                        ForegroundPngSequenceExport.Phase.CANCELLED ->
                            "PNG sequence export cancelled"
                        ForegroundPngSequenceExport.Phase.FAILED ->
                            "PNG sequence export failed"
                    }
                    updateStatus(
                        progress.phase, progress.completedFrames, plan.frameCount,
                        progress.percent, message, finished = false,
                        copiedBytes = progress.completedBytes, totalBytes = progress.totalBytes
                    )
                    when (progress.phase) {
                        ForegroundPngSequenceExport.Phase.RENDERING ->
                            updateNotification(progress.completedFrames, plan.frameCount, message)
                        ForegroundPngSequenceExport.Phase.COPYING ->
                            updateNotification(progress.percent, 100, message)
                        else -> updateNotification(plan.frameCount, plan.frameCount, message)
                    }
                }
            )
            updateStatus(ForegroundPngSequenceExport.Phase.DONE, result.framesValidated,
                plan.frameCount, 100,
                "Exported ${plan.frameCount} PNG frames: ${settings.width}x${settings.height} @ ${settings.frameRate} FPS",
                finished = true, succeeded = true)
        } catch (cancelledError: CancellationException) {
            updateStatus(ForegroundPngSequenceExport.Phase.CANCELLED, 0, 0, 0,
                "PNG sequence export cancelled", finished = true)
        } catch (failure: Throwable) {
            updateStatus(ForegroundPngSequenceExport.Phase.FAILED, 0, 0, 0,
                "PNG sequence export failed: ${failure.message ?: "render or write error"}",
                finished = true)
        } finally {
            session?.close()
            isRunning = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    private fun updateStatus(
        phase: ForegroundPngSequenceExport.Phase,
        completedFrames: Int,
        frameCount: Int,
        percent: Int,
        message: String,
        finished: Boolean,
        succeeded: Boolean = false,
        copiedBytes: Long = 0L,
        totalBytes: Long = 0L
    ) {
        status = Status(phase, completedFrames, frameCount, percent, message, finished, succeeded, copiedBytes, totalBytes)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(completed: Int, total: Int, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("EffectCraft export")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), completed, false)
            .setContentIntent(contentPendingIntent())
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelPendingIntent())
            .build()

    private fun updateNotification(completed: Int, total: Int, text: String) {
        createChannel()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(completed, total, text)) }
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, PngSequenceExportService::class.java).apply { action = ACTION_CANCEL }
        return PendingIntent.getService(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_START = "com.codeair.effectcraft.export.ACTION_START"
        const val ACTION_CANCEL = "com.codeair.effectcraft.export.ACTION_CANCEL"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_COMPOSITION_ID = "compositionId"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_START_FRAME = "startFrame"
        const val EXTRA_END_FRAME = "endFrame"
        const val EXTRA_COMPOSITION_FRAMES = "compositionFrames"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "effectcraft-export"
        private const val CHANNEL_NAME = "EffectCraft exports"

        @Volatile
        var status: Status? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(
            context: Context,
            destination: Uri,
            compositionId: Long,
            settings: RenderQueueSettings,
            compositionFrames: Int
        ): Intent = Intent(context, PngSequenceExportService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_DESTINATION, destination.toString())
            putExtra(EXTRA_COMPOSITION_ID, compositionId)
            putExtra(EXTRA_WIDTH, settings.width)
            putExtra(EXTRA_HEIGHT, settings.height)
            putExtra(EXTRA_FPS, settings.frameRate)
            putExtra(EXTRA_START_FRAME, settings.startFrame)
            putExtra(EXTRA_END_FRAME, settings.endFrame)
            putExtra(EXTRA_COMPOSITION_FRAMES, compositionFrames)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, PngSequenceExportService::class.java).apply { action = ACTION_CANCEL }
    }
}
