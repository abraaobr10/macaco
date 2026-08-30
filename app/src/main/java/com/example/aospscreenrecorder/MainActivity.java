package com.example.aospscreenrecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 100;
    private static final int REQUEST_AUDIO = 101;
    private static final int REQUEST_NOTIFICATIONS = 102;
    private static final int REQUEST_WRITE_SETTINGS = 103;

    private Spinner audioSpinner;
    private Switch showTapsSwitch;
    private TextView statusText;
    private Button recordButton;
    private MediaProjectionManager projectionManager;
    private CountDownTimer countDownTimer;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateUi(intent.getIntExtra(RecordingState.EXTRA_STATE, RecordingState.IDLE));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = getSystemService(MediaProjectionManager.class);
        audioSpinner = findViewById(R.id.audio_spinner);
        showTapsSwitch = findViewById(R.id.show_taps_switch);
        statusText = findViewById(R.id.status_text);
        recordButton = findViewById(R.id.record_button);

        String[] audioLabels = {
                getString(R.string.audio_none),
                getString(R.string.audio_internal),
                getString(R.string.audio_microphone),
                getString(R.string.audio_both)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, audioLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioSpinner.setAdapter(adapter);
        audioSpinner.setSelection(AudioSource.INTERNAL.ordinal());

        recordButton.setOnClickListener(v -> onRecordButtonClicked());
        updateUi(RecordingState.get(this));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RecordingState.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerStateReceiverLegacy(filter);
        }
        receiverRegistered = true;
        updateUi(RecordingState.get(this));
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        super.onDestroy();
    }

    private void onRecordButtonClicked() {
        int state = RecordingState.get(this);
        if (state == RecordingState.RECORDING || state == RecordingState.SAVING) {
            startService(RecordingService.createStopIntent(this));
            return;
        }
        ensurePermissionsAndRequestCapture();
    }

    private void ensurePermissionsAndRequestCapture() {
        AudioSource source = selectedAudioSource();
        if ((source.usesInternalAudio() || source.usesMicrophone())
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }

        if (showTapsSwitch.isChecked() && !Settings.System.canWrite(this)) {
            Toast.makeText(this, R.string.settings_permission_needed, Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(settingsIntent, REQUEST_WRITE_SETTINGS);
            return;
        }

        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WRITE_SETTINGS) {
            if (Settings.System.canWrite(this)) {
                ensurePermissionsAndRequestCapture();
            } else {
                showTapsSwitch.setChecked(false);
                Toast.makeText(this, R.string.settings_permission_needed, Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        beginCountdown(resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ensurePermissionsAndRequestCapture();
        } else {
            Toast.makeText(this, R.string.audio_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void beginCountdown(int resultCode, Intent projectionData) {
        setOptionsEnabled(false);
        countDownTimer = new CountDownTimer(3000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) Math.max(1, (millisUntilFinished + 500) / 1000);
                statusText.setText(getString(R.string.status_countdown, seconds));
            }

            @Override
            public void onFinish() {
                Intent startIntent = RecordingService.createStartIntent(
                        MainActivity.this,
                        resultCode,
                        projectionData,
                        selectedAudioSource(),
                        showTapsSwitch.isChecked());
                startForegroundService(startIntent);
                moveTaskToBack(true);
            }
        }.start();
        recordButton.setEnabled(false);
    }

    private AudioSource selectedAudioSource() {
        return AudioSource.fromOrdinal(audioSpinner.getSelectedItemPosition());
    }

    private void updateUi(int state) {
        boolean active = state != RecordingState.IDLE;
        setOptionsEnabled(!active);
        if (state == RecordingState.RECORDING) {
            statusText.setText(R.string.status_recording);
            statusText.setTextColor(getColor(R.color.primary));
            recordButton.setText(R.string.stop_recording);
            recordButton.setEnabled(true);
        } else if (state == RecordingState.SAVING) {
            statusText.setText(R.string.status_saving);
            statusText.setTextColor(getColor(R.color.text_secondary));
            recordButton.setText(R.string.stop_recording);
            recordButton.setEnabled(false);
        } else {
            statusText.setText(R.string.status_idle);
            statusText.setTextColor(getColor(R.color.text_secondary));
            recordButton.setText(R.string.start_recording);
            recordButton.setEnabled(true);
        }
    }

    private void setOptionsEnabled(boolean enabled) {
        audioSpinner.setEnabled(enabled);
        showTapsSwitch.setEnabled(enabled);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStateReceiverLegacy(IntentFilter filter) {
        // The export flag overload is unavailable before API 33. The broadcast is package-scoped.
        registerReceiver(stateReceiver, filter);
    }
}
