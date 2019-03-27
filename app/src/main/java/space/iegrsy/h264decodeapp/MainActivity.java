package space.iegrsy.h264decodeapp;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;

import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import space.iegrsy.h264player.player.H264Player;
import vms.Nvr;
import vms.NvrServiceGrpc;

public class MainActivity extends AppCompatActivity {
    private String mUid = "";
    private String mHost = "10.5.178.83";
    private int mPort = 60001;

    private H264Player player;

    private boolean isReadyChannel = false;
    private ManagedChannel channel;
    private StreamObserver<Nvr.CameraStreamQ> queryStreamObserver;

    private Nvr.CameraStreamQ streamQ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initConnection();

        SurfaceView surfaceView = findViewById(R.id.player_surface);
        surfaceView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) {
                    initConnection();
                    player.start();
                    queryStreamObserver.onNext(streamQ);
                    Log.v("debug", "click start " + streamQ.getBeginTs());
                }
            }
        });
        player = new H264Player(surfaceView);
        player.setPlayingChangeListener(new H264Player.PlayingChangeListener() {
            @Override
            public void onChange(boolean isPlay) {
                Log.v("debug", "Player change: " + isPlay);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null)
            player.stop();
    }

    private void initConnection() {
        if (isReadyChannel)
            return;

        if (channel != null) {
            channel.shutdownNow();
            channel = null;
        }

        channel = ManagedChannelBuilder.forAddress(mHost, mPort).usePlaintext().build();
        NvrServiceGrpc.NvrServiceStub stub = NvrServiceGrpc.newStub(channel);
        NvrServiceGrpc.NvrServiceBlockingStub blockingStub = NvrServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2000, TimeUnit.MILLISECONDS);

        Nvr.CameraList cameraList = blockingStub.getCameraList(Nvr.CameraFilters.newBuilder().build());
        mUid = cameraList.getListCount() > 0 ? cameraList.getList(0).getUniqueId() : "";
        Nvr.RecordDetails recordDetails = blockingStub.getRecordDetails(Nvr.CameraQ.newBuilder().setUniqueId(mUid).build());

        long ts = recordDetails.getRecordBeginTime();
        Nvr.PlaybackChanges playbackChanges = Nvr.PlaybackChanges.newBuilder().setType(Nvr.PlaybackChanges.PlaybackChangesTypes.PC_SEEK_ABSOLUTE).build();
        streamQ = Nvr.CameraStreamQ.newBuilder().setUniqueId(mUid).setBeginTs(ts).setPc(playbackChanges).build();

        queryStreamObserver = stub.getCameraStream(cameraFrameStreamObserver);
        isReadyChannel = true;
    }

    private final StreamObserver<Nvr.StreamFrame> cameraFrameStreamObserver = new StreamObserver<Nvr.StreamFrame>() {
        Nvr.CameraStreamQ noneStreamQ = Nvr.CameraStreamQ.newBuilder().setPc(
                Nvr.PlaybackChanges.newBuilder().setType(Nvr.PlaybackChanges.PlaybackChangesTypes.PC_NONE).build()
        ).build();

        @Override
        public void onNext(Nvr.StreamFrame value) {
            if (player != null)
                player.useFrameData(value.getData(0).getData().toByteArray(), value.getData(0).getTs());
            if (queryStreamObserver != null)
                queryStreamObserver.onNext(noneStreamQ);
        }

        @Override
        public void onError(Throwable t) {
            Log.e("debug", String.format("[%s]: %s", Status.fromThrowable(t).getCode(), Status.fromThrowable(t).getDescription()));
            isReadyChannel = false;
        }

        @Override
        public void onCompleted() {
        }
    };
}
