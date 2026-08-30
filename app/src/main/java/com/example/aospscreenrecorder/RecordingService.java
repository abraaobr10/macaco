package com.example.aospscreenrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecordingService extends Service implements ScreenRecorder.Listener {
    private static final String TAG = "RecordingService";
    private static final String CHANNEL_ID = "screen_recording";
    private static final int NOTIFICATION_ID = 4273;
    private static final int SAVED_NOTIFICATION_ID = 4274;

    private static final String ACTION_START =
            "com.example.aospscreenrecorder.action.START";
    private static final String ACTION_STOP =
            "com.example.aospscreenrecorder.action.STOP";
    private static final String ACTION_SHARE =
            "com.example.aospscreenrecorder.action.SHARE";

    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";
    private static final String EXTRA_AUDIO_SOURCE = "audio_source";
    private static final String EXTRA_SHOW_TAPS = "show_taps";
    private static final String EXTRA_URI = "uri";

    private final AtomicBoolean stopping = new AtomicBoolean();
    private ExecutorService executor;
    private NotificationManager notificationManager;
    private ScreenRecorder recorder;
    private AudioSource audioSource = AudioSource.NONE;
    private boolean changedShowTaps;
    private int originalShowTaps;

    static Intent createStartIntent(
            Context context,
            int resultCode,
            Intent resultData,
            AudioSource audioSource,
            boolean showTaps) {
        return new Intent(context, RecordingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_AUDIO_SOURCE, audioSource.ordinal())
                .putExtra(EXTRA_SHOW_TAPS, showTaps);
    }

    static Intent createStopIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_STOP);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_recording_text));
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_START:
                startFromIntent(intent);
                break;
            case ACTION_STOP:
                stopAndSave();
                break;
            case ACTION_SHARE:
                Uri uri;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    uri = intent.getParcelableExtra(EXTRA_URI, Uri.class);
                } else {
                    uri = intent.getParcelableExtra(EXTRA_URI);
                }
                share(uri);
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void startFromIntent(Intent intent) {
        if (recorder != null || RecordingState.get(this) != RecordingState.IDLE) {
            return;
        }

        audioSource = AudioSource.fromOrdinal(
                intent.getIntExtra(EXTRA_AUDIO_SOURCE, AudioSource.NONE.ordinal()));
        startForegroundCompat(createRecordingNotification(true));

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            failRecording(new IllegalArgumentException("Missing MediaProjection result data"));
            return;
        }

        applyShowTaps(intent.getBooleanExtra(EXTRA_SHOW_TAPS, false));
        try {
            recorder = new ScreenRecorder(this, resultCode, resultData, audioSource, this);
            recorder.start();
            RecordingState.set(this, RecordingState.RECORDING);
            notificationManager.notify(NOTIFICATION_ID, createRecordingNotification(false));
        } catch (Exception error) {
            failRecording(error);
        }
    }

    private void startForegroundCompat(Notification notification) {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && audioSource.usesMicrophone()) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        startForeground(NOTIFICATION_ID, notification, type);
    }

    private Notification createRecordingNotification(boolean preparing) {
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                createStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop),
                stopIntent).build();
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                2,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_recording_title))
                .setContentText(getString(preparing
                        ? R.string.notification_preparing
                        : R.string.notification_recording_text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(stopAction)
                .build();
    }

    private Notification createSavingNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_recording_title))
                .setContentText(getString(R.string.notification_saving))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private Notification createSavedNotification(Uri uri) {
        Intent viewIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent viewPendingIntent = PendingIntent.getActivity(
                this,
                3,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent shareIntent = new Intent(this, RecordingService.class)
                .setAction(ACTION_SHARE)
                .putExtra(EXTRA_URI, uri);
        PendingIntent sharePendingIntent = PendingIntent.getService(
                this,
                4,
                shareIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action shareAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_share,
                getString(R.string.action_share),
                sharePendingIntent).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_saved_title))
                .setContentText(getString(R.string.notification_saved_text))
                .setContentIntent(viewPendingIntent)
                .setAutoCancel(true)
                .addAction(shareAction)
                .build();
    }

    private Notification createErrorNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_error_title))
                .setContentText(getString(R.string.notification_error_text))
                .setAutoCancel(true)
                .build();
    }

    private void stopAndSave() {
        if (recorder == null || !stopping.compareAndSet(false, true)) {
            return;
        }
        RecordingState.set(this, RecordingState.SAVING);
        notificationManager.notify(NOTIFICATION_ID, createSavingNotification());

        ScreenRecorder activeRecorder = recorder;
        recorder = null;
        executor.execute(() -> {
            try {
                activeRecorder.stop();
                Uri uri = activeRecorder.save();
                notificationManager.notify(SAVED_NOTIFICATION_ID, createSavedNotification(uri));
                runOnMainThread(() -> Toast.makeText(
                        this, R.string.saved_success, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                Log.e(TAG, "Unable to stop or save recording", error);
                notificationManager.notify(SAVED_NOTIFICATION_ID, createErrorNotification());
            } finally {
                activeRecorder.release();
                finishService();
            }
        });
    }

    private void failRecording(Exception error) {
        Log.e(TAG, "Unable to start recording", error);
        if (recorder != null) {
            ScreenRecorder failedRecorder = recorder;
            recorder = null;
            failedRecorder.release();
        }
        Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_LONG).show();
        notificationManager.notify(SAVED_NOTIFICATION_ID, createErrorNotification());
        finishService();
    }

    private void finishService() {
        restoreShowTaps();
        RecordingState.set(this, RecordingState.IDLE);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void applyShowTaps(boolean requested) {
        if (!requested || !Settings.System.canWrite(this)) {
            return;
        }
        try {
            originalShowTaps = Settings.System.getInt(
                    getContentResolver(), "show_touches", 0);
            changedShowTaps = Settings.System.putInt(
                    getContentResolver(), "show_touches", 1);
        } catch (SecurityException error) {
            Log.w(TAG, "Device does not allow changing show_touches", error);
        }
    }

    private void restoreShowTaps() {
        if (!changedShowTaps || !Settings.System.canWrite(this)) {
            return;
        }
        try {
            Settings.System.putInt(
                    getContentResolver(), "show_touches", originalShowTaps);
        } catch (SecurityException error) {
            Log.w(TAG, "Unable to restore show_touches", error);
        }
        changedShowTaps = false;
    }

    private void share(Uri uri) {
        if (uri == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, getString(R.string.share_video))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(chooser);
        stopSelf();
    }

    private void runOnMainThread(Runnable runnable) {
        getMainExecutor().execute(runnable);
    }

    @Override
    public void onProjectionStopped() {
        stopAndSave();
    }

    @Override
    public void onRecorderInfo(MediaRecorder recorder, int what, int extra) {
        stopAndSave();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        restoreShowTaps();
        RecordingState.set(this, RecordingState.IDLE);
        if (executor != null) {
            executor.shutdown();
        }
        super.onDestroy();
    }
}
