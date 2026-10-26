package dev.partykit.r0usis.festasync

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews

// Widgets da tela inicial:
//  - "Sala favorita": código da sala + Entrar. Abre o app e já entra (com o nome e a senha
//    guardados no celular). A sala é a fixada pela pessoa (folha da sala → "Fixar no widget")
//    ou, se nunca fixou, a última em que entrou.
//  - "Tocando agora": a música da sala, com tocar/pausar e passar música. Usa os mesmos
//    controles da notificação (PlaybackService.controls) — só funciona com a pessoa numa sala;
//    fora dela mostra "Fora da festa" e um toque abre o app.

/** sala do widget de entrada rápida (fica nas preferências do app, só no celular) */
data class FavoriteRoom(val room: String, val password: String)

object FavoriteRooms {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("festaSync", Context.MODE_PRIVATE)

    /** a fixada; se não tiver, a última sala em que entrou */
    fun get(ctx: Context): FavoriteRoom? {
        val p = prefs(ctx)
        val room = p.getString("favSala", null) ?: p.getString("ultimaSala", null) ?: return null
        val pass = if (p.contains("favSala")) p.getString("favSenha", "") else p.getString("ultimaSenha", "")
        return FavoriteRoom(room, pass ?: "")
    }

    fun pinnedRoom(ctx: Context): String? = prefs(ctx).getString("favSala", null)

    fun pin(ctx: Context, room: String, password: String) {
        prefs(ctx).edit().putString("favSala", room).putString("favSenha", password).apply()
        QuickJoinWidget.refresh(ctx)
    }

    fun unpin(ctx: Context) {
        prefs(ctx).edit().remove("favSala").remove("favSenha").apply()
        QuickJoinWidget.refresh(ctx)
    }

    /** entrou numa sala: vira a "última" (usada quando nenhuma está fixada) */
    fun rememberLast(ctx: Context, room: String, password: String) {
        prefs(ctx).edit().putString("ultimaSala", room).putString("ultimaSenha", password).apply()
        QuickJoinWidget.refresh(ctx)
    }
}

class QuickJoinWidget : AppWidgetProvider() {
    companion object {
        const val ACTION_QUICK_JOIN = "dev.partykit.r0usis.festasync.QUICK_JOIN"

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, QuickJoinWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick_join)
            val fav = FavoriteRooms.get(ctx)
            val open = PendingIntent.getActivity(
                ctx, 10,
                Intent(ctx, MainActivity::class.java).setAction(ACTION_QUICK_JOIN)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (fav == null) {
                v.setTextViewText(R.id.qj_room, "Nenhuma")
                v.setTextViewText(R.id.qj_hint, "Entre numa sala pelo app que ela aparece aqui")
                v.setTextViewText(R.id.qj_button, "Abrir o app")
            } else {
                v.setTextViewText(R.id.qj_room, fav.room)
                v.setTextViewText(R.id.qj_hint, if (FavoriteRooms.pinnedRoom(ctx) != null) "Fixada por você" else "Última sala em que você entrou")
                v.setTextViewText(R.id.qj_button, "Entrar")
            }
            v.setOnClickPendingIntent(R.id.widget_root, open)
            v.setOnClickPendingIntent(R.id.qj_button, open)
            return v
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, views(ctx))
    }
}

class NowPlayingWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION_PLAY_PAUSE = "dev.partykit.r0usis.festasync.widget.PLAY_PAUSE"
        private const val ACTION_NEXT = "dev.partykit.r0usis.festasync.widget.NEXT"

        // o último estado mostrado (pra quando o widget é adicionado no meio da festa)
        private var last: NowPlaying? = null
        private var lastArt: Bitmap? = null

        /** chamado pelo PlaybackService: mudou a música / play / pause; null = saiu da festa */
        fun update(ctx: Context, info: NowPlaying?, art: Bitmap?) {
            last = info
            // miniatura pequena: o widget vai por IPC e tem limite de tamanho
            lastArt = art?.let { Bitmap.createScaledBitmap(it, 160, (160f * it.height / it.width).toInt().coerceAtLeast(1), true) }
            push(ctx)
        }

        private fun push(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NowPlayingWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx))
        }

        private fun broadcast(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, NowPlayingWidget::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_now_playing)
            val openApp = PendingIntent.getActivity(
                ctx, 20,
                Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, openApp)
            val info = last
            if (info == null || PlaybackService.controls == null) {
                v.setTextViewText(R.id.np_label, "FESTA SYNC")
                v.setTextViewText(R.id.np_title, "Fora da festa")
                v.setTextViewText(R.id.np_sub, "Toque pra abrir o app")
                v.setViewVisibility(R.id.np_playpause, View.GONE)
                v.setViewVisibility(R.id.np_next, View.GONE)
                v.setViewVisibility(R.id.np_thumb, View.GONE)
                return v
            }
            v.setTextViewText(R.id.np_label, if (info.isPlaying) "TOCANDO AGORA" else "PAUSADA")
            v.setTextViewText(R.id.np_title, info.title ?: "Nada tocando")
            v.setTextViewText(R.id.np_sub, "Sala ${info.room}")
            v.setViewVisibility(R.id.np_playpause, View.VISIBLE)
            v.setImageViewResource(R.id.np_playpause, if (info.isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
            v.setOnClickPendingIntent(R.id.np_playpause, broadcast(ctx, ACTION_PLAY_PAUSE, 21))
            v.setViewVisibility(R.id.np_next, if (info.hasNext) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.np_next, broadcast(ctx, ACTION_NEXT, 22))
            // modo trabalho: sem capa (igual a notificação)
            val art = lastArt.takeIf { !info.discreet }
            if (art != null) { v.setImageViewBitmap(R.id.np_thumb, art); v.setViewVisibility(R.id.np_thumb, View.VISIBLE) }
            else v.setViewVisibility(R.id.np_thumb, View.GONE)
            return v
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, views(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val controls = PlaybackService.controls
        when (intent.action) {
            ACTION_PLAY_PAUSE -> controls?.playPause()
            ACTION_NEXT -> controls?.next()
            else -> return
        }
        // app fechado pelo sistema no meio do caminho: volta pro "Fora da festa"
        if (controls == null) { last = null; push(ctx) }
    }
}
