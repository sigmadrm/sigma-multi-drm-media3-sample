package com.sigmadrm.drmpacker;

import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
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
public class PlayerActivity extends AppCompatActivity implements View.OnClickListener, PlayerControlView.VisibilityListener {
  private PlayerView playerView;
  private ExoPlayer player;
  private DefaultTrackSelector trackSelector;
  String drmLicenseUrl;
  String videoPath;
  Button playBtn = null;

  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    initStream();

    setContentView(R.layout.activity_player);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();

    playBtn = findViewById(R.id.play);
    playBtn.setOnClickListener(v -> {
      releasePlayer();
      initializePlayer();
    });
  }

  private void initStream() {
    videoPath = "https://sdrm-test.gviet.vn:9080/static/vod_staging/the_box/manifest.mpd";
    drmLicenseUrl = "https://license-staging.sigmadrm.com/license/verify/widevine";
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
      // Empty results are triggered if a permission is requested while another request was already
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
      drmSessionManager =
              new DefaultDrmSessionManager.Builder()
                      .setMultiSession(true)
                      .setUuidAndExoMediaDrmProvider(drmSchemeUuid, SigmaMediaDrm.DEFAULT_PROVIDER)
                      .build(drmCallback);
    } else {
      drmSessionManager = DrmSessionManager.DRM_UNSUPPORTED;
    }

    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoPath));
    MediaSource.Factory mediaSourceFactory =
            new DefaultMediaSourceFactory(getApplicationContext())
                    .setDrmSessionManagerProvider(mi -> drmSessionManager);
    MediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);

    trackSelector = new DefaultTrackSelector(/* context= */ this);
    DefaultTrackSelector.Parameters trackSelectionParameters =
            new DefaultTrackSelector.ParametersBuilder(/* context= */ this)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .build();
    player = new ExoPlayer.Builder(getApplicationContext())
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
    HttpDataSource.Factory licenseDataSourceFactory =
            ((ExoplayerApplication) getApplication()).buildHttpDataSourceFactory();
    WidevineMediaDrmCallback drmCallback =
            new WidevineMediaDrmCallback(licenseUrl, licenseDataSourceFactory);
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
      String errorString = getString(R.string.error_generic);
      Throwable cause = e.getCause();
      if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
        // Special case for decoder initialization failures.
        MediaCodecRenderer.DecoderInitializationException decoderInitializationException =
                (MediaCodecRenderer.DecoderInitializationException) cause;
        if (decoderInitializationException.codecInfo == null) {
          if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
            errorString = getString(R.string.error_querying_decoders);
          } else if (decoderInitializationException.secureDecoderRequired) {
            errorString =
                    getString(
                            R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
          } else {
            errorString =
                    getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
          }
        } else {
          errorString =
                  getString(
                          R.string.error_instantiating_decoder,
                          decoderInitializationException.codecInfo.name);
        }
      }
      return Pair.create(0, errorString);
    }
  }

  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

}
