package dev.partykit.r0usis.festasync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

// Mantém a festa tocando com o app em segundo plano ou a tela desligada. Sem um serviço
// "em primeiro plano" (o da notificação fixa, igual app de música), o Android congela o app
// alguns segundos depois que ele sai da tela e a música/voz param. Só roda enquanto a
// pessoa está dentro de uma sala (o site avisa via FestaAndroid.setInRoom).
public class PlaybackService extends Service {
    static final String CHANNEL = "festa";
    static final int NOTIF_ID = 1;
    static final String EXTRA_ROOM = "room";

    PowerManager.WakeLock wakeLock;
    WifiManager.WifiLock wifiLock;

    static void start(Context ctx, String room) {
        Intent i = new Intent(ctx, PlaybackService.class).putExtra(EXTRA_ROOM, room);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, PlaybackService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String room = intent != null ? intent.getStringExtra(EXTRA_ROOM) : null;
        if (Build.VERSION.SDK_INT >= 29) {
            // 2 = mediaPlayback; 128 = microphone — sem esse último, no Android 14+ o
            // microfone do chat de voz fica mudo com o app em segundo plano. Só dá pra pedir
            // se a permissão de microfone já foi dada (o app chama start() de novo quando for).
            int types = 2;
            if (Build.VERSION.SDK_INT >= 30
                    && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) types |= 128;
            startForeground(NOTIF_ID, buildNotification(room), types);
        } else {
            startForeground(NOTIF_ID, buildNotification(room));
        }
        // CPU e Wi-Fi acordados com a tela apagada — senão o áudio engasga e a conexão
        // com a sala cai quando o celular "dorme"
        if (wakeLock == null) {
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FestaSync:tocando");
            wakeLock.acquire();
        }
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FestaSync:tocando");
                wifiLock.acquire();
            }
        }
        return START_NOT_STICKY;
    }

    Notification buildNotification(String room) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Festa tocando", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aparece enquanto você está numa sala, pra música continuar com o app fechado");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent quit = PendingIntent.getActivity(this, 1,
            new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_QUIT).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(room != null && !room.isEmpty() ? "Na festa: " + room : "Festa Sync")
            .setContentText("Tocando em segundo plano")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Sair da festa", quit);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    // app fechado arrastando dos recentes: a festa acaba junto
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
