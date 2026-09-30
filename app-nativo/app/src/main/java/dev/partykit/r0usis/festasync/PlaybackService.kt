package dev.partykit.r0usis.festasync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** o que o serviço precisa saber pra montar a notificação / a tela de bloqueio */
data class NowPlaying(
    val room: String,
    val title: String?,
    val artist: String?,
    val thumb: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    /** modo trabalho: sem capa do vídeo na notificação / tela de bloqueio */
    val discreet: Boolean = false,
)

/** o que os botões da notificação / da tela de bloqueio fazem (quem implementa é o PartyViewModel) */
interface PartyControls {
    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun quitParty()
}

// Mantém a festa viva com o app em segundo plano ou a tela apagada. Sem um serviço "em
// primeiro plano" (o da notificação fixa, igual app de música), o Android congela o app
// uns segundos depois que ele sai da tela — a música, a sincronização e a voz param.
// Só roda enquanto a pessoa está dentro de uma sala. Também é quem cuida dos controles de
// música da notificação e da tela de bloqueio (MediaSession).
class PlaybackService : Service() {
    companion object {
        private const val CHANNEL = "festa"
        private const val NOTIF_ID = 1
        private const val ACTION_PLAY_PAUSE = "festa.PLAY_PAUSE"
        private const val ACTION_NEXT = "festa.NEXT"
        private const val ACTION_PREVIOUS = "festa.PREVIOUS"
        private const val ACTION_QUIT = "festa.QUIT"

        @Volatile var controls: PartyControls? = null
        private var instance: PlaybackService? = null
        private var pending: NowPlaying? = null
        private var wantMic = false

        /** entrou numa sala (chamar com o app na tela — o Android não deixa começar isso do fundo) */
        fun start(ctx: Context, info: NowPlaying) {
            pending = info
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        }

        /** mudou a música / play / pause: atualiza a notificação (não recomeça o serviço) */
        fun update(info: NowPlaying) {
            pending = info
            instance?.render(info)
        }

        /** ligou/desligou o microfone: o Android 14+ exige avisar que o serviço usa o mic,
         *  senão a voz fica muda com o app no fundo */
        fun setMicActive(active: Boolean) {
            wantMic = active
            instance?.let { svc -> pending?.let { svc.promote(it) } }
        }

        fun stop(ctx: Context) {
            pending = null
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val http = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()
    private var artUrl: String? = null
    private var art: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSession(this, "FestaSync").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { controls?.playPause() }
                override fun onPause() { controls?.playPause() }
                override fun onSkipToNext() { controls?.next() }
                override fun onSkipToPrevious() { controls?.previous() }
                override fun onSeekTo(pos: Long) { controls?.seekTo(pos) }
                override fun onStop() { controls?.quitParty() }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        // CPU e Wi-Fi acordados com a tela apagada — senão a música engasga e a conexão com a
        // sala cai quando o celular "dorme"
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FestaSync:festa").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FestaSync:festa").apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> controls?.playPause()
            ACTION_NEXT -> controls?.next()
            ACTION_PREVIOUS -> controls?.previous()
            ACTION_QUIT -> { controls?.quitParty(); return START_NOT_STICKY }
        }
        val info = pending
        if (info == null) { stopSelf(); return START_NOT_STICKY }
        promote(info)
        return START_NOT_STICKY
    }

    /** (re)declara o serviço em primeiro plano com os tipos certos — mídia sempre, microfone
     *  só enquanto o mic está ligado (e com a permissão dada, senão o Android recusa) */
    fun promote(info: NowPlaying) {
        val notif = buildNotification(info)
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (wantMic && Build.VERSION.SDK_INT >= 30 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try { startForeground(NOTIF_ID, notif, types) }
            catch (e: Exception) { startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        } else {
            startForeground(NOTIF_ID, notif)
        }
        updateSession(info)
    }

    fun render(info: NowPlaying) {
        updateSession(info)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(info))
    }

    private fun updateSession(info: NowPlaying) {
        loadArt(info.thumb)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, info.title ?: "Festa Sync")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, info.artist?.takeIf { it.isNotBlank() } ?: "Na festa: ${info.room}")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Sala ${info.room}")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, info.durationMs)
                .apply { if (!info.discreet) art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        var actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP
        if (info.durationMs > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (info.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (info.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (info.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, info.positionMs, if (info.isPlaying) 1f else 0f)
                .build()
        )
    }

    // capa da música (miniatura do YouTube) na notificação e na tela de bloqueio
    private fun loadArt(url: String?) {
        if (url == null || url == artUrl) return
        artUrl = url
        art = null
        thread(isDaemon = true) {
            val bmp = try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            } catch (e: Exception) { null }
            main.post {
                if (artUrl == url && bmp != null) {
                    art = bmp
                    pending?.let { render(it) }
                }
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(info: NowPlaying): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Festa tocando", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aparece enquanto você está numa sala, pra música e a voz continuarem com o app fechado"
                    setShowBadge(false)
                }
            )
        }
        val prev = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Anterior", actionIntent(ACTION_PREVIOUS, 1)).build()
        val playPause = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, if (info.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (info.isPlaying) "Pausar" else "Tocar", actionIntent(ACTION_PLAY_PAUSE, 2),
        ).build()
        val next = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next), "Próxima", actionIntent(ACTION_NEXT, 3)).build()
        val quit = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Sair da festa", actionIntent(ACTION_QUIT, 4)).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_festa)
            .setContentTitle(info.title ?: "Na festa: ${info.room}")
            .setContentText(if (info.title != null) "Sala ${info.room}" else "Nenhuma música tocando ainda")
            .apply { if (!info.discreet) art?.let { setLargeIcon(it) } }
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(prev).addAction(playPause).addAction(next).addAction(quit)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    // app fechado arrastando dos recentes: a festa acaba junto
    override fun onTaskRemoved(rootIntent: Intent?) {
        controls?.quitParty()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        session.isActive = false
        session.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
