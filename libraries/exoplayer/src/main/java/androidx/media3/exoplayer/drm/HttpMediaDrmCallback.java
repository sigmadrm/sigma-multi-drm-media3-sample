/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.drm;

import static androidx.media3.exoplayer.drm.DrmUtil.executePost;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest;
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.primitives.Bytes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A {@link MediaDrmCallback} that makes requests using {@link DataSource} instances. */
@UnstableApi
public final class HttpMediaDrmCallback implements MediaDrmCallback {
  private final DataSource.Factory dataSourceFactory;
  @Nullable private final String defaultLicenseUrl;
  private final boolean forceDefaultLicenseUrl;
  private final Map<String, String> keyRequestProperties;

  /**
   * Constructs an instance.
   *
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL. May be {@code null} if it's known that all key requests will specify
   *     their own URLs.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     usually be an HTTP-based {@link DataSource}.
   */
  public HttpMediaDrmCallback(
      @Nullable String defaultLicenseUrl, DataSource.Factory dataSourceFactory) {
    this(defaultLicenseUrl, /* forceDefaultLicenseUrl= */ false, dataSourceFactory);
  }

  /**
   * Constructs an instance.
   *
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL, or for all key requests if {@code forceDefaultLicenseUrl} is set to
   *     true. May be {@code null} if {@code forceDefaultLicenseUrl} is {@code false} and if it's
   *     known that all key requests will specify their own URLs.
   * @param forceDefaultLicenseUrl Whether to force use of {@code defaultLicenseUrl} for key
   *     requests that include their own license URL.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     * usually be an HTTP-based {@link DataSource}.
   */
  public HttpMediaDrmCallback(
      @Nullable String defaultLicenseUrl,
      boolean forceDefaultLicenseUrl,
      DataSource.Factory dataSourceFactory) {
    checkArgument(!(forceDefaultLicenseUrl && TextUtils.isEmpty(defaultLicenseUrl)));
    this.dataSourceFactory = dataSourceFactory;
    this.defaultLicenseUrl = defaultLicenseUrl;
    this.forceDefaultLicenseUrl = forceDefaultLicenseUrl;
    this.keyRequestProperties = new HashMap<>();
  }

  /**
   * Sets a header for key requests made by the callback.
   *
   * @param name The name of the header field.
   * @param value The value of the field.
   */
  public void setKeyRequestProperty(String name, String value) {
    checkNotNull(name);
    checkNotNull(value);
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
    checkNotNull(name);
    synchronized (keyRequestProperties) {
      keyRequestProperties.remove(name);
    }
  }

  /** Clears all headers for key requests made by the callback. */
  public void clearAllKeyRequestProperties() {
    synchronized (keyRequestProperties) {
      keyRequestProperties.clear();
    }
  }

  // Wrapping into a RuntimeException is recommended by the JSONException docs:
  // https://developer.android.com/reference/org/json/JSONException
  @SuppressWarnings("ThrowSpecificExceptions")
  @Override
  public Response executeProvisionRequest(UUID uuid, ProvisionRequest request)
      throws MediaDrmCallbackException {
    byte[] httpBody =
        Bytes.concat(
            "{\"signedRequest\":\"".getBytes(UTF_8), request.getData(), "\"}".getBytes(UTF_8));
    return executePost(
        dataSourceFactory.createDataSource(),
        request.getDefaultUrl(),
        httpBody,
        ImmutableMap.of(
            HttpHeaders.CONTENT_TYPE,
            MediaType.JSON_UTF_8.toString(),
            HttpHeaders.CONTENT_LENGTH,
            String.valueOf(httpBody.length)));
  }

  @Override
  public Response executeKeyRequest(UUID uuid, KeyRequest request)
      throws MediaDrmCallbackException {
    String url = request.getLicenseServerUrl();
    if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
      url = defaultLicenseUrl;
    }
    if (TextUtils.isEmpty(url)) {
      throw new MediaDrmCallbackException(
          new DataSpec.Builder().setUri(Uri.EMPTY).build(),
          Uri.EMPTY,
          /* responseHeaders= */ ImmutableMap.of(),
          /* bytesLoaded= */ 0,
          /* cause= */ new IllegalStateException("No license URL"));
    }
    Map<String, String> requestProperties = new HashMap<>();
    // Add standard request properties for supported schemes.
    String contentType =
        C.PLAYREADY_UUID.equals(uuid)
            ? "text/xml"
            : (C.CLEARKEY_UUID.equals(uuid) ? "application/json" : "application/octet-stream");
    requestProperties.put("Content-Type", contentType);
    if (C.PLAYREADY_UUID.equals(uuid)) {
      requestProperties.put(
          "SOAPAction", "http://schemas.microsoft.com/DRM/2007/03/protocols/AcquireLicense");
    }
    // Add additional request properties.
    synchronized (keyRequestProperties) {
      requestProperties.putAll(keyRequestProperties);
    }
    // Gửi thông tin về App UI để hiển thị Log
    try {
        android.util.Log.d("DRM_DEBUG", ">>> SENDING REQUEST to: " + url);
        sendBroadcastLog("[NET] Requesting License...");
    } catch (Exception ignored) {}

    MediaDrmCallback.Response response;
    try {
        response = executePost(
            dataSourceFactory.createDataSource(),
            url,
            /* httpBody= */ request.getData(),
            requestProperties);
        sendBroadcastLog("[NET] Response 200 OK");
    } catch (Exception e) {
        sendBroadcastLog("[NET] Request Failed: " + e.getMessage());
        throw e;
    }

    // CODE DEBUG: In nội dung License ra Logcat
    android.util.Log.d("DRM_DEBUG", "Server URL: " + url);
    if (response != null && response.data != null) {
        String responseString = new String(response.data);
        android.util.Log.d("DRM_DEBUG", "Server Response: " + responseString);
        
        // MỚI: Kiểm tra nếu là JSON từ SigmaDRM
        if (responseString.trim().startsWith("{")) {
            try {
                JSONObject jsonObject = new JSONObject(responseString);
                if (jsonObject.has("license")) {
                    String licenseBase64 = jsonObject.getString("license");
                    byte[] decodedLicense = Base64.decode(licenseBase64, Base64.DEFAULT);
                    logDebug("JSON detected, license decoded successfully.");
                    
                    // Tạo Response mới với dữ liệu đã giải mã
                    return new MediaDrmCallback.Response.Builder(decodedLicense)
                        .setLoadEventInfo(response.loadEventInfo)
                        .build();
                }
            } catch (JSONException e) {
                logDebug("Error parsing JSON: " + e.getMessage());
            }
        }
    }

    return response;
  }

  private void logDebug(String msg) {
      android.util.Log.d("DRM_DEBUG", msg);
  }

  private void sendBroadcastLog(String msg) {
      try {
          // Lấy context ứng dụng thông qua Reflection để gửi Broadcast từ library
          @SuppressLint("PrivateApi")
          Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
          Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
          android.content.Context context = (android.content.Context) activityThreadClass.getMethod("getApplication").invoke(activityThread);
          
          if (context != null) {
              android.content.Intent intent = new android.content.Intent("SIGMA_DRM_LOG");
              intent.setPackage(context.getPackageName());
              intent.putExtra("message", msg);
              context.sendBroadcast(intent);
          }
      } catch (Exception ignored) {}
  }
}
