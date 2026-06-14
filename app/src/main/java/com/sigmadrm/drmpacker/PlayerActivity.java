package com.sigmadrm.drmpacker;

import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.ErrorMessageProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.MediaDrmCallback;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.PlayerView;

import com.sigma.packer.SigmaMediaDrm;

@UnstableApi
public class PlayerActivity extends AppCompatActivity
    implements View.OnClickListener, PlayerControlView.VisibilityListener {
  private PlayerView playerView;
  private ExoPlayer player;
  private DefaultTrackSelector trackSelector;
  String drmLicenseUrl;
  String videoPath;
  String merchantId;
  String appId;
  String userId;
  String sessionId;
  Button playBtn = null;

  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    initStream();

    setContentView(R.layout.activity_player);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();

    SurfaceView surfaceView = (SurfaceView) playerView.getVideoSurfaceView();
    surfaceView.setSecure(false);

    playBtn = findViewById(R.id.play);
    playBtn.setOnClickListener(v -> {
      releasePlayer();
      initializePlayer();
    });

    displayConfig();
  }

  private void displayConfig() {
    android.widget.TextView tvUrl = findViewById(R.id.tv_url);
    if (tvUrl != null)
      tvUrl.setText("URL: " + videoPath);

    android.widget.TextView tvLicense = findViewById(R.id.tv_license);
    if (tvLicense != null)
      tvLicense.setText("License: " + drmLicenseUrl);

    android.widget.TextView tvMerchant = findViewById(R.id.tv_merchant_id);
    if (tvMerchant != null)
      tvMerchant.setText("Merchant ID: " + merchantId);

    android.widget.TextView tvApp = findViewById(R.id.tv_app_id);
    if (tvApp != null)
      tvApp.setText("App ID: " + appId);

    android.widget.TextView tvUser = findViewById(R.id.tv_user_id);
    if (tvUser != null)
      tvUser.setText("User ID: " + userId);

    android.widget.TextView tvSession = findViewById(R.id.tv_session_id);
    if (tvSession != null)
      tvSession.setText("Session ID: " + sessionId);
  }

  private void initStream() {
    videoPath = "https://sdrm-test.gviet.vn:9080/drm/static/vod_staging/big_bug_bunny/manifest.mpd";
    drmLicenseUrl = "https://license-staging.sigmadrm.com/license/verify/widevine";

    videoPath = "https://s129137.cdn.mytvnet.vn/pkg20/__cl/gvtsig/vstv263/manifest.mpd?prefix=1&did=5329&ctl=239.174";
    drmLicenseUrl = "https://license.sigmadrm.com/license/verify/widevine";

    merchantId = "mytv";
    appId = "sigma";
    userId = "mytv";
    sessionId = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJtZW1iZXJfaWQiOjY1Njc4MjY2LCJtYW51ZmFjdHVyZXJfaWQiOiJmNTJmNDgzYTIxYTBlNTM1IiwiaWF0IjoxNzgxMzYzOTg1LCJleHAiOjE3ODEzODU1ODV9.ps7E0384YGJf4RjDIVNbAnwGT6CToLv4yPoXOQ7MkCY";
  }

  @Override
  public void onStart() {
    super.onStart();
    if (Util.SDK_INT > 23) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Util.SDK_INT <= 23 || player == null) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (Util.SDK_INT <= 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (Util.SDK_INT > 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  private void releasePlayer() {
    if (player != null) {
      player.release();
      player = null;
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
      @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length == 0) {
      // Empty results are triggered if a permission is requested while another
      // request was already
      // pending and can be safely ignored in this case.
      return;
    }
    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      initializePlayer();
    } else {
      showToast(R.string.storage_permission_denied);
      finish();
    }
  }

  @Override
  public void onVisibilityChange(int visibility) {

  }

  private void initializePlayer() {
    DrmSessionManager drmSessionManager;
    if (Util.SDK_INT >= 18) {
      UUID drmSchemeUuid = Assertions.checkNotNull(Util.getDrmUuid("widevine"));
      MediaDrmCallback drmCallback = createMediaDrmCallback(drmLicenseUrl, null);
      drmSessionManager = new DefaultDrmSessionManager.Builder()
          .setMultiSession(true)
          .setUuidAndExoMediaDrmProvider(drmSchemeUuid, SigmaMediaDrm.DEFAULT_PROVIDER)
          .build(drmCallback);
    } else {
      drmSessionManager = DrmSessionManager.DRM_UNSUPPORTED;
    }

    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoPath));
    MediaSource.Factory mediaSourceFactory = new DefaultMediaSourceFactory(getApplicationContext())
        .setDrmSessionManagerProvider(mi -> drmSessionManager);
    MediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);

    trackSelector = new DefaultTrackSelector(/* context= */ this);
    DefaultTrackSelector.Parameters trackSelectionParameters = new DefaultTrackSelector.ParametersBuilder(
        /* context= */ this)
        .setAllowVideoMixedMimeTypeAdaptiveness(true)
        .setAllowVideoNonSeamlessAdaptiveness(true)
        .build();

    DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(getApplicationContext());
    boolean isNote20Ultra = android.os.Build.MODEL.contains("N986") || android.os.Build.MODEL.contains("N985");
    Log.e("SIGMA", "Model " +  android.os.Build.MODEL);
    if (isNote20Ultra) {
      renderersFactory.forceDisableMediaCodecAsynchronousQueueing();
    }

    player = new ExoPlayer.Builder(getApplicationContext(), renderersFactory)
        .setTrackSelector(trackSelector)
        .build();
    player.setTrackSelectionParameters(trackSelectionParameters);
    player.setMediaSource(mediaSource);
    player.prepare();
    player.play();
    playerView.setPlayer(player);
    player.addAnalyticsListener(new EventLogger(trackSelector));
  }

  private WidevineMediaDrmCallback createMediaDrmCallback(String licenseUrl, String[] keyRequestPropertiesArray) {
    HttpDataSource.Factory licenseDataSourceFactory = ((ExoplayerApplication) getApplication())
        .buildHttpDataSourceFactory();
    WidevineMediaDrmCallback drmCallback = new WidevineMediaDrmCallback(licenseUrl, licenseDataSourceFactory);
    drmCallback.setSigmaConfig(merchantId, appId, userId, sessionId);
    if (keyRequestPropertiesArray != null) {
      for (int i = 0; i < keyRequestPropertiesArray.length - 1; i += 2) {
        drmCallback.setKeyRequestProperty(keyRequestPropertiesArray[i],
            keyRequestPropertiesArray[i + 1]);
      }
    }
    return drmCallback;
  }

  @Override
  public void onClick(View view) {

  }

  private class PlayerErrorMessageProvider implements ErrorMessageProvider<PlaybackException> {
    @Override
    public Pair<Integer, String> getErrorMessage(PlaybackException e) {
      String errorString = e.getErrorCodeName();
      Throwable cause = e.getCause();
      if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
        // Special case for decoder initialization failures.
        MediaCodecRenderer.DecoderInitializationException decoderInitializationException = (MediaCodecRenderer.DecoderInitializationException) cause;
        if (decoderInitializationException.codecInfo == null) {
          if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
            errorString = getString(R.string.error_querying_decoders);
          } else if (decoderInitializationException.secureDecoderRequired) {
            errorString = getString(
                R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
          } else {
            errorString = getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
          }
        } else {
          errorString = getString(
              R.string.error_instantiating_decoder,
              decoderInitializationException.codecInfo.name);
        }
      }
      return Pair.create(0, e.errorCode + ": " + errorString);
    }
  }

  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

}
