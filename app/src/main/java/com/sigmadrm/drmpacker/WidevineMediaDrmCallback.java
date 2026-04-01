package com.sigmadrm.drmpacker;

import static androidx.media3.exoplayer.drm.DrmUtil.executePost;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest;
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest;
import androidx.media3.exoplayer.drm.MediaDrmCallback;
import androidx.media3.exoplayer.drm.MediaDrmCallbackException;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.primitives.Bytes;

import com.sigma.packer.RequestInfo;
import com.sigma.packer.SigmaDrmPacker;

/**
 * A {@link MediaDrmCallback} that makes requests using {@link DataSource}
 * instances.
 */
@UnstableApi
public final class WidevineMediaDrmCallback implements MediaDrmCallback {
    private final DataSource.Factory dataSourceFactory;
    private final String defaultLicenseUrl;
    private final boolean forceDefaultLicenseUrl;
    private final Map<String, String> keyRequestProperties;

    /**
     * @param defaultLicenseUrl The default license URL. Used for key requests that
     *                          do not specify
     *                          their own license URL.
     * @param dataSourceFactory A factory from which to obtain {@link DataSource}
     *                          instances.
     */
    public WidevineMediaDrmCallback(String defaultLicenseUrl, DataSource.Factory dataSourceFactory) {
        this(defaultLicenseUrl, false, dataSourceFactory);
    }

    /**
     * @param defaultLicenseUrl      The default license URL. Used for key requests
     *                               that do not specify
     *                               their own license URL, or for all key requests
     *                               if {@code forceDefaultLicenseUrl} is
     *                               set to true.
     * @param forceDefaultLicenseUrl Whether to use {@code defaultLicenseUrl} for
     *                               key requests that
     *                               include their own license URL.
     * @param dataSourceFactory      A factory from which to obtain
     *                               {@link DataSource} instances.
     */
    public WidevineMediaDrmCallback(String defaultLicenseUrl, boolean forceDefaultLicenseUrl,
            DataSource.Factory dataSourceFactory) {
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

    // Wrapping into a RuntimeException is recommended by the JSONException docs:
    // https://developer.android.com/reference/org/json/JSONException
    @SuppressWarnings("ThrowSpecificExceptions")
    @NonNull
    @Override
    public Response executeProvisionRequest(@NonNull UUID uuid, ProvisionRequest request)
            throws MediaDrmCallbackException {
        byte[] httpBody = Bytes.concat(
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

    @NonNull
    @Override
    public Response executeKeyRequest(@NonNull UUID uuid, @NonNull KeyRequest request)
            throws MediaDrmCallbackException {
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
            Response response = executePost(
                    dataSourceFactory.createDataSource(),
                    url,
                    request.getData(),
                    requestProperties);
            JSONObject jsonObject = new JSONObject(new String(response.data));
            String licenseEncrypted = jsonObject.getString("license");
            return new Response(Base64.decode(licenseEncrypted, Base64.DEFAULT));
        } catch (MediaDrmCallbackException e) {
            throw e;
        } catch (Exception e) {
            throw new MediaDrmCallbackException(
                    new DataSpec.Builder().setUri(Uri.EMPTY).build(),
                    Uri.EMPTY,
                    /* responseHeaders= */ ImmutableMap.of(),
                    /* bytesLoaded= */ 0,
                    /* cause= */ new IllegalStateException("Error while parsing response", e));
        }
    }

    private String getCustomData(KeyRequest keyRequest) throws Exception {
        JSONObject customData = new JSONObject();
        customData.put("merchantId", "sctv");
        customData.put("appId", "RedTV");
        customData.put("userId", "your_user_id");
        customData.put("sessionId", "your_session_id");

        RequestInfo requestInfo = SigmaDrmPacker.requestInfo(keyRequest.getData());
        customData.put("reqId", requestInfo.requestId);
        customData.put("deviceInfo", requestInfo.deviceInfo);

        String customHeader = Base64.encodeToString(customData.toString().getBytes(), Base64.NO_WRAP);
        Log.e("SIGMA", "Custom data " + customHeader);
        return customHeader;
    }
}
