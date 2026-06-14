package com.qralarm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that:
 *  1. Acquires a CPU WakeLock so the device stays awake.
 *  2. Launches AlarmRingActivity over the lock screen.
 *  3. Plays a 3-stage escalating ringtone from res/raw/.
 *
 * Stage durations (each STAGE_DURATION_MS = 120 s):
 *   Stage 0 → calm.mp3   @ MIN_VOL  → MAX_VOL  (0–2 min)
 *   Stage 1 → medium.mp3 @ MIN_VOL  → MAX_VOL  (2–4 min)
 *   Stage 2 → loud.mp3   @ MIN_VOL  → MAX_VOL  (4–6 min)
 *   After stage 2 → service stops (auto-dismiss).
 *
 * Volume is raised in 1-second ticks via a Handler; no vibration.
 */
public class AlarmService extends Service {

    private static final String TAG = "AlarmService";

    public static final String EXTRA_ALARM_ID = "extra_alarm_id";
    public static final String CHANNEL_ID     = "qralarm_channel";
    public static final int    NOTIFICATION_ID = 42;

    private static final long  STAGE_DURATION_MS = 120_000L; // 2 min per stage
    private static final float MIN_VOL           = 0.07f;
    private static final float MAX_VOL           = 1.00f;
    private static final int[] STAGE_RAW         = {
        R.raw.calm, R.raw.medium, R.raw.loud
    };

    // Guard: prevents duplicate concurrent alarm sessions.
    private static volatile boolean sRinging    = false;
    private static volatile int     sRingingId  = -1;

    private final Handler   handler  = new Handler(Looper.getMainLooper());
    private final ExecutorService db = Executors.newSingleThreadExecutor();

    private MediaPlayer      player;
    private PowerManager.WakeLock wakeLock;

    private int  currentAlarmId = -1;
    private int  stage          = 0;   // 0,1,2
    private long stageStart     = 0;

    private final Runnable volumeTick = new Runnable() {
        @Override public void run() {
            long elapsed = System.currentTimeMillis() - stageStart;
            if (elapsed >= STAGE_DURATION_MS) {
                advanceStage();
                return;
            }
            float vol = MIN_VOL + (MAX_VOL - MIN_VOL) * ((float) elapsed / STAGE_DURATION_MS);
            setVolume(vol);
            handler.postDelayed(this, 1_000L);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);
        if (alarmId == -1) { stopSelf(); return START_NOT_STICKY; }

        // Deduplicate: ignore if same alarm is already ringing.
        if (sRinging && sRingingId == alarmId) return START_STICKY;
        if (sRinging) return START_STICKY; // another alarm already active

        sRinging   = true;
        sRingingId = alarmId;
        currentAlarmId = alarmId;
        stage      = 0;

        acquireWakeLock();
        startForeground(NOTIFICATION_ID, buildNotification());
        launchRingScreen();
        db.execute(this::startFirstStage);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(volumeTick);
        releasePlayer();
        releaseWakeLock();
        sRinging   = false;
        sRingingId = -1;
        db.shutdown();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ── Stage logic ───────────────────────────────────────────────────────────

    /** Called from background thread (DB lookup already done). */
    private void startFirstStage() {
        handler.post(() -> playStage(0));
    }

    private void playStage(int s) {
        if (s >= STAGE_RAW.length) { stopSelf(); return; }
        stage      = s;
        stageStart = System.currentTimeMillis();

        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    .build());
            player.setDataSource(getResources().openRawResourceFd(STAGE_RAW[s]));
            player.setLooping(true);
            player.prepare();
            setVolume(MIN_VOL);
            player.start();
            handler.postDelayed(volumeTick, 1_000L);
            Log.d(TAG, "Stage " + s + " started");
        } catch (IOException e) {
            Log.e(TAG, "MediaPlayer failed at stage " + s, e);
            advanceStage();
        }
    }

    private void advanceStage() {
        handler.removeCallbacks(volumeTick);
        playStage(stage + 1);
    }

    private void setVolume(float v) {
        if (player != null) {
            try { player.setVolume(v, v); }
            catch (IllegalStateException ignored) {}
        }
    }

    // ── Lock-screen / full-screen intent ──────────────────────────────────────

    private void launchRingScreen() {
        Intent i = new Intent(this, AlarmRingActivity.class);
        i.putExtra(AlarmRingActivity.EXTRA_ALARM_ID, currentAlarmId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                 | Intent.FLAG_ACTIVITY_CLEAR_TOP
                 | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QRAlarm::WakeLock");
            wakeLock.acquire(7 * 60 * 1_000L); // 7 min safety ceiling
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── MediaPlayer cleanup ───────────────────────────────────────────────────

    private void releasePlayer() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (IllegalStateException ignored) {}
            player.release();
            player = null;
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent tap  = new Intent(this, AlarmRingActivity.class);
        tap.putExtra(AlarmRingActivity.EXTRA_ALARM_ID, currentAlarmId);
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(getString(R.string.alarm_ringing))
                .setContentText(getString(R.string.scan_qr_to_dismiss))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.alarm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setBypassDnd(true);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ── Static helpers used by AlarmRingActivity ─────────────────────────────

    public static boolean isAlarmRinging(int alarmId) {
        return sRinging && sRingingId == alarmId;
    }

    public static void stop(android.content.Context ctx) {
        ctx.stopService(new Intent(ctx, AlarmService.class));
    }
}
