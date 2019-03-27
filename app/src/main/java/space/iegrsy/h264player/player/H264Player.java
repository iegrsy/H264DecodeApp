package space.iegrsy.h264player.player;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class H264Player implements SurfaceHolder.Callback {
    private static final String TAG = H264Player.class.getSimpleName();

    SurfaceHolder mHolder;

    private StreamData mData;

    private double mFrameRate = 30.0;
    private H264Decoder mDecoder;

    private boolean isPlay = false;
    private PlayingChangeListener playingChangeListener;

    public H264Player(@NonNull SurfaceView surfaceView) {
        mHolder = surfaceView.getHolder();
        mHolder.addCallback(this);
        mData = new StreamData();
    }

    public H264Player(@NonNull SurfaceView surfaceView, double fps) {
        mHolder = surfaceView.getHolder();
        mHolder.addCallback(this);
        mData = new StreamData();
        mFrameRate = fps;
    }

    public void useFrameData(byte[] readInData, long ts) {
        if (!isPlay)
            return;

        try {
            if (readInData != null && readInData.length <= 0)
                throw new Exception("Size error !!!");

            mData.useFrameData(readInData, ts);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void useFrameData(byte[] readInData) {
        useFrameData(readInData, System.currentTimeMillis());
    }

    public void setPlayingChangeListener(PlayingChangeListener playingChangeListener) {
        this.playingChangeListener = playingChangeListener;
    }

    private void setIsPlay(boolean isPlay) {
        this.isPlay = isPlay;
        if (playingChangeListener != null)
            playingChangeListener.onChange(isPlay);
    }

    public boolean isPlay() {
        return isPlay;
    }

    public void start() {
        if (isPlay)
            stop();

        mDecoder = new H264Decoder(mData, mHolder.getSurface(), mFrameRate);
        mDecoder.setStateListener(decoderStateListener);
        mDecoder.start();

        setIsPlay(true);
    }

    public void stop() {
        if (mDecoder != null) {
            mDecoder.release();
            mDecoder = null;
        }

        if (mData != null)
            mData.clearAll();

        setIsPlay(false);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stop();
    }

    private H264Decoder.DecoderStateListener decoderStateListener = new H264Decoder.DecoderStateListener() {
        @Override
        public void onState(H264Decoder.DecoderState state, @Nullable String msg) {
            Log.v(TAG, String.format("[%s]:%s", state, msg));
            if (state == H264Decoder.DecoderState.IDLE)
                setIsPlay(false);
            else if (state == H264Decoder.DecoderState.READY)
                setIsPlay(true);
        }
    };

    public interface PlayingChangeListener {
        void onChange(boolean isPlay);
    }
}