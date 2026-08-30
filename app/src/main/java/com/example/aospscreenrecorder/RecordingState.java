package com.example.aospscreenrecorder;

import android.content.Context;
import android.content.Intent;

final class RecordingState {
    static final String ACTION_CHANGED = "com.example.aospscreenrecorder.STATE_CHANGED";
    static final String EXTRA_STATE = "state";

    static final int IDLE = 0;
    static final int RECORDING = 1;
    static final int SAVING = 2;

    private static final String PREFS = "recording_state";
    private static final String KEY_STATE = "state";

    private RecordingState() {}

    static int get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_STATE, IDLE);
    }

    static void set(Context context, int state) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_STATE, state)
                .apply();
        Intent changed = new Intent(ACTION_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_STATE, state);
        context.sendBroadcast(changed);
    }
}
