package androidx.media3.demo.main;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@UnstableApi
public class SigmaDemoActivity extends AppCompatActivity {

    private PlayerView playerView;
    private Player player;
    private TextView textLogs, textTime;
    private EditText editManifestUri, editBaseUrl, editMerchantId, editAppId, editUserId, editSessionId;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateProgressAction = this::updateProgress;
    private ListenableFuture<MediaController> controllerFuture;

    private final BroadcastReceiver drmLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            if (msg != null) log(msg);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sigma_demo);

        // Đăng ký nhận Log từ Service - Fix cho Android 14
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(drmLogReceiver, new IntentFilter("SIGMA_DRM_LOG"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(drmLogReceiver, new IntentFilter("SIGMA_DRM_LOG"));
        }

        playerView = findViewById(R.id.player_view);
        textLogs = findViewById(R.id.text_logs);
        textLogs.setMovementMethod(new ScrollingMovementMethod());
        textLogs.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        textTime = findViewById(R.id.text_time);
        
        editManifestUri = findViewById(R.id.edit_manifest_uri);
        editBaseUrl = findViewById(R.id.edit_base_url);
        editMerchantId = findViewById(R.id.edit_merchant_id);
        editAppId = findViewById(R.id.edit_app_id);
        editUserId = findViewById(R.id.edit_user_id);
        editSessionId = findViewById(R.id.edit_session_id);

        findViewById(R.id.btn_start).setOnClickListener(v -> startPlayback());
        findViewById(R.id.btn_play).setOnClickListener(v -> { if (player != null) player.play(); });
        findViewById(R.id.btn_pause).setOnClickListener(v -> { if (player != null) player.pause(); });
        findViewById(R.id.btn_reset).setOnClickListener(v -> resetApp());
        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> { textLogs.setText(""); log("Logs cleared."); });
        
        findViewById(R.id.btn_seek_back).setOnClickListener(v -> {
            if (player != null) player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
        });
        findViewById(R.id.btn_seek_forward).setOnClickListener(v -> {
            if (player != null) player.seekTo(player.getCurrentPosition() + 10000);
        });

        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                player = controllerFuture.get();
                if (player != null) {
                    playerView.setPlayer(player);
                    setupPlayerListener();
                    log("Connected to Playback Service.");
                }
            } catch (Exception e) {
                log("Connection Error: " + e.getMessage());
                android.util.Log.e("SigmaDemo", "Controller connection failed", e);
            }
        }, MoreExecutors.directExecutor());

        log("App Ready. Input data and press START.");
    }

    private void setupPlayerListener() {
        if (player == null) return;
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                log(isPlaying ? ">>> EVENT: Play" : ">>> EVENT: Pause");
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { log("Status: Playing (Ready)"); updateProgress(); }
                else if (state == Player.STATE_BUFFERING) log("Status: Buffering...");
            }
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                log("[PLAYER] Fatal Error: " + error.getErrorCodeName());
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
                    log(">>> HINT: No Network Connection.");
                }
                // Các lỗi DRM đã được PlaybackService báo cáo chi tiết, không cần báo lại ở đây
            }
        });
    }

    private void resetApp() {
        editManifestUri.setText("https://sdrm-test.gviet.vn:9080/drm/static/vod_staging/the_box/manifest.mpd");
        editBaseUrl.setText("https://license-staging.sigmadrm.com/license/verify/widevine");
        editMerchantId.setText("sctv"); editAppId.setText("RedTV");
        editUserId.setText("U_Pnh_And"); editSessionId.setText("S_Pnh_And");
        log("UI Reseted.");
    }

    private void startPlayback() {
        if (player == null) { log("Connecting to service..."); return; }

        String manifestUri = editManifestUri.getText().toString().trim();
        // ... (phần lấy các biến khác giữ nguyên)
        String baseUrl = editBaseUrl.getText().toString().trim();
        String merchantId = editMerchantId.getText().toString().trim();
        String appId = editAppId.getText().toString().trim();
        String userId = editUserId.getText().toString().trim();
        String sessionId = editSessionId.getText().toString().trim();

        String finalBaseUrl = baseUrl;
        if (!finalBaseUrl.contains("/license/verify/widevine")) {
            finalBaseUrl += finalBaseUrl.endsWith("/") ? "license/verify/widevine" : "/license/verify/widevine";
        }
        String connector = finalBaseUrl.contains("?") ? "&" : "?";
        String licenseUrl = String.format(Locale.US, "%s%smerchantId=%s&appId=%s&userId=%s&sessionId=%s",
                finalBaseUrl, connector, android.net.Uri.encode(merchantId), android.net.Uri.encode(appId),
                android.net.Uri.encode(userId), android.net.Uri.encode(sessionId));

        log(">>> STARTING: Requesting Manifest...");
        log("License URL set to: " + licenseUrl);
        
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(manifestUri)
                .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(licenseUrl)
                        .setForceDefaultLicenseUri(true)
                        .build())
                .build();

        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    private void updateProgress() {
        if (player != null && player.getPlaybackState() == Player.STATE_READY && player.getPlayWhenReady()) {
            textTime.setText(formatTime(player.getCurrentPosition()) + " / " + formatTime(player.getDuration()));
            handler.postDelayed(updateProgressAction, 1000);
        }
    }

    private String formatTime(long timeMs) {
        if (timeMs < 0) return "00:00";
        long s = timeMs / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", (s / 60) % 60, s % 60);
    }

    private void log(String message) {
        String time = dateFormat.format(new Date());
        runOnUiThread(() -> {
            String currentLogs = textLogs.getText().toString();
            textLogs.setText("[" + time + "] " + message + "\n" + currentLogs);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(drmLogReceiver);
        MediaController.releaseFuture(controllerFuture);
    }
}
