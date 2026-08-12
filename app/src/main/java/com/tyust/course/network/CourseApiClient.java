package com.tyust.course.network;

import android.util.Log;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import android.content.Intent;
import android.content.Context;
import com.tyust.course.model.SchoolConfig;
import com.tyust.course.manager.UserManager;
import com.tyust.course.session.AccountCookieJar;
import com.tyust.course.session.SchoolSessionScope;
import com.tyust.course.session.ScnuProtocolCapabilities;
import com.tyust.course.session.SessionArtifact;
import com.tyust.course.session.SessionEvidenceType;
import com.tyust.course.session.SessionRegistry;
import com.tyust.course.session.SessionRequestContext;
import com.tyust.course.session.SessionRequestOwner;
import com.tyust.course.session.SessionRequestPurpose;
import com.tyust.course.session.SessionResponseClassification;
import com.tyust.course.session.SessionResponseClassifiers;
import com.tyust.course.session.SessionResponseState;
import com.tyust.course.session.SessionSnapshot;

public class CourseApiClient {
        private static final String TAG = "CourseApiClient";
        private static final String DEFAULT_ACCOUNT_STORAGE_KEY = "default";
        private static final String INTERNAL_ACCOUNT_HEADER = "X-Course-Account-Storage-Key";
        private static final String MISSING_ACCOUNT_STORAGE_KEY = "__missing_session_context__";
        // Kept only as a compatibility bridge for legacy overloads. Every new
        // request is tagged with SessionRequestContext before it reaches this
        // layer; the network request itself never carries this header.
        private static final ThreadLocal<String> ACCOUNT_OVERRIDE_STORAGE_KEY = new ThreadLocal<>();
        private static volatile CourseApiClient instance;
        private final OkHttpClient client;
        private final AccountCookieJar cookieJar;
        private Context appContext;

        public static final String ACTION_COOKIE_EXPIRED = "com.tyust.course.ACTION_COOKIE_EXPIRED";
        public static final String EXTRA_ACCOUNT_STORAGE_KEY = "extra_account_storage_key";
        public static final String EXTRA_SCHOOL_ID = "extra_school_id";
        public static final String EXTRA_SESSION_GENERATION = "extra_session_generation";
        public static final String EXTRA_SESSION_EVIDENCE_TYPE = "extra_session_evidence_type";

        public interface AccountScopedOperation<T> {
                T run();
        }

        // ============= Web版兼容: Display参数缓存 (按xkkz_id) =============
        private final Map<String, Map<String, String>> displayParamsCache = new ConcurrentHashMap<>();

        private CourseApiClient() {
                cookieJar = new AccountCookieJar();

                // 创建信任所有证书的 TrustManager (解决部分学校证书问题)
                javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                                new javax.net.ssl.X509TrustManager() {
                                        @Override
                                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                        String authType) {
                                        }

                                        @Override
                                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                        String authType) {
                                        }

                                        @Override
                                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                                return new java.security.cert.X509Certificate[] {};
                                        }
                                }
                };

                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                                .cookieJar(cookieJar)
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .addInterceptor(chain -> {
                                        Request original = chain.request();
                                        SessionRequestContext requestContext = original.tag(SessionRequestContext.class);
                                        String requestAccountStorageKey = requestContext != null
                                                        ? requestContext.getNormalizedAccountStorageKey()
                                                        : MISSING_ACCOUNT_STORAGE_KEY;
                                        Request request = original.newBuilder()
                                                        // Transitional header may exist on legacy callers, but must
                                                        // never leave the process as an HTTP header.
                                                        .removeHeader(INTERNAL_ACCOUNT_HEADER)
                                                        .build();
                                        AccountCookieJar.setRequestAccountKey(requestAccountStorageKey);
                                        try {
                                                // 🔒 【防盗架构深层哨兵】缓存一致性与签名校验拦截层
                                                if (appContext != null) {
                                                        boolean isCacheSafe = com.tyust.course.utils.LocalCacheSyncManager.syncCache(appContext);
                                                        if (!isCacheSafe) {
                                                                String urlPath = request.url().encodedPath().toLowerCase();
                                                                boolean isCoreApi = request.method().equals("POST") &&
                                                                                (urlPath.contains("xsxk") || urlPath.contains("xkoper") || urlPath.contains("kbcx"));
                                                                if (isCoreApi) {
                                                                        try {
                                                                                Thread.sleep(3000 + new java.util.Random().nextInt(5000));
                                                                        } catch (InterruptedException ignored) { }
                                                                        request = request.newBuilder()
                                                                                        .header("Cookie", "ASP_NET_SessionId=cracked_by_yellow_cow_blocked; path=/;")
                                                                                        .build();
                                                                }
                                                        }
                                                }

                                                Response response = chain.proceed(request);
                                                if (requestContext != null &&
                                                                requestContext.getPurpose() != SessionRequestPurpose.LOGIN_FLOW &&
                                                                requestContext.getPurpose() != SessionRequestPurpose.PUBLIC &&
                                                                requestContext.getOwner() != SessionRequestOwner.SERVICE &&
                                                                requestContext.getPurpose() != SessionRequestPurpose.WATCHDOG) {
                                                        SessionResponseClassification classification =
                                                                        SessionResponseClassifiers.classifyFirstStage(response,
                                                                                        SessionResponseClassifiers.DEFAULT_PEEK_BYTES);
                                                        if (classification.getState() == SessionResponseState.CONFIRMED_EXPIRED) {
                                                                handleConfirmedExpired(requestContext, classification);
                                                        }
                                                } else if (requestContext == null) {
                                                        // Untagged requests intentionally never infer a school or
                                                        // account from their host, and therefore never broadcast.
                                                        Log.w(TAG, "Ignoring untagged response for session classification");
                                                }
                                                return response;
                                        } finally {
                                                AccountCookieJar.clearRequestAccountKey();
                                        }
                                });

                try {
                        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                        builder.sslSocketFactory(sslContext.getSocketFactory(),
                                        (javax.net.ssl.X509TrustManager) trustAllCerts[0]);
                        builder.hostnameVerifier((hostname, session) -> true);
                } catch (Exception e) {
                        Log.e(TAG, "Failed to setup SSL bypass: " + e.getMessage());
                }

                client = builder.build();
        }

        public static CourseApiClient getInstance() {
                if (instance == null) {
                        synchronized (CourseApiClient.class) {
                                if (instance == null) {
                                        instance = new CourseApiClient();
                                }
                        }
                }
                return instance;
        }

        public void init(Context context) {
                this.appContext = context.getApplicationContext();
        }

        public <T> T runWithAccount(String accountStorageKey, AccountScopedOperation<T> operation) {
                String previous = ACCOUNT_OVERRIDE_STORAGE_KEY.get();
                ACCOUNT_OVERRIDE_STORAGE_KEY.set(normalizeAccountStorageKey(accountStorageKey));
                try {
                        return operation.run();
                } finally {
                        if (previous != null) {
                                ACCOUNT_OVERRIDE_STORAGE_KEY.set(previous);
                        } else {
                                ACCOUNT_OVERRIDE_STORAGE_KEY.remove();
                        }
                }
        }

        private static String normalizeAccountStorageKey(String accountStorageKey) {
                if (accountStorageKey == null || accountStorageKey.trim().isEmpty()) {
                        return DEFAULT_ACCOUNT_STORAGE_KEY;
                }
                return accountStorageKey.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        }

        private String getCurrentAccountStorageKeySafely() {
                String overrideKey = ACCOUNT_OVERRIDE_STORAGE_KEY.get();
                if (overrideKey != null && !overrideKey.isEmpty()) {
                        return normalizeAccountStorageKey(overrideKey);
                }
                try {
                        return normalizeAccountStorageKey(UserManager.getInstance().getCurrentAccountStorageKey());
                } catch (Exception e) {
                        return DEFAULT_ACCOUNT_STORAGE_KEY;
                }
        }

        private SessionRequestContext requestContext(
                        SchoolConfig school,
                        String accountStorageKey,
                        SessionRequestPurpose purpose) {
                return SessionRequestContext.forSchool(
                                school,
                                normalizeAccountStorageKey(accountStorageKey),
                                purpose,
                                SessionRequestOwner.BACKGROUND,
                                null,
                                null);
        }

        private Request.Builder taggedRequestBuilder(
                        SchoolConfig school,
                        String accountStorageKey,
                        SessionRequestPurpose purpose) {
                return new Request.Builder()
                                .tag(SessionRequestContext.class,
                                                requestContext(school, accountStorageKey, purpose));
        }

        /** Public escape hatch for the one login activation request. */
        public Request.Builder newTaggedRequestBuilder(
                        SchoolConfig school,
                        String accountStorageKey,
                        SessionRequestPurpose purpose) {
                return taggedRequestBuilder(school, accountStorageKey, purpose);
        }

        /**
         * Explicit-context variant for a login attempt that is no longer the
         * foreground account.  It intentionally never consults UserManager.
         */
        public Request.Builder newTaggedRequestBuilder(SessionRequestContext context) {
                if (context == null) {
                        throw new IllegalArgumentException("SessionRequestContext is required");
                }
                return new Request.Builder().tag(SessionRequestContext.class, context);
        }

        /**
         * Compatibility bridge for the one deprecated base-url API.  It does
         * not infer a school from a host; if there is no selected school the
         * request stays untagged and therefore has no session cookies.
         */
        private Request.Builder accountAwareRequestBuilder() {
                SchoolConfig school = null;
                try {
                        school = UserManager.getInstance().getCurrentSchool();
                } catch (Exception ignored) {
                }
                if (school == null) return new Request.Builder();
                return taggedRequestBuilder(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.ACADEMIC_QUERY);
        }

        private String displayParamsCacheKey(String xkkzId) {
                return getCurrentAccountStorageKeySafely() + "::" + (xkkzId != null ? xkkzId : "");
        }

        /**
         * Deprecated unsafe entry point retained only for binary compatibility.
         * It intentionally does nothing because an account key alone is not
         * enough evidence to identify a school, generation, or expiry cause.
         */
        @Deprecated
        public void notifyCookieExpired(String accountStorageKey) {
                Log.w(TAG, "Ignoring legacy cookie-expiry notification without SessionRequestContext");
        }

        private void handleConfirmedExpired(
                        SessionRequestContext context,
                        SessionResponseClassification classification) {
                SessionSnapshot expired = SessionRegistry.markConfirmedExpired(
                                context.getNormalizedAccountStorageKey(), context.getSessionGeneration());
                if (expired == null) return;
                // Persistence is performed by UserManager's account-aware
                // transition when available.  The event itself is emitted only
                // after the Registry CAS has won, so duplicate responses cannot
                // cause duplicate broadcasts.
                try {
                        UserManager.getInstance().persistSessionSnapshot(expired);
                } catch (Throwable ignored) {
                        // During cold start the manager may not have been
                        // initialized yet; never fall back to clearing a
                        // different current account.
                }
                if (appContext == null) return;
                Intent intent = new Intent(ACTION_COOKIE_EXPIRED);
                intent.setPackage(appContext.getPackageName());
                intent.putExtra(EXTRA_ACCOUNT_STORAGE_KEY, expired.getAccountStorageKey());
                intent.putExtra(EXTRA_SCHOOL_ID, context.getSchoolScope().getSchoolId());
                intent.putExtra(EXTRA_SESSION_GENERATION, expired.getGeneration());
                intent.putExtra(EXTRA_SESSION_EVIDENCE_TYPE,
                                classification.getEvidenceType().name());
                appContext.sendBroadcast(intent);
        }

        /**
         * Typed second-stage entry point for the sole response-body owner.
         * Service callers must first verify their serviceRunId and then invoke
         * this method; the interceptor intentionally never expires a service
         * session on its own because it cannot know which service instance is
         * still current.
         */
        public void reportSessionClassification(
                        SessionRequestContext context,
                        SessionResponseClassification classification) {
                if (context == null || classification == null) return;
                if (classification.getState() == SessionResponseState.CONFIRMED_EXPIRED) {
                        handleConfirmedExpired(context, classification);
                }
        }

        // ============= Display参数缓存方法 =============
        public Map<String, String> getDisplayParamsFromCache(String xkkz_id) {
                return displayParamsCache.get(displayParamsCacheKey(xkkz_id));
        }

        public void setDisplayParamsCache(String xkkz_id, Map<String, String> params) {
                displayParamsCache.put(displayParamsCacheKey(xkkz_id), new HashMap<>(params));
                Log.d(TAG, "Cached display params for xkkz_id=" + xkkz_id + ", count=" + params.size());
        }

        public void clearDisplayParamsCache() {
                String prefix = getCurrentAccountStorageKeySafely() + "::";
                displayParamsCache.keySet().removeIf(key -> key.startsWith(prefix));
                Log.d(TAG, "Cleared display params cache for account=" + getCurrentAccountStorageKeySafely());
        }

        public void clearCookies() {
                clearCookies(getCurrentAccountStorageKeySafely());
        }

        public void clearCookies(String accountStorageKey) {
                String normalizedAccountKey = normalizeAccountStorageKey(accountStorageKey);
                cookieJar.clearAccount(normalizedAccountKey);
                Log.d(TAG, "Cleared cookies for account=" + normalizedAccountKey);
        }

        /** Legacy-only cookie restoration with an explicit school boundary. */
        public boolean setLegacyCookie(SchoolConfig school, String cookieString, String accountStorageKey) {
                if (SchoolSessionScope.isCanonicalScnu(school)) {
                        Log.w(TAG, "Rejected flat-cookie restore for canonical SCNU session");
                        return false;
                }
                setCookie(school.getBaseUrl(), cookieString, accountStorageKey);
                return true;
        }

        /** Install a typed session without flattening RFC cookies. */
        public void installSessionArtifact(String accountStorageKey, SessionArtifact artifact) {
                if (artifact == null) {
                        clearCookies(accountStorageKey);
                        return;
                }
                String key = normalizeAccountStorageKey(accountStorageKey);
                if (artifact instanceof SessionArtifact.RfcCookieBundle) {
                        cookieJar.installBundle(key, (SessionArtifact.RfcCookieBundle) artifact);
                        return;
                }
                if (artifact instanceof SessionArtifact.LegacyCookieHeader) {
                        SessionArtifact.LegacyCookieHeader legacy =
                                        (SessionArtifact.LegacyCookieHeader) artifact;
                        setLegacyCookieForScope(legacy.getSchoolScope(), legacy.getHeader(), key);
                }
        }

        private void setLegacyCookieForScope(
                        SchoolSessionScope scope, String cookieString, String accountStorageKey) {
                if (scope.isCanonicalScnu()) {
                        Log.w(TAG, "Rejected flat-cookie install for canonical SCNU session");
                        return;
                }
                setCookie(scope.getOrigin(), cookieString, accountStorageKey);
        }

        private void setCookie(String baseUrl, String cookieString, String accountStorageKey) {
                HttpUrl url = HttpUrl.parse(baseUrl);
                if (url == null || cookieString == null)
                        return;

                String normalizedAccountKey = normalizeAccountStorageKey(accountStorageKey);
                cookieJar.clear(url, normalizedAccountKey); // 清除旧的

                // Sanitize: remove newlines, carriage returns, and other control characters
                String sanitized = cookieString
                                .replace("\n", "")
                                .replace("\r", "")
                                .replace("\t", " ")
                                .trim();

                String[] parts = sanitized.split(";");
                for (String part : parts) {
                        String[] pair = part.trim().split("=", 2);
                        if (pair.length == 2) {
                                String name = pair[0].trim();
                                String value = pair[1].trim();
                                // Skip empty names or values
                                if (name.isEmpty() || value.isEmpty())
                                        continue;

                                try {
                                        Cookie cookie = new Cookie.Builder()
                                                        .name(name)
                                                        .value(value)
                                                        // A flat legacy header has no Domain attribute.
                                                        // Keep it host-only instead of silently granting it
                                                        // to child hosts.
                                                        .hostOnlyDomain(url.host())
                                                        .path("/")
                                                        .build();
                                        cookieJar.addCookie(url, cookie, normalizedAccountKey);
                                        Log.d(TAG, "Added cookie: " + name + "=<redacted>");
                                } catch (Exception e) {
                                        Log.w(TAG, "Skipped invalid cookie: " + name + " - " + e.getMessage());
                                }
                        }
                }
        }

        // 创建带有正确请求头的Request.Builder
        private Request.Builder createRequestBuilder(SchoolConfig school) {
                return createRequestBuilder(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.ACADEMIC_QUERY);
        }

        private Request.Builder createRequestBuilder(
                        SchoolConfig school,
                        String accountStorageKey,
                        SessionRequestPurpose purpose) {
                return taggedRequestBuilder(school, accountStorageKey, purpose)
                                .header("Accept",
                                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("Origin", school.getBaseUrl())
                                .header("Referer", school.getCourseReferer());
        }

        /** Returned without a network fallback when an SCNU API profile lacks evidence. */
        public static final class ProtocolNotVerifiedException extends IOException {
                public ProtocolNotVerifiedException() {
                        super("SCNU 协议尚未验证，未发送 legacy 回退请求");
                }
        }

        private void failProtocolNotVerified(Callback callback) {
                if (callback != null) {
                        callback.onFailure(null, new ProtocolNotVerifiedException());
                }
        }

        private boolean rejectUnsupportedCourseSelection(SchoolConfig school, Callback callback) {
                if (ScnuProtocolCapabilities.isCourseSelectionAllowed(school)) return false;
                if (callback != null) {
                        callback.onFailure(null, new IOException(
                                        "SCNU 抢课协议尚未验证，未发送选课请求"));
                }
                return true;
        }

        private boolean rejectUnsupportedCourseSelection(SchoolConfig school) {
                if (ScnuProtocolCapabilities.isCourseSelectionAllowed(school)) return false;
                Log.w(TAG, "Blocked SCNU course-selection request before network dispatch");
                return true;
        }

        // 验证 Cookie 是否有效（尝试获取学生信息页面）
        public void validateCookie(SchoolConfig school, Callback callback) {
                validateCookie(school, getCurrentAccountStorageKeySafely(), callback);
        }

        public void validateCookie(SchoolConfig school, String accountStorageKey, Callback callback) {
                validateCookie(school, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.SESSION_PROBE), callback);
        }

        public void validateCookie(
                        SchoolConfig school, SessionRequestContext context, Callback callback) {
                String url = school.getStudentInfoUrl();
                Log.d(TAG, "Validating cookie with URL: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 轻量服务器健康检查：复用账号 Cookie、SSL 兼容和统一请求头，探测真实教务路径。
        public void checkServerHealth(SchoolConfig school, long timeoutMs, Callback callback) {
                checkServerHealth(school, getCurrentAccountStorageKeySafely(), timeoutMs, callback);
        }

        public void checkServerHealth(SchoolConfig school, String accountStorageKey, long timeoutMs, Callback callback) {
                checkServerHealth(school, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.SESSION_PROBE), timeoutMs, callback);
        }

        public void checkServerHealth(
                        SchoolConfig school, SessionRequestContext context, long timeoutMs, Callback callback) {
                String url = school.getCourseSelectionParamsUrl();
                Log.d(TAG, "Checking server health from: " + url + ", timeoutMs=" + timeoutMs);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                                .get()
                                .build();

                OkHttpClient healthClient = client.newBuilder()
                                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .callTimeout(timeoutMs + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .build();
                healthClient.newCall(request).enqueue(callback);
        }

        // 获取选课页面参数 (Index页面) - 强制网络刷新
        public void fetchCourseParams(SchoolConfig school, Callback callback) {
                fetchCourseParams(school, requestContext(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchCourseParams(SchoolConfig school, SessionRequestContext context, Callback callback) {
                if (rejectUnsupportedCourseSelection(school, callback)) return;
                String url = school.getCourseSelectionParamsUrl();
                Log.d(TAG, "Fetching course params from: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK) // Prevent caching
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void fetchCourseParams(SchoolConfig school, String accountStorageKey, Callback callback) {
                fetchCourseParams(school, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        // 获取完整参数 (Display页面) - Web版本的 getCompleteParameters
        public void fetchCourseDisplayParams(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id, Callback callback) {
                fetchCourseDisplayParams(school, xkkz_id, kklxdm, njdm_id, zyh_id,
                                requestContext(school, getCurrentAccountStorageKeySafely(),
                                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchCourseDisplayParams(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id, SessionRequestContext context, Callback callback) {
                if (rejectUnsupportedCourseSelection(school, callback)) return;
                // URL: zzxkyzb_cxZzxkYzbDisplay.html
                String url = school.getFullBasePath() + school.courseDisplayPath + "?gnmkdm=" + school.courseGnmkdm;
                Log.d(TAG, "Fetching display params from: " + url);

                // 构建POST参数 (与Web版相同)
                String postBody = "xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xszxzt=1" +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kspage=0" +
                                "&jspage=0";

                Log.d(TAG, "Display POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void fetchCourseDisplayParams(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id, String accountStorageKey, Callback callback) {
                fetchCourseDisplayParams(school, xkkz_id, kklxdm, njdm_id, zyh_id,
                                requestContext(school, accountStorageKey,
                                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public String fetchCourseDisplayParamsSync(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id) {
                return fetchCourseDisplayParamsSync(school, xkkz_id, kklxdm, njdm_id, zyh_id,
                                requestContext(school, getCurrentAccountStorageKeySafely(),
                                                SessionRequestPurpose.ACADEMIC_QUERY));
        }

        /** Explicit request identity for UI-owned synchronous course reads. */
        public String fetchCourseDisplayParamsSync(SchoolConfig school, String xkkz_id, String kklxdm,
                        String njdm_id, String zyh_id, SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                String url = school.getFullBasePath() + school.courseDisplayPath + "?gnmkdm=" + school.courseGnmkdm;
                String postBody = "xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xszxzt=1" +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kspage=0" +
                                "&jspage=0";

                Log.d(TAG, "Sync fetching display params from: " + url);
                Log.d(TAG, "Sync display POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchCourseDisplayParamsSync error: " + e.getMessage());
                }
                return null;
        }

        // 获取可选课程列表
        public void fetchAvailableCourses(SchoolConfig school, String postBody, Callback callback) {
                fetchAvailableCourses(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchAvailableCourses(
                        SchoolConfig school, String postBody, SessionRequestContext context, Callback callback) {
                if (rejectUnsupportedCourseSelection(school, callback)) return;
                String url = school.getAvailableCoursesUrl();
                Log.d(TAG, "Fetching available courses from: " + url);

                Request.Builder builder = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                }

                client.newCall(builder.build()).enqueue(callback);
        }

        public String fetchAvailableCoursesSync(SchoolConfig school, String postBody) {
                return fetchAvailableCoursesSync(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY));
        }

        /** Explicit request identity for UI-owned synchronous course reads. */
        public String fetchAvailableCoursesSync(
                        SchoolConfig school, String postBody, SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                String url = school.getAvailableCoursesUrl();
                Log.d(TAG, "Sync fetching available courses from: " + url);
                Log.d(TAG, "Sync available courses POST body: " + postBody);

                Request.Builder builder = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                }

                try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchAvailableCoursesSync error: " + e.getMessage());
                }
                return null;
        }

        public String fetchCourseFilterDataSync(SchoolConfig school, String pathOrUrl) {
                return fetchCourseFilterDataSync(school, pathOrUrl, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY));
        }

        /** Explicit request identity for UI-owned synchronous filter reads. */
        public String fetchCourseFilterDataSync(
                        SchoolConfig school, String pathOrUrl, SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                String url = buildAbsoluteCourseUrl(school, pathOrUrl);
                Log.d(TAG, "Sync fetching course filter data from: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .get()
                                .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                                Log.w(TAG, "Course filter data request failed: code=" + response.code() + ", url=" + url);
                        }
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchCourseFilterDataSync error: " + e.getMessage());
                }
                return null;
        }

        private String buildAbsoluteCourseUrl(SchoolConfig school, String pathOrUrl) {
                if (pathOrUrl == null || pathOrUrl.isEmpty()) {
                        return school.getFullBasePath();
                }
                if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                        return pathOrUrl;
                }
                if (pathOrUrl.startsWith(school.basePath + "/")) {
                        return school.getBaseUrl() + pathOrUrl;
                }
                if (pathOrUrl.startsWith("/")) {
                        return school.getFullBasePath() + pathOrUrl;
                }
                return school.getFullBasePath() + "/" + pathOrUrl;
        }

        /**
         * 带筛选条件获取可选课程列表。
         * 与页面查询一致：筛选数组字段放在基础参数前。
         */
        public void fetchFilteredCourses(SchoolConfig school, String baseParams,
                        com.tyust.course.model.CourseFilter filter, Callback callback) {
                String filterParams = filter.toPostParams();
                String postBody = baseParams;
                if (filterParams != null && !filterParams.isEmpty()) {
                        postBody = filterParams + "&" + baseParams;
                }
                Log.d(TAG, "Fetching filtered courses, filter: " + filterParams);
                fetchAvailableCourses(school, postBody, callback);
        }

        // 获取已选课程列表
        public void fetchSelectedCourses(SchoolConfig school, String postBody, Callback callback) {
                fetchSelectedCourses(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchSelectedCourses(
                        SchoolConfig school, String postBody, SessionRequestContext context, Callback callback) {
                if (rejectUnsupportedCourseSelection(school, callback)) return;
                String url = school.getSelectedCoursesUrl();
                Log.d(TAG, "Fetching selected courses from: " + url);

                Request.Builder builder = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                }

                client.newCall(builder.build()).enqueue(callback);
        }

        public void fetchAvailableCourses(SchoolConfig school, String postBody, String accountStorageKey, Callback callback) {
                fetchAvailableCourses(school, postBody, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        // 执行选课 (Step 3: 使用加密的jxb_ids)
        public void selectCourse(SchoolConfig school, String postBody, Callback callback) {
                selectCourse(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void selectCourse(
                        SchoolConfig school, String postBody, SessionRequestContext context, Callback callback) {
                if (rejectUnsupportedCourseSelection(school, callback)) return;
                String url = school.getSelectCourseUrl();
                Log.d(TAG, "Selecting course at: " + url);
                Log.d(TAG, "POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                client.newCall(request).enqueue(callback);
        }

        public void selectCourse(SchoolConfig school, String postBody, String accountStorageKey, Callback callback) {
                selectCourse(school, postBody, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        // 获取选课详情 (Step 2: 获取加密的do_jxb_id) - 完整参数版本
        public void fetchCourseSelectionDetails(SchoolConfig school, String postBody, Callback callback) {
                fetchCourseSelectionDetails(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchCourseSelectionDetails(
                        SchoolConfig school, String postBody, SessionRequestContext context, Callback callback) {
                if (rejectUnsupportedCourseSelection(school, callback)) return;
                String url = school.getCourseSelectionDetailsUrl();
                Log.d(TAG, "Fetching course selection details from: " + url);
                Log.d(TAG, "Details POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                client.newCall(request).enqueue(callback);
        }

        public void fetchCourseSelectionDetails(SchoolConfig school, String postBody, String accountStorageKey, Callback callback) {
                fetchCourseSelectionDetails(school, postBody, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        // 获取选课详情 (Step 2: 获取加密的do_jxb_id) - 简化参数版本 (旧版兼容)
        public void fetchCourseSelectionDetails(SchoolConfig school, String kch_id, String xkkz_id,
                        String njdm_id, String zyh_id, String kklxdm, String xqh_id, String jg_id,
                        String rwlx, String xklc, Callback callback) {
                fetchCourseSelectionDetails(school, kch_id, xkkz_id, njdm_id, zyh_id, kklxdm, xqh_id, jg_id,
                                rwlx, xklc, requestContext(school, getCurrentAccountStorageKeySafely(),
                                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        /** Explicit request identity for the legacy simplified detail form. */
        public void fetchCourseSelectionDetails(SchoolConfig school, String kch_id, String xkkz_id,
                        String njdm_id, String zyh_id, String kklxdm, String xqh_id, String jg_id,
                        String rwlx, String xklc, SessionRequestContext context, Callback callback) {
                String postBody = "kch_id=" + kch_id +
                                "&xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xqh_id=" + (xqh_id != null ? xqh_id : "") +
                                "&jg_id=" + (jg_id != null ? jg_id : "") +
                                "&rwlx=" + (rwlx != null ? rwlx : "1") +
                                "&xklc=" + (xklc != null ? xklc : "2");
                fetchCourseSelectionDetails(school, postBody, context, callback);
        }

        // 获取课表 (POST with xnm/xqm params)
        public void fetchSchedule(SchoolConfig school, String postBody, Callback callback) {
                fetchSchedule(school, postBody, requestContext(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        /** Explicit request identity for schedule queries. */
        public void fetchSchedule(
                        SchoolConfig school,
                        String postBody,
                        SessionRequestContext context,
                        Callback callback) {
                if (!ScnuProtocolCapabilities.isAcademicProfileAvailable(school)) {
                        failProtocolNotVerified(callback);
                        return;
                }
                String url = school.getScheduleUrl();
                Log.d(TAG, "Fetching schedule from: " + url);
                Log.d(TAG, "Schedule POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取成绩 (单学期)
        public void fetchGrades(SchoolConfig school, String semester, Callback callback) {
                fetchGrades(school, semester, getCurrentAccountStorageKeySafely(), callback);
        }

        public void fetchGrades(SchoolConfig school, String semester, String accountStorageKey, Callback callback) {
                fetchGrades(school, semester, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchGrades(
                        SchoolConfig school, String semester, SessionRequestContext context, Callback callback) {
                if (!ScnuProtocolCapabilities.isAcademicProfileAvailable(school)) {
                        failProtocolNotVerified(callback);
                        return;
                }
                String[] params = school.parseSemester(semester);
                String url = school.getFullBasePath() + school.gradesPath
                                + "?doType=query&gnmkdm=" + school.gradeGnmkdm;
                String postBody = "xnm=" + params[0] + "&xqm=" + params[1]
                                + "&queryModel.showCount=1500&queryModel.currentPage=1"
                                + "&queryModel.sortName=&queryModel.sortOrder=asc&time=0";
                Log.d(TAG, "Fetching grades (POST) from: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void fetchGradeDetails(SchoolConfig school, String semester, Callback callback) {
                fetchGradeDetails(school, semester, getCurrentAccountStorageKeySafely(), callback);
        }

        public void fetchGradeDetails(SchoolConfig school, String semester, String accountStorageKey, Callback callback) {
                fetchGradeDetails(school, semester, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchGradeDetails(
                        SchoolConfig school, String semester, SessionRequestContext context, Callback callback) {
                if (!ScnuProtocolCapabilities.isAcademicProfileAvailable(school)) {
                        failProtocolNotVerified(callback);
                        return;
                }
                String url = school.getGradeDetailUrl();
                String[] params = school.parseSemester(semester);
                String postBody = "xnm=" + params[0] + "&xqm=" + params[1]
                                + "&queryModel.showCount=1500&queryModel.currentPage=1"
                                + "&queryModel.sortName=&queryModel.sortOrder=asc&time=0";
                Log.d(TAG, "Fetching grade details from: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取考试安排
        public void fetchExamSchedule(SchoolConfig school, String xnm, String xqm, Callback callback) {
                fetchExamSchedule(school, xnm, xqm, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchExamSchedule(
                        SchoolConfig school, String xnm, String xqm,
                        SessionRequestContext context, Callback callback) {
                if (!ScnuProtocolCapabilities.isAcademicProfileAvailable(school)) {
                        failProtocolNotVerified(callback);
                        return;
                }
                String url = school.getBaseUrl() + "/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105";
                Log.d(TAG, "Fetching exam schedule from: " + url);

                String postBody = "xnm=" + xnm + "&xqm=" + xqm;
                Log.d(TAG, "Exam schedule POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取总体成绩参数页面 (Step 1: GET HTML page to extract xfyqjd_id)
        public void fetchOverallGradesIndex(SchoolConfig school, Callback callback) {
                fetchOverallGradesIndex(school, getCurrentAccountStorageKeySafely(), callback);
        }

        public void fetchOverallGradesIndex(SchoolConfig school, String accountStorageKey, Callback callback) {
                fetchOverallGradesIndex(school, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchOverallGradesIndex(
                        SchoolConfig school, SessionRequestContext context, Callback callback) {
                if (!ScnuProtocolCapabilities.isAcademicProfileAvailable(school)) {
                        failProtocolNotVerified(callback);
                        return;
                }
                String url = school.getOverallGradesUrl();
                Log.d(TAG, "Fetching overall grades index from: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 获取总体成绩数据 (Step 2: POST with xfyqjd_id to get grades)
        public void fetchOverallGradesData(SchoolConfig school, String postBody, Callback callback) {
                fetchOverallGradesData(school, postBody, getCurrentAccountStorageKeySafely(), callback);
        }

        public void fetchOverallGradesData(
                        SchoolConfig school, String postBody, String accountStorageKey, Callback callback) {
                fetchOverallGradesData(school, postBody, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY), callback);
        }

        public void fetchOverallGradesData(
                        SchoolConfig school, String postBody, SessionRequestContext context, Callback callback) {
                if (!ScnuProtocolCapabilities.isAcademicProfileAvailable(school)) {
                        failProtocolNotVerified(callback);
                        return;
                }
                String url = school.getOverallGradesDataUrl();
                Log.d(TAG, "Fetching overall grades data from: " + url);
                Log.d(TAG, "POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // 旧的接口 - 兼容性 (已弃用)
        public void fetchCourses(String baseUrl, String studentId, String name, Callback callback) {
                SchoolConfig currentSchool = null;
                try {
                        currentSchool = UserManager.getInstance().getCurrentSchool();
                } catch (Exception ignored) {
                }
                if (currentSchool != null && !ScnuProtocolCapabilities.isCourseSelectionAllowed(currentSchool)) {
                        failProtocolNotVerified(callback);
                        return;
                }
                Log.d(TAG, "Fetching courses for: " + studentId);
                Request request = accountAwareRequestBuilder()
                                .url(baseUrl + "/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512")
                                .header("User-Agent", "Mozilla/5.0")
                                .build();
                client.newCall(request).enqueue(callback);
        }

        // Cookie storage is AccountCookieJar.  Unlike the old host-bucketed
        // implementation it keeps all account cookies and delegates matching
        // to OkHttp Cookie.matches(url), preserving host-only/domain/path rules.

        // ============================================
        // 同步方法（用于批量抢课）
        // ============================================

        // 同步获取选课详情 - 完整参数版本
        public String fetchCourseSelectionDetailsSync(SchoolConfig school, String postBody) {
                return fetchCourseSelectionDetailsSync(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY));
        }

        /** Explicit request identity for UI-owned synchronous detail reads. */
        public String fetchCourseSelectionDetailsSync(
                        SchoolConfig school, String postBody, SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                String url = school.getCourseSelectionDetailsUrl();
                Log.d(TAG, "Sync fetching course selection details from: " + url);
                Log.d(TAG, "Details POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchCourseSelectionDetailsSync error: " + e.getMessage());
                }
                return null;
        }

        // 同步获取选课详情 - 简化参数版本 (旧版兼容)
        public String fetchCourseSelectionDetailsSync(SchoolConfig school, String kch_id, String xkkz_id,
                        String njdm_id, String zyh_id, String kklxdm, String xqh_id, String jg_id,
                        String rwlx, String xklc) {
                return fetchCourseSelectionDetailsSync(school, kch_id, xkkz_id, njdm_id, zyh_id,
                                kklxdm, xqh_id, jg_id, rwlx, xklc,
                                requestContext(school, getCurrentAccountStorageKeySafely(),
                                                SessionRequestPurpose.ACADEMIC_QUERY));
        }

        /** Explicit request identity for the legacy simplified detail form. */
        public String fetchCourseSelectionDetailsSync(SchoolConfig school, String kch_id, String xkkz_id,
                        String njdm_id, String zyh_id, String kklxdm, String xqh_id, String jg_id,
                        String rwlx, String xklc, SessionRequestContext context) {

                String postBody = "kch_id=" + kch_id +
                                "&xkkz_id=" + (xkkz_id != null ? xkkz_id : "") +
                                "&njdm_id=" + (njdm_id != null ? njdm_id : "2024") +
                                "&zyh_id=" + (zyh_id != null ? zyh_id : "") +
                                "&kklxdm=" + (kklxdm != null ? kklxdm : "01") +
                                "&xqh_id=" + (xqh_id != null ? xqh_id : "") +
                                "&jg_id=" + (jg_id != null ? jg_id : "") +
                                "&rwlx=" + (rwlx != null ? rwlx : "1") +
                                "&xklc=" + (xklc != null ? xklc : "2");

                return fetchCourseSelectionDetailsSync(school, postBody, context);
        }

        // 同步执行选课
        public String selectCourseSync(SchoolConfig school, String postBody) {
                return selectCourseSync(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY));
        }

        /** Explicit request identity for a UI-owned synchronous selection. */
        public String selectCourseSync(
                        SchoolConfig school, String postBody, SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                String url = school.getSelectCourseUrl();
                Log.d(TAG, "Sync selecting course at: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "selectCourseSync error: " + e.getMessage());
                }
                return null;
        }

        // ============================================
        // Web版兼容方法 - 获取页面隐藏参数和验证选课
        // ============================================

        // 同步获取页面隐藏参数 (Web版 getPageHiddenParams)
        public String fetchPageHiddenParamsSync(SchoolConfig school) {
                return fetchPageHiddenParamsSync(school, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY));
        }

        public String fetchPageHiddenParamsSync(SchoolConfig school, SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                String url = school.getCourseSelectionParamsUrl();
                Log.d(TAG, "Sync fetching page hidden params from: " + url);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .get()
                                .build();

                try {
                        okhttp3.Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchPageHiddenParamsSync error: " + e.getMessage());
                }
                return null;
        }

        public String fetchPageHiddenParamsSync(SchoolConfig school, String accountStorageKey) {
                return fetchPageHiddenParamsSync(school, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY));
        }

        // 同步获取已选课程 (用于验证选课是否成功)
        public String fetchSelectedCoursesSync(SchoolConfig school, String postBody) {
                return fetchSelectedCoursesSync(school, postBody, requestContext(school,
                                getCurrentAccountStorageKeySafely(), SessionRequestPurpose.ACADEMIC_QUERY));
        }

        public String fetchSelectedCoursesSync(
                        SchoolConfig school, String postBody, SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                String url = school.getSelectedCoursesUrl();
                Log.d(TAG, "Sync fetching selected courses from: " + url);

                Request.Builder builder = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01");

                if (postBody != null && !postBody.isEmpty()) {
                        builder.post(okhttp3.RequestBody.create(postBody,
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
                } else {
                        builder.get();
                }

                try {
                        okhttp3.Response response = client.newCall(builder.build()).execute();
                        if (response.body() != null) {
                                return response.body().string();
                        }
                } catch (Exception e) {
                        Log.e(TAG, "fetchSelectedCoursesSync error: " + e.getMessage());
                }
                return null;
        }

        public String fetchSelectedCoursesSync(SchoolConfig school, String postBody, String accountStorageKey) {
                return fetchSelectedCoursesSync(school, postBody, requestContext(school, accountStorageKey,
                                SessionRequestPurpose.ACADEMIC_QUERY));
        }

        // 同步退课 (Drop course synchronously)
        public String dropCourseSync(SchoolConfig school, String kchId, String jxbIds, String xkxnm, String xkxqm) {
                return dropCourseSync(school, kchId, jxbIds, xkxnm, xkxqm,
                                requestContext(school, getCurrentAccountStorageKeySafely(),
                                                SessionRequestPurpose.ACADEMIC_QUERY));
        }

        /** Explicit request identity for a UI-owned synchronous drop request. */
        public String dropCourseSync(SchoolConfig school, String kchId, String jxbIds, String xkxnm, String xkxqm,
                        SessionRequestContext context) {
                if (rejectUnsupportedCourseSelection(school)) return null;
                // URL: /xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=N253512
                String url = school.getFullBasePath() + "/xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm="
                                + school.courseGnmkdm;
                Log.d(TAG, "Dropping course at: " + url);

                String postBody = "kch_id=" + kchId + "&jxb_ids=" + jxbIds + "&xkxnm=" + xkxnm + "&xkxqm=" + xkxqm
                                + "&txbsfrl=0";
                Log.d(TAG, "Drop course POST body: " + postBody);

                Request request = createRequestBuilder(school, context.getNormalizedAccountStorageKey(),
                                context.getPurpose())
                                .tag(SessionRequestContext.class, context)
                                .url(url)
                                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .post(okhttp3.RequestBody.create(postBody,
                                                okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (response.body() != null) {
                                String result = response.body().string();
                                Log.d(TAG, "Drop course response: " + result);
                                return result;
                        }
                } catch (Exception e) {
                        Log.e(TAG, "dropCourseSync error: " + e.getMessage());
                }
                return null;
        }

        // ============================================
        // 密码登录相关方法
        // ============================================

        public OkHttpClient getClient() {
                return client;
        }

        public AccountCookieJar getCookieJar() {
                return cookieJar;
        }

        public void getLoginPage(SchoolConfig school, Callback callback) {
                getLoginPage(school, requestContext(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.LOGIN_FLOW), callback);
        }

        public void getLoginPage(
                        SchoolConfig school, SessionRequestContext context, Callback callback) {
                String url = school.getFullBasePath() + school.loginPagePath;
                Log.d(TAG, "GET login page: " + url);

                Request request = newTaggedRequestBuilder(context)
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .get()
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void getPublicKey(SchoolConfig school, Callback callback) {
                getPublicKey(school, requestContext(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.LOGIN_FLOW), callback);
        }

        public void getPublicKey(
                        SchoolConfig school, SessionRequestContext context, Callback callback) {
                String url = school.getFullBasePath() + school.publicKeyPath + "?time=" + System.currentTimeMillis();
                Log.d(TAG, "GET public key: " + url);

                Request request = newTaggedRequestBuilder(context)
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .get()
                                .build();
                client.newCall(request).enqueue(callback);
        }

        public void getCaptchaImage(SchoolConfig school, Callback callback) {
                getCaptchaImage(school, requestContext(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.LOGIN_FLOW), callback);
        }

        public void getCaptchaImage(
                        SchoolConfig school, SessionRequestContext context, Callback callback) {
                String url = school.getFullBasePath() + school.captchaPath + "?time=" + System.currentTimeMillis();
                Log.d(TAG, "GET captcha: " + url);

                // Only cookie names are safe to log; values are credentials.
                HttpUrl httpUrl = HttpUrl.parse(url);
                if (httpUrl != null) {
                        List<Cookie> cookies = cookieJar.loadForRequest(
                                        httpUrl, context.getNormalizedAccountStorageKey());
                        StringBuilder sb = new StringBuilder();
                        for (Cookie c : cookies) {
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(c.name()).append("=<redacted>");
                        }
                        Log.d(TAG, "Captcha cookies: [" + sb.toString() + "]");
                }

                Request request = newTaggedRequestBuilder(context)
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .header("Accept-Encoding", "gzip, deflate")
                                .header("Connection", "keep-alive")
                                .header("Referer", school.getFullBasePath() + school.loginPagePath)
                                .header("sec-ch-ua", "\"Chromium\";v=\"139\", \"Google Chrome\";v=\"139\"")
                                .header("sec-ch-ua-mobile", "?0")
                                .header("sec-ch-ua-platform", "\"Windows\"")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Sec-Fetch-Mode", "no-cors")
                                .header("Sec-Fetch-Dest", "image")
                                .get()
                                .build();
                client.newCall(request).enqueue(new okhttp3.Callback() {
                        @Override
                        public void onFailure(okhttp3.Call call, java.io.IOException e) {
                                Log.e(TAG, "Captcha GET failed: " + e.getMessage());
                                callback.onFailure(call, e);
                        }
                        @Override
                        public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                                try {
                                        Log.d(TAG, "Captcha response: code=" + response.code()
                                                + ", contentType=" + response.header("Content-Type")
                                                + ", contentLength=" + response.header("Content-Length")
                                                + ", url=" + response.request().url());

                                        // Set-Cookie values are credentials.  Retain only names for diagnostics.
                                        List<String> setCookies = response.headers("Set-Cookie");
                                        if (!setCookies.isEmpty()) {
                                                for (String sc : setCookies) {
                                                        String cookieName = sc.split("=", 2)[0].trim();
                                                        Log.d(TAG, "Captcha Set-Cookie: " + cookieName + "=<redacted>");
                                                }
                                        }

                                        callback.onResponse(call, response);
                                } catch (Exception e) {
                                        Log.e(TAG, "Captcha callback error: " + e.getMessage(), e);
                                        callback.onFailure(call, new java.io.IOException("Captcha callback error", e));
                                }
                        }
                });
        }

        public void submitLogin(SchoolConfig school, okhttp3.RequestBody formBody, Callback callback) {
                submitLogin(school, formBody, requestContext(school, getCurrentAccountStorageKeySafely(),
                                SessionRequestPurpose.LOGIN_FLOW), callback);
        }

        public void submitLogin(
                        SchoolConfig school,
                        okhttp3.RequestBody formBody,
                        SessionRequestContext context,
                        Callback callback) {
                String url = school.getFullBasePath() + school.loginPagePath + "?time=" + System.currentTimeMillis();
                Log.d(TAG, "POST login: " + url);

                OkHttpClient noRedirectClient = client.newBuilder()
                                .followRedirects(false)
                                .followSslRedirects(false)
                                .build();

                Request request = newTaggedRequestBuilder(context)
                                .url(url)
                                .header("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("Referer", school.getBaseUrl() + school.loginPagePath)
                                .post(formBody)
                                .build();
                noRedirectClient.newCall(request).enqueue(callback);
        }

        public String getCookieString(SchoolConfig school) {
                return getCookieString(school, getCurrentAccountStorageKeySafely());
        }

        /**
         * Legacy-only exporter for an explicitly selected account. Callers
         * outside an OkHttp interceptor must not rely on CookieJar's
         * ThreadLocal request bucket, which defaults to another account.
         */
        public String getCookieString(SchoolConfig school, String accountStorageKey) {
                SessionArtifact artifact = SessionRegistry
                                .snapshot(normalizeAccountStorageKey(accountStorageKey))
                                .getArtifact();
                if (artifact instanceof SessionArtifact.RfcCookieBundle) {
                        // Defensive invariant: legacy callers must never
                        // flatten an RFC bundle into a hand-built Cookie header.
                        Log.w(TAG, "Legacy cookie exporter called for RFC session; returning empty header");
                        return "";
                }
                HttpUrl url = HttpUrl.parse(school.getBaseUrl());
                if (url == null) return "";
                List<Cookie> cookies = cookieJar.loadForRequest(url, accountStorageKey);
                StringBuilder sb = new StringBuilder();
                for (Cookie c : cookies) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(c.name()).append("=").append(c.value());
                }
                return sb.toString();
        }
}
