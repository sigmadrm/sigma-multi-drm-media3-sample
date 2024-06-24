package com.sigmadrm.drmpacker;

import android.annotation.TargetApi;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest;
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest;
import androidx.media3.exoplayer.drm.MediaDrmCallback;
import androidx.media3.exoplayer.drm.MediaDrmCallbackException;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.sigma.packer.RequestInfo;
import com.sigma.packer.SigmaDrmPacker;

/**
 * A {@link MediaDrmCallback} that makes requests using {@link HttpDataSource} instances.
 */
@UnstableApi
@TargetApi(18)
public final class WidevineMediaDrmCallback implements MediaDrmCallback {

  private final HttpDataSource.Factory dataSourceFactory;
  private final String defaultLicenseUrl;
  private final boolean forceDefaultLicenseUrl;
  private final Map<String, String> keyRequestProperties;

  /**
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *                          their own license URL.
   * @param dataSourceFactory A factory from which to obtain {@link HttpDataSource} instances.
   */
  public WidevineMediaDrmCallback(String defaultLicenseUrl, HttpDataSource.Factory dataSourceFactory) {
    this(defaultLicenseUrl, false, dataSourceFactory);
  }

  /**
   * @param defaultLicenseUrl      The default license URL. Used for key requests that do not specify
   *                               their own license URL, or for all key requests if {@code forceDefaultLicenseUrl} is
   *                               set to true.
   * @param forceDefaultLicenseUrl Whether to use {@code defaultLicenseUrl} for key requests that
   *                               include their own license URL.
   * @param dataSourceFactory      A factory from which to obtain {@link HttpDataSource} instances.
   */
  public WidevineMediaDrmCallback(String defaultLicenseUrl, boolean forceDefaultLicenseUrl,
                                  HttpDataSource.Factory dataSourceFactory) {
    this.dataSourceFactory = dataSourceFactory;
    this.defaultLicenseUrl = defaultLicenseUrl;
    this.forceDefaultLicenseUrl = forceDefaultLicenseUrl;
    this.keyRequestProperties = new HashMap<>();
  }

  /**
   * Sets a header for key requests made by the callback.
   *
   * @param name  The name of the header field.
   * @param value The value of the field.
   */
  public void setKeyRequestProperty(String name, String value) {
    Assertions.checkNotNull(name);
    Assertions.checkNotNull(value);
    synchronized (keyRequestProperties) {
      keyRequestProperties.put(name, value);
    }
  }

  /**
   * Clears a header for key requests made by the callback.
   *
   * @param name The name of the header field.
   */
  public void clearKeyRequestProperty(String name) {
    Assertions.checkNotNull(name);
    synchronized (keyRequestProperties) {
      keyRequestProperties.remove(name);
    }
  }

  /**
   * Clears all headers for key requests made by the callback.
   */
  public void clearAllKeyRequestProperties() {
    synchronized (keyRequestProperties) {
      keyRequestProperties.clear();
    }
  }

  @NonNull
  @Override
  public byte[] executeProvisionRequest(@NonNull UUID uuid, ProvisionRequest request) throws MediaDrmCallbackException {
    String url =
            request.getDefaultUrl() + "&signedRequest=" + Util.fromUtf8Bytes(request.getData());
    return executePost(dataSourceFactory, url, Util.EMPTY_BYTE_ARRAY, null);
  }

  @NonNull
  @Override
  public byte[] executeKeyRequest(@NonNull UUID uuid, @NonNull KeyRequest request) throws MediaDrmCallbackException {
    try {
      String url = request.getLicenseServerUrl();
      if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
        url = defaultLicenseUrl;
      }
      Map<String, String> requestProperties = new HashMap<>();
      // Add standard request properties for supported schemes.
      String contentType = "application/octet-stream";
      requestProperties.put("Content-Type", contentType);
      requestProperties.put("custom-data", getCustomData(request));

      // Add additional request properties.
      synchronized (keyRequestProperties) {
        requestProperties.putAll(keyRequestProperties);
      }
      byte[] bytes = executePost(dataSourceFactory, url, request.getData(), requestProperties);
      JSONObject jsonObject = new JSONObject(new String(bytes));
      String licenseEncrypted = jsonObject.getString("license");
      return Base64.decode(licenseEncrypted, Base64.DEFAULT);
    } catch (Exception e) {
      throw new RuntimeException("Error while parsing response", e);
    }
  }

  private static byte[] executePost(HttpDataSource.Factory dataSourceFactory, String url,
                                    byte[] data, Map<String, String> requestProperties) throws MediaDrmCallbackException {
    HttpDataSource dataSource = dataSourceFactory.createDataSource();
    if (requestProperties != null) {
      for (Map.Entry<String, String> requestProperty : requestProperties.entrySet()) {
        dataSource.setRequestProperty(requestProperty.getKey(), requestProperty.getValue());
      }
    }

    while (true) {
      DataSpec dataSpec =
              new DataSpec(
                      Uri.parse(url),
                      data,
                      /* absoluteStreamPosition= */ 0,
                      /* position= */ 0,
                      /* length= */ C.LENGTH_UNSET,
                      /* key= */ null,
                      DataSpec.FLAG_ALLOW_GZIP);
      DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
      try {
        return Util.toByteArray(inputStream);
      } catch (Exception e) {
        throw new MediaDrmCallbackException(
                dataSpec,
                Uri.parse(url),
                dataSource.getResponseHeaders(),
                inputStream.bytesRead(),
                e);
      } finally {
        Util.closeQuietly(inputStream);
      }
    }
  }

  private String getCustomData(KeyRequest keyRequest) throws Exception {
    JSONObject customData = new JSONObject();

    customData.put("merchantId", "sctv");
    customData.put("appId", "RedTV");
    customData.put("userId", "media3_userId_12346");
    customData.put("sessionId", "media3_sessionId_12355");

    RequestInfo requestInfo = SigmaDrmPacker.requestInfo(keyRequest.getData());
    customData.put("reqId", requestInfo.requestId);
    customData.put("deviceInfo", requestInfo.deviceInfo);

    String customHeader = Base64.encodeToString(customData.toString().getBytes(), Base64.NO_WRAP);
    Log.e("Custom Data: ", customHeader);
    return customHeader;
  }
}
