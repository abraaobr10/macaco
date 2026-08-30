package com.example.aospscreenrecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

final class InternalAudioRecorder {
    private static final String TAG = "InternalAudioRecorder";
    private static final int SAMPLE_RATE = 44_100;
    private static final int BIT_RATE = 196_000;
    private static final int CHANNEL_COUNT = 1;
    private static final float MIC_GAIN = 1.4f;
    private static final long CODEC_TIMEOUT_US = 10_000;

    private final File outputFile;
    private final Context context;
    private final MediaProjection projection;
    private final boolean includeMicrophone;
    private final AtomicReference<Exception> failure = new AtomicReference<>();

    private AudioRecord playbackRecord;
    private AudioRecord microphoneRecord;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private Thread worker;
    private volatile boolean running;
    private boolean started;
    private boolean muxerStarted;
    private int trackIndex = -1;
    private long totalSamples;

    InternalAudioRecorder(
            Context context,
            File outputFile,
            MediaProjection projection,
            boolean includeMicrophone) {
        this.context = context.getApplicationContext();
        this.outputFile = outputFile;
        this.projection = projection;
        this.includeMicrophone = includeMicrophone;
    }

    synchronized void start() throws IOException {
        if (started) {
            throw new IllegalStateException("Audio recorder is single-use");
        }
        started = true;
        setup();

        codec.start();
        playbackRecord.startRecording();
        if (microphoneRecord != null) {
            microphoneRecord.startRecording();
        }
        if (playbackRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IOException("Internal audio capture did not start");
        }

        running = true;
        worker = new Thread(this::captureLoop, "ScreenAudioCapture");
        worker.start();
    }

    private void setup() throws IOException {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("RECORD_AUDIO permission is required");
        }
        int minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBuffer <= 0) {
            throw new IOException("Unsupported audio capture configuration");
        }
        int bufferSize = Math.max(minimumBuffer * 4, 64 * 1024);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        AudioPlaybackCaptureConfiguration captureConfiguration =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        playbackRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfiguration)
                .build();

        if (includeMicrophone) {
            microphoneRecord = new AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build();
        }

        MediaFormat codecFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT);
        codecFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        codecFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        codecFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        muxer = new MediaMuxer(
                outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void captureLoop() {
        short[] playback = new short[8192];
        short[] microphone = includeMicrophone ? new short[8192] : null;
        byte[] pcm = new byte[playback.length * 2];
        try {
            while (running) {
                int playbackCount = playbackRecord.read(
                        playback, 0, playback.length, AudioRecord.READ_BLOCKING);
                if (playbackCount <= 0) {
                    if (running) {
                        throw new IOException("Internal audio read failed: " + playbackCount);
                    }
                    break;
                }

                int sampleCount = playbackCount;
                if (microphoneRecord != null) {
                    int microphoneCount = microphoneRecord.read(
                            microphone, 0, microphone.length, AudioRecord.READ_BLOCKING);
                    if (microphoneCount <= 0) {
                        if (running) {
                            throw new IOException("Microphone read failed: " + microphoneCount);
                        }
                        break;
                    }
                    sampleCount = Math.min(playbackCount, microphoneCount);
                    mix(playback, microphone, sampleCount);
                }

                shortsToBytes(playback, pcm, sampleCount);
                queuePcm(pcm, sampleCount * 2);
                drainCodec(false);
            }
            queueEndOfStream();
            drainCodec(true);
        } catch (Exception error) {
            failure.compareAndSet(null, error);
            Log.e(TAG, "Audio capture failed", error);
        } finally {
            finishCodecAndMuxer();
        }
    }

    private void mix(short[] playback, short[] microphone, int count) {
        for (int i = 0; i < count; i++) {
            int mic = Math.round(microphone[i] * MIC_GAIN);
            int mixed = playback[i] + mic;
            playback[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
        }
    }

    private void shortsToBytes(short[] input, byte[] output, int count) {
        ByteBuffer.wrap(output)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(input, 0, count);
    }

    private void queuePcm(byte[] pcm, int byteCount) throws IOException {
        int offset = 0;
        while (offset < byteCount && running) {
            int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputIndex < 0) {
                drainCodec(false);
                continue;
            }
            ByteBuffer input = codec.getInputBuffer(inputIndex);
            if (input == null) {
                throw new IOException("AAC encoder input buffer is unavailable");
            }
            input.clear();
            int chunk = Math.min(input.remaining(), byteCount - offset);
            input.put(pcm, offset, chunk);
            long presentationTimeUs = totalSamples * 1_000_000L / SAMPLE_RATE;
            codec.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs, 0);
            totalSamples += chunk / 2L;
            offset += chunk;
        }
    }

    private void queueEndOfStream() {
        for (int attempts = 0; attempts < 100; attempts++) {
            int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputIndex >= 0) {
                long presentationTimeUs = totalSamples * 1_000_000L / SAMPLE_RATE;
                codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return;
            }
            drainCodec(false);
        }
    }

    private void drainCodec(boolean waitForEnd) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int emptyPolls = 0;
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(
                    info, waitForEnd ? CODEC_TIMEOUT_US : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd || ++emptyPolls >= 100) {
                    return;
                }
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer output = codec.getOutputBuffer(outputIndex);
            if (output != null
                    && muxerStarted
                    && info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                output.position(info.offset);
                output.limit(info.offset + info.size);
                muxer.writeSampleData(trackIndex, output, info);
            }
            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(outputIndex, false);
            if (end) {
                return;
            }
        }
    }

    synchronized void stop() throws IOException {
        if (!started) {
            return;
        }
        running = false;
        safeStop(playbackRecord);
        safeStop(microphoneRecord);
        if (worker != null) {
            try {
                worker.join(5000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping audio", error);
            }
            if (worker.isAlive()) {
                throw new IOException("Timed out while stopping audio capture");
            }
        }
        Exception audioFailure = failure.get();
        if (audioFailure != null) {
            throw new IOException("Internal audio capture failed", audioFailure);
        }
    }

    void release() {
        running = false;
        safeStop(playbackRecord);
        safeStop(microphoneRecord);
        safeRelease(playbackRecord);
        safeRelease(microphoneRecord);
        playbackRecord = null;
        microphoneRecord = null;
    }

    private void finishCodecAndMuxer() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception error) {
                Log.w(TAG, "Unable to stop AAC encoder", error);
            }
            try {
                codec.release();
            } catch (Exception error) {
                Log.w(TAG, "Unable to release AAC encoder", error);
            }
        }
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (Exception error) {
                Log.w(TAG, "Unable to stop audio muxer", error);
            }
            try {
                muxer.release();
            } catch (Exception error) {
                Log.w(TAG, "Unable to release audio muxer", error);
            }
        }
    }

    private static void safeStop(AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (IllegalStateException ignored) {
            // The record may already have been stopped by the audio server.
        }
    }

    private static void safeRelease(AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            record.release();
        } catch (Exception ignored) {
            // Best effort during cleanup.
        }
    }
}
