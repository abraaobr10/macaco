package com.example.aospscreenrecorder;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ScreenRecorder {
    interface Listener {
        void onProjectionStopped();
        void onRecorderInfo(MediaRecorder recorder, int what, int extra);
    }

    private static final int FRAME_RATE = 30;
    private static final int AUDIO_BIT_RATE = 196_000;
    private static final int AUDIO_SAMPLE_RATE = 44_100;
    private static final int MAX_DURATION_MS = 60 * 60 * 1000;
    private static final long MAX_FILE_SIZE = 5_000_000_000L;

    private final Context context;
    private final int resultCode;
    private final Intent resultData;
    private final AudioSource audioSource;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private InternalAudioRecorder internalAudioRecorder;
    private File tempVideo;
    private File tempAudio;
    private boolean stopped;

    ScreenRecorder(
            Context context,
            int resultCode,
            Intent resultData,
            AudioSource audioSource,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.resultCode = resultCode;
        this.resultData = resultData;
        this.audioSource = audioSource;
        this.listener = listener;
    }

    void start() throws IOException {
        MediaProjectionManager manager = context.getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            throw new IOException("MediaProjection permission was not granted");
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                listener.onProjectionStopped();
            }
        }, mainHandler);

        CaptureSize size = getCaptureSize();
        tempVideo = File.createTempFile("screen-video-", ".mp4", context.getCacheDir());
        mediaRecorder = new MediaRecorder();

        if (audioSource == AudioSource.MICROPHONE) {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(size.width, size.height);
        mediaRecorder.setVideoFrameRate(FRAME_RATE);
        mediaRecorder.setVideoEncodingBitRate(size.width * size.height * FRAME_RATE * 6 / 30);
        mediaRecorder.setMaxDuration(MAX_DURATION_MS);
        mediaRecorder.setMaxFileSize(MAX_FILE_SIZE);

        if (audioSource == AudioSource.MICROPHONE) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioChannels(1);
            mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
            mediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE);
        }

        mediaRecorder.setOutputFile(tempVideo.getAbsolutePath());
        mediaRecorder.setOnInfoListener(listener::onRecorderInfo);
        mediaRecorder.prepare();
        surface = mediaRecorder.getSurface();

        virtualDisplay = projection.createVirtualDisplay(
                "AOSP Screen Recorder",
                size.width,
                size.height,
                size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler);

        if (audioSource.usesInternalAudio()) {
            tempAudio = File.createTempFile("screen-audio-", ".mp4", context.getCacheDir());
            internalAudioRecorder = new InternalAudioRecorder(
                    context,
                    tempAudio,
                    projection,
                    audioSource == AudioSource.INTERNAL_AND_MICROPHONE);
        }

        mediaRecorder.start();
        if (internalAudioRecorder != null) {
            internalAudioRecorder.start();
        }
    }

    synchronized void stop() throws IOException {
        if (stopped) {
            return;
        }
        stopped = true;

        IOException failure = null;
        if (internalAudioRecorder != null) {
            try {
                internalAudioRecorder.stop();
            } catch (IOException error) {
                failure = error;
            }
        }

        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException error) {
                failure = new IOException("MediaRecorder stopped too soon or failed", error);
            }
        }
        releaseCaptureResources();
        if (failure != null) {
            throw failure;
        }
    }

    Uri save() throws IOException {
        if (tempVideo == null || !tempVideo.exists() || tempVideo.length() == 0) {
            throw new IOException("No recorded video was produced");
        }

        File source = tempVideo;
        File muxed = null;
        if (audioSource.usesInternalAudio()
                && tempAudio != null
                && tempAudio.exists()
                && tempAudio.length() > 0) {
            muxed = File.createTempFile("screen-final-", ".mp4", context.getCacheDir());
            new Mp4Muxer(muxed, tempVideo, tempAudio).mux();
            source = muxed;
        }

        String fileName = new SimpleDateFormat(
                "'screen-'yyyyMMdd-HHmmss'.mp4'", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Screen recordings");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("MediaStore did not create an output item");
        }

        boolean complete = false;
        try (FileInputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IOException("MediaStore output stream is unavailable");
            }
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            complete = true;
        } finally {
            if (!complete) {
                resolver.delete(uri, null, null);
            }
        }

        ContentValues ready = new ContentValues();
        ready.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, ready, null, null);
        if (muxed != null) {
            muxed.delete();
        }
        return uri;
    }

    void release() {
        releaseCaptureResources();
        if (internalAudioRecorder != null) {
            internalAudioRecorder.release();
            internalAudioRecorder = null;
        }
        if (tempVideo != null) {
            tempVideo.delete();
            tempVideo = null;
        }
        if (tempAudio != null) {
            tempAudio.delete();
            tempAudio = null;
        }
    }

    private void releaseCaptureResources() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    private CaptureSize getCaptureSize() {
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        Rect bounds;
        int density;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            bounds = windowManager.getMaximumWindowMetrics().getBounds();
            density = context.getResources().getConfiguration().densityDpi;
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
            density = metrics.densityDpi;
        }

        int width = bounds.width();
        int height = bounds.height();
        int longest = Math.max(width, height);
        if (longest > 1920) {
            double scale = 1920.0 / longest;
            width = (int) Math.round(width * scale);
            height = (int) Math.round(height * scale);
        }
        width = Math.max(2, width & ~1);
        height = Math.max(2, height & ~1);
        return new CaptureSize(width, height, density);
    }

    private static final class CaptureSize {
        final int width;
        final int height;
        final int densityDpi;

        CaptureSize(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }
}
