package com.example.aospscreenrecorder;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class Mp4Muxer {
    private static final int BUFFER_SIZE = 4 * 1024 * 1024;

    private final File output;
    private final File[] inputs;

    Mp4Muxer(File output, File... inputs) {
        this.output = output;
        this.inputs = inputs;
    }

    void mux() throws IOException {
        List<Source> sources = new ArrayList<>();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            muxer = new MediaMuxer(
                    output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (File input : inputs) {
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(input.getAbsolutePath());
                for (int track = 0; track < extractor.getTrackCount(); track++) {
                    MediaFormat format = extractor.getTrackFormat(track);
                    int outputTrack = muxer.addTrack(format);
                    sources.add(new Source(extractor, track, outputTrack));
                }
            }

            muxer.start();
            muxerStarted = true;
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (Source source : sources) {
                source.extractor.selectTrack(source.inputTrack);
                while (true) {
                    buffer.clear();
                    int size = source.extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        break;
                    }
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = source.extractor.getSampleTime();
                    int sampleFlags = source.extractor.getSampleFlags();
                    info.flags = 0;
                    if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        info.flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        info.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                    }
                    muxer.writeSampleData(source.outputTrack, buffer, info);
                    source.extractor.advance();
                }
                source.extractor.unselectTrack(source.inputTrack);
            }
        } finally {
            for (Source source : sources) {
                source.extractor.release();
            }
            if (muxer != null) {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            }
        }
    }

    private static final class Source {
        final MediaExtractor extractor;
        final int inputTrack;
        final int outputTrack;

        Source(MediaExtractor extractor, int inputTrack, int outputTrack) {
            this.extractor = extractor;
            this.inputTrack = inputTrack;
            this.outputTrack = outputTrack;
        }
    }
}
