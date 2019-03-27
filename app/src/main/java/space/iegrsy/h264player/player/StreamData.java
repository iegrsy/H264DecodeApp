package space.iegrsy.h264player.player;

import android.util.Log;

import java.util.ArrayList;

public class StreamData {
    private static final String TAG = StreamData.class.getSimpleName();

    private static final String remove_str = "Array size cannot be greater than %s. The first item removed in the array.";
    private static final String throw_str = "Array size: %s => out of index: %s";

    private static final int MAX_FRAMES_SIZE = 20000;

    private int frameID = 0;
    private ArrayList<RAWFrame> frames = new ArrayList<>();

    private byte[] header_sps = null;
    private byte[] header_pps = null;

    StreamData() {
    }

    private synchronized void incrementFrameID() {
        frameID++;
    }

    public synchronized int getFrameID() {
        return frameID;
    }

    private synchronized void addFrame(RAWFrame frame) {
        if (frame != null) {
            if (frames.size() > MAX_FRAMES_SIZE) {
                removeFrame(0);
                Log.i(TAG, String.format(remove_str, MAX_FRAMES_SIZE));
            }
            frames.add(frame);
        }
    }

    public synchronized RAWFrame getFrame(int index) throws ArrayIndexOutOfBoundsException {
        if (0 > index || index >= frames.size())
            throw new ArrayIndexOutOfBoundsException(String.format(throw_str, frames.size(), index));

        return frames.get(index);
    }

    public synchronized void removeFrame(int index) throws ArrayIndexOutOfBoundsException {
        if (0 > index || index >= frames.size())
            throw new ArrayIndexOutOfBoundsException(String.format(throw_str, frames.size(), index));

        frames.remove(index);
    }

    public synchronized void useFrameData(byte[] readInData, long ts) throws Exception {
        if (readInData[0] != 0 || readInData[1] != 0 || readInData[2] != 0 || readInData[3] != 1)
            throw new Exception("Decode condition error.");

        if (header_sps == null) {
            byte[] sps = H264Decoder.Helper.findSPS(readInData);
            if (sps != null) {
                header_sps = sps;
                Log.v(TAG, "SPS setting: " + header_sps.length);
            }
        }

        if (header_pps == null) {
            byte[] pps = H264Decoder.Helper.findPPS(readInData);
            if (pps != null) {
                header_pps = pps;
                Log.v(TAG, "PPS setting: " + header_pps.length);
            }
        }

        if (header_sps != null && header_pps != null && readInData.length > 300) { //TODO: hack
            //Log.v(TAG, "New frame with size: " + readInData.length);
            StreamData.RAWFrame frame = new StreamData.RAWFrame(frameID);
            frame.frameData = readInData;
            frame.ts = ts;

            addFrame(frame);
            incrementFrameID();
        }
    }

    public synchronized ArrayList<RAWFrame> getFrames() {
        return frames;
    }

    public synchronized void setHeader_sps(byte[] header_sps) {
        this.header_sps = header_sps;
    }

    public synchronized void setHeader_pps(byte[] header_pps) {
        this.header_pps = header_pps;
    }

    public synchronized byte[] getHeader_sps() {
        return header_sps;
    }

    public synchronized byte[] getHeader_pps() {
        return header_pps;
    }

    public synchronized void clearAll() {
        frameID = 0;
        frames.clear();
        header_sps = null;
        header_pps = null;
    }

    public static class RAWFrame {
        int id;
        byte[] frameData;
        long ts;

        RAWFrame(int id) {
            this.id = id;
        }
    }
}
