package androidx.media3.demo.main;

import android.content.Intent;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

@UnstableApi
public class PlaybackService extends MediaSessionService {
    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();

        // Thiết lập chính sách Retry: 3 lần thử lại, mỗi lần cách nhau cố định 5 giây
        DefaultLoadErrorHandlingPolicy retryPolicy = new DefaultLoadErrorHandlingPolicy(3) {
            @Override
            public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
                // errorCount 1 là lần đầu lỗi, 2 là retry lần 1, 3 là retry lần 2...
                if (loadErrorInfo.errorCount <= 3) {
                    sendDrmLog(">>> SYSTEM: Will retry in 5 seconds... (Attempt " + loadErrorInfo.errorCount + "/3)");
                    return 5000;
                }
                return androidx.media3.common.C.TIME_UNSET;
            }

            @Override
            public int getMinimumLoadableRetryCount(int dataType) {
                return 3; // Luôn cho phép thử lại 3 lần
            }
        };

        // Trình phát chuẩn cho điện thoại
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        ExoPlayer player = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setLoadErrorHandlingPolicy(retryPolicy))
                .build();
        
        player.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onDrmSessionAcquired(EventTime eventTime, int state) {
                sendDrmLog("[DRM] Session Acquired (State: " + state + ")");
            }

            @Override
            public void onDrmKeysLoaded(EventTime eventTime) {
                sendDrmLog("[DRM] License Loaded Successfully");
            }

            @Override
            public void onDrmKeysRestored(EventTime eventTime) {
                sendDrmLog("[DRM] License Renewed Successfully");
            }

            @Override
            public void onDrmSessionManagerError(EventTime eventTime, Exception error) {
                sendDrmLog("[DRM] Session Error: " + error.getMessage());
            }
        });

        mediaSession = new MediaSession.Builder(this, player).build();
    }

    private void sendDrmLog(String msg) {
        Intent intent = new Intent("SIGMA_DRM_LOG");
        intent.putExtra("message", msg);
        sendBroadcast(intent);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            Player player = mediaSession.getPlayer();
            player.release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
