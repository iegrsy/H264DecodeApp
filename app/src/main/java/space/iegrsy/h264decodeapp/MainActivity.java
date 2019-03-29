package space.iegrsy.h264decodeapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.TextInputEditText;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.TranslateAnimation;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import space.iegrsy.h264player.player.H264Player;
import vms.Nvr;
import vms.NvrServiceGrpc;

public class MainActivity extends AppCompatActivity {
    private Context context = this;

    private H264Player player;
    private PlayerFeeder playerFeeder;

    private LinearLayout linearLayout;
    private ImageButton connectBtn;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SurfaceView surfaceView = findViewById(R.id.player_surface);
        surfaceView.setOnTouchListener(new UIHelper.OnSwipeTouchListener(this,
                new UIHelper.OnSwipeTouchListener.SwipeListener() {
                    @Override
                    public void onSwipeRight() {

                    }

                    @Override
                    public void onSwipeLeft() {

                    }

                    @Override
                    public void onSwipeTop() {
                        UIHelper.slideToUp(linearLayout);
                    }

                    @Override
                    public void onSwipeBottom() {
                        UIHelper.slideToDown(linearLayout);
                    }
                }
        ));

        linearLayout = findViewById(R.id.control_layout);
        connectBtn = findViewById(R.id.connect_btn);
        connectBtn.setOnClickListener(connectOnClickListener);

        player = new H264Player(surfaceView);
        player.setPlayingChangeListener(playingChangeListener);
    }

    private boolean isConnected = false;

    private View.OnClickListener connectOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            String host = ((TextInputEditText) findViewById(R.id.txt_host)).getText().toString();
            int port = Integer.parseInt(((TextInputEditText) findViewById(R.id.txt_port)).getText().toString());

            if (isConnected) {
                if (playerFeeder != null) {
                    playerFeeder.release();
                    playerFeeder = null;
                }

                if (player != null)
                    player.stop();
            } else {
                if (player != null)
                    player.start();

                if (playerFeeder == null)
                    playerFeeder = new PlayerFeeder().init(host, port, player);
                playerFeeder.start();
            }
        }
    };

    private H264Player.PlayingChangeListener playingChangeListener = new H264Player.PlayingChangeListener() {
        @Override
        public void onChange(final boolean isPlay) {
            isConnected = isPlay;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    connectBtn.setImageDrawable(isPlay ? getDrawable(R.drawable.ic_close_black_24dp) : getDrawable(R.drawable.ic_call_made_black_24dp));
                }
            });
            if (!isPlay && playerFeeder != null)
                playerFeeder.release();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (playerFeeder != null)
            playerFeeder.release();
        if (player != null)
            player.stop();
    }

    private static class PlayerFeeder {
        private boolean isReadyChannel = false;

        private ManagedChannel channel;
        private NvrServiceGrpc.NvrServiceStub stub;
        private NvrServiceGrpc.NvrServiceBlockingStub blockingStub;

        private StreamObserver<Nvr.CameraStreamQ> queryStreamObserver;

        private H264Player player;

        public PlayerFeeder init(final String host, final int port, @NonNull final H264Player player) {
            this.player = player;

            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            stub = NvrServiceGrpc.newStub(channel);
            blockingStub = NvrServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2000, TimeUnit.MILLISECONDS);
            isReadyChannel = true;

            return this;
        }

        public void start() {
            String uid = "";
            long ts = 0;

            // TODO: UNIMPLEMENTED
            // getAnyCamera(uid, ts);

            Nvr.PlaybackChanges playbackChanges = Nvr.PlaybackChanges.newBuilder().setType(Nvr.PlaybackChanges.PlaybackChangesTypes.PC_SEEK_ABSOLUTE).build();
            Nvr.CameraStreamQ streamQ = Nvr.CameraStreamQ.newBuilder().setUniqueId(uid).setBeginTs(ts).setPc(playbackChanges).build();

            queryStreamObserver = stub.getCameraStream(cameraFrameStreamObserver);
            queryStreamObserver.onNext(streamQ);
        }

        public void release() {
            cameraFrameStreamObserver.onCompleted();
            if (channel != null) {
                channel.shutdownNow();
                channel = null;
            }
            isReadyChannel = false;
        }

        private void getAnyCamera(String uid, long beginTS) {
            if (!isReadyChannel)
                throw new NullPointerException("Please create channel. Channel null");

            Nvr.CameraList cameraList = blockingStub.getCameraList(Nvr.CameraFilters.newBuilder().build());
            uid = cameraList.getListCount() > 0 ? cameraList.getList(0).getUniqueId() : "";

            Nvr.RecordDetails recordDetails = blockingStub.getRecordDetails(Nvr.CameraQ.newBuilder().setUniqueId(uid).build());
            beginTS = recordDetails.getRecordBeginTime();
        }

        private final StreamObserver<Nvr.StreamFrame> cameraFrameStreamObserver = new StreamObserver<Nvr.StreamFrame>() {
            Nvr.CameraStreamQ noneStreamQ = Nvr.CameraStreamQ.newBuilder().setPc(
                    Nvr.PlaybackChanges.newBuilder().setType(Nvr.PlaybackChanges.PlaybackChangesTypes.PC_NONE).build()
            ).build();

            @Override
            public void onNext(Nvr.StreamFrame value) {
                //Log.w("debug", "onNext");
                if (player != null)
                    player.useFrameData(value.getData(0).getData().toByteArray(), value.getData(0).getTs());
                if (queryStreamObserver != null)
                    queryStreamObserver.onNext(noneStreamQ);
            }

            @Override
            public void onError(Throwable t) {
                Log.e("debug", String.format("[%s]: %s", Status.fromThrowable(t).getCode(), Status.fromThrowable(t).getDescription()));
                if (player != null)
                    player.stop();
            }

            @Override
            public void onCompleted() {
                Log.w("debug", "onCompleted");
            }
        };
    }

    public static class UIHelper {
        public static void toggleViewLeftRight(View view) {
            if (view.getVisibility() != View.VISIBLE)
                slideToRight(view);
            else
                slideToLeft(view);
        }

        public static void toggleViewUpDown(View view) {
            if (view.getVisibility() != View.VISIBLE)
                slideToUp(view);
            else
                slideToDown(view);
        }

        // To animate view slide out from left to right
        public static void slideToRight(View view) {
            TranslateAnimation animate = new TranslateAnimation(-view.getWidth(), 0, 0, 0);
            animate.setDuration(500);
            animate.setFillAfter(false);
            view.startAnimation(animate);
            view.setVisibility(View.VISIBLE);
        }

        // To animate view slide out from right to left
        public static void slideToLeft(View view) {
            TranslateAnimation animate = new TranslateAnimation(0, -view.getWidth(), 0, 0);
            animate.setDuration(500);
            animate.setFillAfter(false);
            view.startAnimation(animate);
            view.setVisibility(View.GONE);
        }

        // To animate view slide out from left to right
        public static void slideToUp(View view) {
            TranslateAnimation animate = new TranslateAnimation(0, 0, view.getHeight(), 0);
            animate.setDuration(500);
            animate.setFillAfter(false);
            view.startAnimation(animate);
            view.setVisibility(View.VISIBLE);
        }

        // To animate view slide out from right to left
        public static void slideToDown(View view) {
            TranslateAnimation animate = new TranslateAnimation(0, 0, 0, view.getHeight());
            animate.setDuration(500);
            animate.setFillAfter(false);
            view.startAnimation(animate);
            view.setVisibility(View.GONE);
        }

        public static class OnSwipeTouchListener implements View.OnTouchListener {

            private final GestureDetector gestureDetector;

            public OnSwipeTouchListener(Context ctx, SwipeListener swipeListener) {
                gestureDetector = new GestureDetector(ctx, new GestureListener(swipeListener));
            }

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
            }

            private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
                private static final int SWIPE_THRESHOLD = 100;
                private static final int SWIPE_VELOCITY_THRESHOLD = 100;

                private final SwipeListener swipeListener;

                GestureListener(SwipeListener swipeListener) {
                    this.swipeListener = swipeListener;
                }

                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    boolean result = false;
                    try {
                        float diffY = e2.getY() - e1.getY();
                        float diffX = e2.getX() - e1.getX();
                        if (Math.abs(diffX) > Math.abs(diffY)) {
                            if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                                if (diffX > 0) {
                                    if (swipeListener != null) swipeListener.onSwipeRight();
                                } else {
                                    if (swipeListener != null) swipeListener.onSwipeLeft();
                                }
                                result = true;
                            }
                        } else if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                            if (diffY > 0) {
                                if (swipeListener != null) swipeListener.onSwipeBottom();
                            } else {
                                if (swipeListener != null) swipeListener.onSwipeTop();
                            }
                            result = true;
                        }
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                    return result;
                }
            }

            public interface SwipeListener {
                void onSwipeRight();

                void onSwipeLeft();

                void onSwipeTop();

                void onSwipeBottom();
            }
        }
    }
}
