package space.iegrsy.h264player.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

public class H264Decoder {
    private static final String TAG = H264Decoder.class.getSimpleName();

    public enum DecoderState {IDLE, READY, ERROR}

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final double FRAME_RATE = 30.0;
    private static final double FRAME_RATE_TS = 1000.0 / FRAME_RATE;
    private static final int TIMEOUT_U_SEC = 10000;
    private static final int TIMEOUT_SEC = 10000;
    private static final int WAIT_TIME = 300;

    private double mFrameRate = FRAME_RATE;
    private double mFrameRateTs = FRAME_RATE_TS;

    private int mMediaCodecWidth = 1920;
    private int mMediaCodecHeight = 1080;

    private StreamData mData;

    private DecoderState mDecoderState = DecoderState.IDLE;

    private boolean isRun = false;
    private Thread mDecodeThread = null;
    private DecoderStateListener mStateListener = null;

    private Surface mSurface;

    public H264Decoder(@NonNull Surface surface) {
        mSurface = surface;
        mData = new StreamData();
    }

    public H264Decoder(@NonNull StreamData data, @NonNull Surface surface) {
        mData = data;
        mSurface = surface;
    }

    public H264Decoder(@NonNull StreamData data, @NonNull Surface surface, double fps) {
        mData = data;
        mSurface = surface;
        setFrameRate(fps);
    }

    public void setFrameRate(double frameRate) {
        // Check range frame rate
        if (frameRate <= 0 || frameRate > 120)
            return;

        mFrameRate = frameRate;
        mFrameRateTs = 1000.0 / frameRate;
    }

    public void setStateListener(DecoderStateListener stateListener) {
        mStateListener = stateListener;
    }

    public DecoderState getDecoderState() {
        return mDecoderState;
    }

    public void start() {
        if (mDecodeThread != null)
            release();

        mDecodeThread = new Thread(decodeRunnable, TAG);
        mDecodeThread.start();
    }

    public void release() {
        isRun = false;
        if (mDecodeThread != null) {
            try {
                Log.v(TAG, "Decoder thread is join");
                mDecodeThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mDecodeThread = null;
        }

        setDecoderState(DecoderState.IDLE, "Decoder release");
    }

    private Runnable decodeRunnable = new Runnable() {
        @Override
        public void run() {
            isRun = true;

            MediaCodec mDecoder = prepareDecoder();
            if (mDecoder == null || mDecoderState != DecoderState.READY) {
                setDecoderState(DecoderState.IDLE, "Decoder not ready");
                return;
            }

            int frameIndex = 0;
            StreamData.RAWFrame frame;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (isRun) {
                if (mData.getFrames().size() <= 0)
                    continue;

                frame = mData.getFrame(frameIndex);
                mData.removeFrame(frameIndex);

                int inIndex;
                while ((inIndex = mDecoder.dequeueInputBuffer(TIMEOUT_U_SEC)) < 0)
                    if (!isRun) break;

                if (inIndex >= 0) {
                    Objects.requireNonNull(mDecoder.getInputBuffer(inIndex)).put(frame.frameData);
                    mDecoder.queueInputBuffer(inIndex, 0, frame.frameData.length, 0, MediaCodec.CRYPTO_MODE_UNENCRYPTED);

                    int outIndex = mDecoder.dequeueOutputBuffer(info, TIMEOUT_U_SEC);
                    switch (outIndex) {
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            Log.d("Buffer info", "INFO_TRY_AGAIN_LATER");
                            break;
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            Log.d("Buffer info", "INFO_OUTPUT_FORMAT_CHANGED: New format: " + mDecoder.getOutputFormat());
                            break;
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d("Buffer info", "INFO_OUTPUT_BUFFERS_CHANGED");
                            break;
                        default:
                            mDecoder.releaseOutputBuffer(outIndex, true);
                            break;
                    }
                }
            }

            mDecoder.setParameters(null);
            mDecoder.stop();
            mDecoder.release();

            setDecoderState(DecoderState.IDLE, "Decoding stop");
        }
    };

    private MediaCodec prepareDecoder() {
        MediaCodec mDecoder;
        long ts = System.currentTimeMillis();
        while (mData.getHeader_sps() == null || mData.getHeader_pps() == null || mData.getFrames().size() <= 0) {
            try {
                if (!isRun)
                    throw new Exception("Stopped decoder");
                if (System.currentTimeMillis() - ts > TIMEOUT_SEC)
                    throw new Exception("Timeout setup decoder");
                Thread.sleep(WAIT_TIME);
                Log.v(TAG, "Waiting SPS PPS data ...");
            } catch (InterruptedException e) {
                setDecoderState(DecoderState.ERROR, e.getMessage());
                return null;
            } catch (Exception e) {
                setDecoderState(DecoderState.ERROR, e.getMessage());
                return null;
            }
        }

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, mMediaCodecWidth, mMediaCodecHeight);
        mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(mData.getHeader_sps()));
        mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(mData.getHeader_pps()));
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, (int) mFrameRate);

        try {
            mDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            mDecoder.configure(mediaFormat, mSurface, null, 0);
            mDecoder.start();
        } catch (IOException e) {
            e.printStackTrace();
            setDecoderState(DecoderState.ERROR, e.getMessage());
            return null;
        }

        setDecoderState(DecoderState.READY, "Prepared decoder");

        return mDecoder;
    }

    private void setDecoderState(DecoderState state, String msg) {
        mDecoderState = state;
        if (mStateListener != null)
            mStateListener.onState(state, msg);
    }

    public interface DecoderStateListener {
        void onState(DecoderState state, @Nullable String msg);
    }

    public static class Helper {
        public static int findNextStart(byte[] data, int off) {
            if (off >= data.length)
                return -1;

            for (int i = off; i < data.length; i++)
                if ((data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1)
                        || (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1))
                    return i;

            return -1;
        }

        public static byte[] findSPS(byte[] data) {
            for (int i = 0; i < data.length - 5; i++) {
                if (data[i] == 0 &&
                        data[i + 1] == 0 &&
                        data[i + 2] == 0 &&
                        data[i + 3] == 1 &&
                        (data[i + 4] & 0x1f) == 0x07) {
                    int next = findNextStart(data, i + 5);
                    return Arrays.copyOfRange(data, i, next > i ? next : data.length);
                }
            }

            return null;
        }

        public static byte[] findPPS(byte[] data) {
            for (int i = 0; i < data.length - 5; i++) {
                if (data[i] == 0 &&
                        data[i + 1] == 0 &&
                        data[i + 2] == 0 &&
                        data[i + 3] == 1 &&
                        (data[i + 4] & 0x1f) == 0x08) {
                    int next = findNextStart(data, i + 5);
                    return Arrays.copyOfRange(data, i, next > i ? next : data.length);
                }
            }

            return null;
        }
    }
}
