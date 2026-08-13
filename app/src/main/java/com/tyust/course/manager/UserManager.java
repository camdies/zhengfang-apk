package com.tyust.course.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.tyust.course.model.SchoolConfig;
import com.tyust.course.model.Course;
import com.tyust.course.network.CourseApiClient;
import com.tyust.course.session.SchoolSessionScope;
import com.tyust.course.session.SessionArtifact;
import com.tyust.course.session.SessionArtifactCodec;
import com.tyust.course.session.SessionInstallResult;
import com.tyust.course.session.SessionInstallTarget;
import com.tyust.course.session.SessionRefreshCoordinator;
import com.tyust.course.session.SessionRegistry;
import com.tyust.course.session.SessionSnapshot;
import com.tyust.course.session.SessionState;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserManager {
    private static final String TAG = "UserManager";
    private static UserManager instance;
    private SchoolConfig currentSchool;
    private String studentName;
    private String studentId;
    private boolean isLoggedIn = false;
    private boolean isDemoMode = false;
    private String savedCookie = "";
    private String sessionPassword = ""; // 内存中保存，用于会话期间自动刷新Cookie，不持久化
    private String currentAccountKey = "";
    private final Map<String, String> sessionPasswords = new HashMap<>();
    /**
     * App-wide coordinator: login UI and the settings refresh action must join
     * the same per-account flight rather than independently replacing a
     * session.
     */
    private final SessionRefreshCoordinator sessionRefreshCoordinator = new SessionRefreshCoordinator();
    private List<Course> selectedCourses = new ArrayList<>();

    private static final String PREFS_NAME = "course_selector_prefs";
    private static final String KEY_CUSTOM_SCHOOLS = "custom_schools";
    private static final String KEY_COOKIE = "saved_cookie";
    private static final String KEY_LOGGED_IN = "is_logged_in";
    private static final String KEY_STUDENT_NAME = "student_name";
    private static final String KEY_STUDENT_ID = "student_id";
    private static final String KEY_CURRENT_SCHOOL_ID = "current_school_id";
    private static final String KEY_COOKIE_SAVE_TIME = "cookie_save_time";
    private static final String KEY_USERNAME = "saved_username";
    private static final String KEY_LOGIN_MODE = "login_mode";
    private static final String KEY_ACCOUNTS = "saved_accounts";
    private static final String KEY_CURRENT_ACCOUNT_KEY = "current_account_key";

    private Context appContext;

    // 默认学校列表
    private final List<SchoolConfig> defaultSchools = new ArrayList<>();
    // 自定义学校列表
    private final List<SchoolConfig> customSchools = new ArrayList<>();

    public static class AccountRecord {
        public String key = "";
        public String schoolId = "";
        public String schoolName = "";
        public String studentName = "";
        public String studentId = "";
        public String username = "";
        public String loginMode = "cookie";
        /** The sole persisted representation for a modern session; never log it. */
        public String sessionArtifactJson = "";
        /** Account-scoped credential generation. */
        public long sessionGeneration = 0L;
        /** Enum name retained in JSON for forward-compatible storage. */
        public String sessionState = SessionState.EMPTY.name();
        public int artifactSchemaVersion = 0;

        /** Compatibility/migration-only header. RFC bundles are never flattened here. */
        public String cookie = "";
        public long cookieSaveTime = 0L;

        public boolean isPasswordMode() {
            return "password".equals(loginMode);
        }

        public String getDisplayName() {
            if (studentName != null && !studentName.isEmpty()) return studentName;
            if (username != null && !username.isEmpty()) return username;
            if (studentId != null && !studentId.isEmpty()) return studentId;
            return "同学";
        }

        public String getAccountIdText() {
            if (studentId != null && !studentId.isEmpty()) return studentId;
            if (username != null && !username.isEmpty()) return username;
            return "未记录学号";
        }
    }

    private UserManager() {
        // 初始化默认学校
        defaultSchools.add(new SchoolConfig("tyust", "太原科技大学", "newjwc.tyust.edu.cn", "https"));
        defaultSchools.add(new SchoolConfig("zjut", "浙江工业大学", "www.gdjw.zjut.edu.cn", "http"));
        SchoolConfig scnu = new SchoolConfig("scnu", "华南师范大学", "jwxt.scnu.edu.cn", "https");
        scnu.basePath = "";
        defaultSchools.add(scnu);
        // 重要修复：这里不要直接赋值 currentSchool，等待 init() 时从 SharedPreferences 加载
    }

    public static synchronized UserManager getInstance() {
        if (instance == null) {
            instance = new UserManager();
        }
        return instance;
    }

    // 初始化 Context（在 Application 或 Activity 中调用）
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        loadCustomSchools();
        loadLoginState(); // 加载保存的登录状态
    }

    public void setCurrentSchool(SchoolConfig school) {
        boolean changed = currentSchool == null
                || school == null
                || !SchoolSessionScope.fromSchool(currentSchool).equals(SchoolSessionScope.fromSchool(school));
        this.currentSchool = school;
        if (changed) {
            SessionRegistry.bumpActiveContextEpoch();
        }
        saveLoginState(); // 保存学校选择
    }

    public SchoolConfig getCurrentSchool() {
        return currentSchool;
    }

    // 获取所有学校（默认 + 自定义）
    public List<SchoolConfig> getSupportedSchools() {
        List<SchoolConfig> all = new ArrayList<>();
        all.addAll(defaultSchools);
        all.addAll(customSchools);
        return all;
    }

    // 根据ID查找学校
    public SchoolConfig getSchoolById(String schoolId) {
        for (SchoolConfig school : getSupportedSchools()) {
            if (school.id.equals(schoolId)) {
                return school;
            }
        }
        return null;
    }

    // 添加自定义学校
    public void addCustomSchool(SchoolConfig school) {
        // 检查是否已存在
        for (SchoolConfig s : getSupportedSchools()) {
            if (s.domain.equals(school.domain)) {
                return; // 已存在，不添加
            }
        }
        customSchools.add(school);
        saveCustomSchools();
    }

    // 删除自定义学校
    public void removeCustomSchool(String schoolId) {
        customSchools.removeIf(s -> s.id.equals(schoolId));
        saveCustomSchools();
    }

    // 更新学校配置
    public void updateSchoolConfig(SchoolConfig updatedSchool) {
        // 更新自定义学校
        for (int i = 0; i < customSchools.size(); i++) {
            if (customSchools.get(i).id.equals(updatedSchool.id)) {
                customSchools.set(i, updatedSchool);
                saveCustomSchools();
                // 同时更新 currentSchool
                if (currentSchool != null && currentSchool.id.equals(updatedSchool.id)) {
                    boolean scopeChanged = !SchoolSessionScope.fromSchool(currentSchool)
                            .equals(SchoolSessionScope.fromSchool(updatedSchool));
                    currentSchool = updatedSchool;
                    if (scopeChanged) SessionRegistry.bumpActiveContextEpoch();
                }
                return;
            }
        }

        // 更新默认学校
        for (int i = 0; i < defaultSchools.size(); i++) {
            if (defaultSchools.get(i).id.equals(updatedSchool.id)) {
                defaultSchools.set(i, updatedSchool);
                // 同时更新 currentSchool
                if (currentSchool != null && currentSchool.id.equals(updatedSchool.id)) {
                    boolean scopeChanged = !SchoolSessionScope.fromSchool(currentSchool)
                            .equals(SchoolSessionScope.fromSchool(updatedSchool));
                    currentSchool = updatedSchool;
                    if (scopeChanged) SessionRegistry.bumpActiveContextEpoch();
                }
                return;
            }
        }
    }

    // 保存自定义学校到 SharedPreferences
    private void saveCustomSchools() {
        if (appContext == null)
            return;

        try {
            JSONArray arr = new JSONArray();
            for (SchoolConfig school : customSchools) {
                arr.put(school.toJson());
            }

            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_CUSTOM_SCHOOLS, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 从 SharedPreferences 加载自定义学校
    private void loadCustomSchools() {
        if (appContext == null)
            return;

        customSchools.clear();
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_CUSTOM_SCHOOLS, "[]");
            Log.d(TAG, "加载自定义学校 JSON: " + json);

            JSONArray arr = new JSONArray(json);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                SchoolConfig school = SchoolConfig.fromJson(obj);
                if (school != null && !school.domain.isEmpty()) {
                    customSchools.add(school);
                    Log.d(TAG, "加载自定义学校: id=" + school.id + ", name=" + school.name);
                }
            }
            Log.d(TAG, "自定义学校加载完成, 共 " + customSchools.size() + " 个");
        } catch (Exception e) {
            Log.e(TAG, "加载自定义学校失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== Cookie、登录状态与账号记录持久化 ==========

    // 保存登录状态到 SharedPreferences
    public void saveLoginState() {
        if (appContext == null)
            return;

        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            editor.putBoolean(KEY_LOGGED_IN, isLoggedIn);
            editor.putString(KEY_STUDENT_NAME, studentName != null ? studentName : "");
            editor.putString(KEY_STUDENT_ID, studentId != null ? studentId : "");
            editor.putString(KEY_COOKIE, savedCookie != null ? savedCookie : "");
            editor.putLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());
            if (currentAccountKey != null && !currentAccountKey.isEmpty()) {
                editor.putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey);
            }

            if (currentSchool != null) {
                editor.putString(KEY_CURRENT_SCHOOL_ID, currentSchool.id);
            }

            editor.apply();

            if (isLoggedIn && currentSchool != null && hasRestorableCurrentSession()) {
                upsertCurrentAccountRecord();
            }

            Log.d(TAG, "登录状态已保存: isLoggedIn=" + isLoggedIn);
        } catch (Exception e) {
            Log.e(TAG, "保存登录状态失败: " + e.getMessage());
        }
    }

    // 从 SharedPreferences 加载登录状态
    public void loadLoginState() {
        if (appContext == null)
            return;

        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            isLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false);
            studentName = prefs.getString(KEY_STUDENT_NAME, "");
            studentId = prefs.getString(KEY_STUDENT_ID, "");
            savedCookie = prefs.getString(KEY_COOKIE, "");

            String schoolId = prefs.getString(KEY_CURRENT_SCHOOL_ID, "");
            Log.d(TAG, "正在恢复学校, 保存的 schoolId=" + schoolId);

            if (!schoolId.isEmpty()) {
                SchoolConfig school = getSchoolById(schoolId);
                if (school != null) {
                    currentSchool = school;
                    Log.d(TAG, "学校恢复成功: " + currentSchool.name);
                } else {
                    Log.w(TAG, "找不到保存的学校 ID: " + schoolId + ", 可用学校: " + listSchoolIds());
                    // 保持默认学校不变
                }
            } else {
                Log.d(TAG, "没有保存的学校 ID，使用默认学校");
            }

            migrateLegacyAccountIfNeeded(prefs);

            currentAccountKey = prefs.getString(KEY_CURRENT_ACCOUNT_KEY, "");
            if (!currentAccountKey.isEmpty()) {
                AccountRecord record = findAccountRecord(currentAccountKey);
                if (record != null) {
                    applyAccountRecord(record, false);
                } else {
                    // The old flat preference cannot establish a session on its
                    // own after the account-scoped migration has run.
                    isLoggedIn = false;
                    savedCookie = "";
                }
            } else {
                isLoggedIn = false;
                savedCookie = "";
            }

            Log.d(TAG, "登录状态已加载: isLoggedIn=" + isLoggedIn + ", school="
                    + (currentSchool != null ? currentSchool.name : "null"));
        } catch (Exception e) {
            Log.e(TAG, "加载登录状态失败: " + e.getMessage());
        }
    }

    // 辅助方法：列出所有可用学校 ID（用于调试）
    private String listSchoolIds() {
        StringBuilder sb = new StringBuilder();
        for (SchoolConfig s : getSupportedSchools()) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(s.id);
        }
        return sb.toString();
    }

    // 保存 Cookie。默认只更新当前会话 Cookie，不改变登录模式。
    public void saveCookie(String cookie) {
        installCurrentLegacyCookie(cookie, true);
    }

    public void saveCookieLogin(String cookie) {
        this.sessionPassword = "";
        currentAccountKey = buildAccountKey(currentSchool, "", studentId, studentName);
        ensureCurrentAccountKey();
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LOGIN_MODE, "cookie")
                    .remove(KEY_USERNAME)
                    .apply();
        }
        installCurrentLegacyCookie(cookie, true);
    }

    public void savePasswordLogin(String username, String cookie, String password) {
        this.sessionPassword = password != null ? password : "";
        String key = buildAccountKey(currentSchool, username, studentId, studentName);
        if (!key.isEmpty()) {
            currentAccountKey = key;
            if (!this.sessionPassword.isEmpty()) {
                sessionPasswords.put(key, this.sessionPassword);
            }
        }
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_USERNAME, username != null ? username : "")
                    .putString(KEY_LOGIN_MODE, "password")
                    .apply();
        }
        installCurrentLegacyCookie(cookie, true);
    }

    /** 获取会话期间保存的密码（仅内存，不持久化） */
    public String getSessionPassword() {
        if (currentAccountKey != null && !currentAccountKey.isEmpty() && sessionPasswords.containsKey(currentAccountKey)) {
            return sessionPasswords.get(currentAccountKey);
        }
        return sessionPassword != null ? sessionPassword : "";
    }

    /**
     * Returns the one process-wide typed login coordinator. LoginActivity and
     * SettingsRoute therefore join the same per-account single-flight rather
     * than racing two password logins.
     */
    public SessionRefreshCoordinator getSessionRefreshCoordinator() {
        return sessionRefreshCoordinator;
    }

    /**
     * Establishes the exact account identity before a password-login target is
     * created. This is in-memory only; preferences and the in-memory password
     * are committed after a typed artifact installs successfully.
     */
    public String preparePasswordLoginAccount(SchoolConfig school, String username) {
        String normalizedUsername = username != null ? username.trim() : "";
        if (school == null || normalizedUsername.isEmpty() || currentSchool == null) {
            return "";
        }
        SchoolSessionScope requestedScope = SchoolSessionScope.fromSchool(school);
        if (!requestedScope.equals(SchoolSessionScope.fromSchool(currentSchool))) {
            return "";
        }
        String accountKey = buildAccountKey(school, normalizedUsername, "", "");
        if (accountKey.isEmpty()) {
            return "";
        }
        if (!accountKey.equals(currentAccountKey)) {
            currentAccountKey = accountKey;
            SessionRegistry.bumpActiveContextEpoch();
        }
        return getCurrentAccountStorageKey();
    }

    /**
     * Commits password-login metadata after the coordinator has installed an
     * active typed artifact. Password values remain in process memory only.
     */
    public boolean completePasswordLogin(SessionInstallTarget target, String username, String password) {
        if (target == null || currentSchool == null) {
            return false;
        }
        String normalizedUsername = username != null ? username.trim() : "";
        if (normalizedUsername.isEmpty()
                || !SchoolSessionScope.fromSchool(currentSchool).equals(target.getSchoolScope())
                || !SessionRegistry.normalizeAccountKey(getCurrentAccountStorageKey())
                        .equals(SessionRegistry.normalizeAccountKey(target.getAccountStorageKey()))) {
            return false;
        }

        SessionSnapshot snapshot = SessionRegistry.snapshot(target.getAccountStorageKey());
        if (snapshot.getState() != SessionState.ACTIVE || snapshot.getArtifact() == null) {
            return false;
        }

        currentAccountKey = buildAccountKey(currentSchool, normalizedUsername, "", "");
        sessionPassword = password != null ? password : "";
        if (!sessionPassword.isEmpty()) {
            sessionPasswords.put(currentAccountKey, sessionPassword);
        } else {
            sessionPasswords.remove(currentAccountKey);
        }
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_USERNAME, normalizedUsername)
                    .putString(KEY_LOGIN_MODE, "password")
                    .putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey)
                    .apply();
        }
        isLoggedIn = true;
        upsertCurrentAccountRecord();
        saveLoginState();
        return true;
    }

    /** 是否可以通过密码模式自动刷新 Cookie */
    public boolean canAutoRelogin() {
        return "password".equals(getLoginMode())
                && !getUsername().isEmpty()
                && !getSessionPassword().isEmpty()
                && currentSchool != null;
    }

    public String getUsername() {
        if (appContext == null) return "";
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getLoginMode() {
        if (appContext == null) return "cookie";
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LOGIN_MODE, "cookie");
    }

    // 获取已保存的 Cookie
    public String getSavedCookie() {
        return savedCookie != null ? savedCookie : "";
    }

    // 检查是否有保存的 Cookie
    public boolean hasSavedCookie() {
        if (currentSchool == null || SchoolSessionScope.isCanonicalScnu(currentSchool)) {
            return false;
        }
        AccountRecord record = findAccountRecord(getCurrentAccountKey());
        if (record != null) {
            SessionArtifact artifact = decodeArtifact(record);
            return hasRestorableSession(currentSchool, record)
                    && artifact instanceof SessionArtifact.LegacyCookieHeader;
        }
        // Compatibility for the brief interval before an old flat preference
        // is migrated into its account-scoped record.
        return savedCookie != null && !savedCookie.isEmpty();
    }

    private void migrateLegacyAccountIfNeeded(SharedPreferences prefs) {
        try {
            List<AccountRecord> accounts = loadAccountRecords();
            boolean changed = false;

            // First migrate every persisted account independently.  A new global
            // login must never make us discard older accounts from another school.
            for (AccountRecord record : accounts) {
                changed |= migrateLegacyRecordIfNeeded(record);
            }

            // Then fold the pre-account-list global preference into its own
            // record, even when other saved accounts already exist.
            if (currentSchool != null && savedCookie != null && !savedCookie.isEmpty()) {
                String username = prefs.getString(KEY_USERNAME, "");
                String key = buildOrDefaultAccountKey(currentSchool, username, studentId, studentName);
                AccountRecord record = findAccountRecord(accounts, key);
                if (record == null) {
                    record = new AccountRecord();
                    record.key = key;
                    record.schoolId = currentSchool.id;
                    record.schoolName = currentSchool.name;
                    record.studentName = studentName != null ? studentName : "";
                    record.studentId = studentId != null ? studentId : "";
                    record.username = username;
                    record.loginMode = prefs.getString(KEY_LOGIN_MODE, "cookie");
                    record.cookie = savedCookie;
                    record.cookieSaveTime = prefs.getLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());
                    accounts.add(record);
                    changed = true;
                } else if ((record.cookie == null || record.cookie.isEmpty())
                        && record.sessionArtifactJson.isEmpty()) {
                    record.cookie = savedCookie;
                    record.cookieSaveTime = prefs.getLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());
                    changed = true;
                }
                changed |= migrateLegacyRecordIfNeeded(record);
                currentAccountKey = record.key;
                prefs.edit().putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey).apply();
            }

            if (changed) {
                saveAccountRecords(accounts);
            }
        } catch (Exception e) {
            Log.w(TAG, "迁移旧版账号状态失败: " + e.getMessage());
        }
    }

    private static String buildAccountKey(SchoolConfig school, String username, String studentId, String studentName) {
        if (school == null || school.id == null || school.id.isEmpty()) return "";
        String identity = firstNotBlank(username, studentId, studentName);
        if (identity.isEmpty()) return "";
        return school.id + "::" + identity.trim();
    }

    /**
     * The legacy single-account preferences did not always have an identity.
     * Preserve those records under a deterministic key instead of losing their
     * display metadata during the one-time migration.
     */
    private static String buildOrDefaultAccountKey(SchoolConfig school, String username, String studentId, String studentName) {
        String key = buildAccountKey(school, username, studentId, studentName);
        if (!key.isEmpty()) return key;
        if (school == null || school.id == null || school.id.isEmpty()) return "";
        return school.id + "::default";
    }

    private void ensureCurrentAccountKey() {
        if (currentAccountKey == null || currentAccountKey.isEmpty()) {
            currentAccountKey = buildOrDefaultAccountKey(currentSchool, getUsername(), studentId, studentName);
        }
    }

    private AccountRecord findAccountRecord(List<AccountRecord> records, String accountKey) {
        if (records == null || accountKey == null || accountKey.isEmpty()) return null;
        for (AccountRecord record : records) {
            if (accountKey.equals(record.key)) return record;
        }
        return null;
    }

    /**
     * Converts a pre-Artifact record without creating an RFC bundle from a
     * flattened header.  Canonical SCNU is deliberately left without a
     * restorable artifact and must be logged in again after protocol discovery.
     */
    private boolean migrateLegacyRecordIfNeeded(AccountRecord record) {
        return migrateLegacyAccountRecord(record, record != null ? getSchoolById(record.schoolId) : null);
    }

    /**
     * Pure account-record migration used by SharedPreferences loading and JVM
     * tests. It is intentionally idempotent: the first migration assigns a
     * starting generation, later reads leave that generation unchanged.
     */
    public static boolean migrateLegacyAccountRecord(AccountRecord record, SchoolConfig school) {
        if (record == null || school == null) return false;
        boolean changed = false;

        if (record.key == null || record.key.isEmpty()) {
            record.key = buildOrDefaultAccountKey(school, record.username, record.studentId, record.studentName);
            changed = !record.key.isEmpty();
        }

        String artifactJson = record.sessionArtifactJson != null ? record.sessionArtifactJson : "";
        if (artifactJson.isEmpty()) {
            if (record.cookie != null && !record.cookie.isEmpty()) {
                if (SchoolSessionScope.isCanonicalScnu(school)) {
                    // Keep the account metadata (and the old compatibility field)
                    // but do not invent a Cookie bundle or restore this header.
                    if (!SessionState.EMPTY.name().equals(record.sessionState)) {
                        record.sessionState = SessionState.EMPTY.name();
                        changed = true;
                    }
                    if (record.artifactSchemaVersion != 0) {
                        record.artifactSchemaVersion = 0;
                        changed = true;
                    }
                } else {
                    SessionArtifact.LegacyCookieHeader artifact = new SessionArtifact.LegacyCookieHeader(
                            SchoolSessionScope.fromSchool(school),
                            record.cookie,
                            record.cookieSaveTime > 0L ? record.cookieSaveTime : System.currentTimeMillis(),
                            SessionArtifact.CURRENT_SCHEMA_VERSION
                    );
                    record.sessionArtifactJson = SessionArtifactCodec.encode(artifact);
                    record.artifactSchemaVersion = artifact.getSchemaVersion();
                    record.sessionGeneration = Math.max(1L, record.sessionGeneration);
                    record.sessionState = isKnownNonEmptySessionState(record.sessionState)
                            ? record.sessionState
                            : SessionState.INACTIVE.name();
                    changed = true;
                }
            }
            return changed;
        }

        SessionArtifact artifact = SessionArtifactCodec.decode(artifactJson);
        if (artifact == null) {
            // A damaged payload is never promoted to a usable session.  Leave
            // it untouched so a later schema migration cannot silently discard
            // account metadata or overwrite a newer writer's record.
            return changed;
        }

        if (artifact instanceof SessionArtifact.LegacyCookieHeader
                && SchoolSessionScope.isCanonicalScnu(school)) {
            // A prior build might have persisted a canonical-SCNU header.  It
            // is not a legal RFC session and must never remain restorable.
            record.sessionArtifactJson = "";
            record.artifactSchemaVersion = 0;
            record.sessionState = SessionState.EMPTY.name();
            changed = true;
            return changed;
        }

        if (record.artifactSchemaVersion == 0) {
            record.artifactSchemaVersion = artifact.getSchemaVersion();
            changed = true;
        }
        if (record.sessionGeneration <= 0L) {
            record.sessionGeneration = 1L;
            changed = true;
        }
        if (!isKnownNonEmptySessionState(record.sessionState)) {
            record.sessionState = SessionState.INACTIVE.name();
            changed = true;
        }
        return changed;
    }

    private static boolean isKnownNonEmptySessionState(String value) {
        return SessionState.ACTIVE.name().equals(value)
                || SessionState.INACTIVE.name().equals(value)
                || SessionState.EXPIRED.name().equals(value);
    }

    private static String firstNotBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private JSONObject accountToJson(AccountRecord record) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("key", record.key != null ? record.key : "");
        obj.put("schoolId", record.schoolId != null ? record.schoolId : "");
        obj.put("schoolName", record.schoolName != null ? record.schoolName : "");
        obj.put("studentName", record.studentName != null ? record.studentName : "");
        obj.put("studentId", record.studentId != null ? record.studentId : "");
        obj.put("username", record.username != null ? record.username : "");
        obj.put("loginMode", record.loginMode != null ? record.loginMode : "cookie");
        obj.put("sessionArtifactJson", record.sessionArtifactJson != null ? record.sessionArtifactJson : "");
        obj.put("sessionGeneration", record.sessionGeneration);
        obj.put("sessionState", record.sessionState != null ? record.sessionState : SessionState.EMPTY.name());
        obj.put("artifactSchemaVersion", record.artifactSchemaVersion);
        obj.put("cookie", record.cookie != null ? record.cookie : "");
        obj.put("cookieSaveTime", record.cookieSaveTime);
        return obj;
    }

    private AccountRecord accountFromJson(JSONObject obj) {
        AccountRecord record = new AccountRecord();
        record.key = obj.optString("key", "");
        record.schoolId = obj.optString("schoolId", "");
        record.schoolName = obj.optString("schoolName", "");
        record.studentName = obj.optString("studentName", "");
        record.studentId = obj.optString("studentId", "");
        record.username = obj.optString("username", "");
        record.loginMode = obj.optString("loginMode", "cookie");
        record.sessionArtifactJson = obj.optString("sessionArtifactJson", "");
        record.sessionGeneration = obj.optLong("sessionGeneration", 0L);
        record.sessionState = obj.optString("sessionState", SessionState.EMPTY.name());
        record.artifactSchemaVersion = obj.optInt("artifactSchemaVersion", 0);
        record.cookie = obj.optString("cookie", "");
        record.cookieSaveTime = obj.optLong("cookieSaveTime", 0L);
        if (record.key.isEmpty()) {
            SchoolConfig school = getSchoolById(record.schoolId);
            record.key = buildAccountKey(school, record.username, record.studentId, record.studentName);
        }
        return record;
    }

    private List<AccountRecord> loadAccountRecords() {
        List<AccountRecord> records = new ArrayList<>();
        if (appContext == null) return records;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_ACCOUNTS, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                AccountRecord record = accountFromJson(arr.getJSONObject(i));
                if (record.key != null && !record.key.isEmpty()) {
                    records.add(record);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载账号列表失败: " + e.getMessage());
        }
        return records;
    }

    private void saveAccountRecords(List<AccountRecord> records) {
        if (appContext == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (AccountRecord record : records) {
                arr.put(accountToJson(record));
            }
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "保存账号列表失败: " + e.getMessage());
        }
    }

    private AccountRecord buildCurrentAccountRecord() {
        if (currentSchool == null) return null;
        ensureCurrentAccountKey();
        AccountRecord record = new AccountRecord();
        record.schoolId = currentSchool.id;
        record.schoolName = currentSchool.name;
        record.studentName = studentName != null ? studentName : "";
        record.studentId = studentId != null ? studentId : "";
        record.username = getUsername();
        record.loginMode = getLoginMode();
        record.key = currentAccountKey;
        if (record.key == null || record.key.isEmpty()) {
            record.key = buildOrDefaultAccountKey(currentSchool, record.username, record.studentId, record.studentName);
        }
        SessionSnapshot snapshot = SessionRegistry.snapshot(storageKeyForRecord(record));
        SessionArtifact artifact = snapshot.getArtifact();
        if (artifact != null && artifact.getSchoolScope().equals(SchoolSessionScope.fromSchool(currentSchool))) {
            record.sessionArtifactJson = SessionArtifactCodec.encode(artifact);
            record.sessionGeneration = snapshot.getGeneration();
            record.sessionState = snapshot.getState().name();
            record.artifactSchemaVersion = artifact.getSchemaVersion();
            record.cookie = artifact instanceof SessionArtifact.LegacyCookieHeader
                    ? ((SessionArtifact.LegacyCookieHeader) artifact).getHeader()
                    : "";
        } else {
            // This only occurs before a legacy session is installed.  Keep the
            // compatibility field for migration, but never synthesize RFC data.
            record.cookie = savedCookie != null ? savedCookie : "";
        }
        record.cookieSaveTime = System.currentTimeMillis();
        return record.key.isEmpty() ? null : record;
    }

    private void upsertCurrentAccountRecord() {
        AccountRecord record = buildCurrentAccountRecord();
        if (record == null) return;
        List<AccountRecord> records = loadAccountRecords();
        boolean replaced = false;
        for (int i = 0; i < records.size(); i++) {
            if (record.key.equals(records.get(i).key)) {
                records.set(i, mergeAccountRecord(records.get(i), record));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            records.add(record);
        }
        currentAccountKey = record.key;
        saveAccountRecords(records);
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey).apply();
        }
    }

    private AccountRecord mergeAccountRecord(AccountRecord existing, AccountRecord replacement) {
        if (existing == null) return replacement;
        existing.schoolId = replacement.schoolId;
        existing.schoolName = replacement.schoolName;
        existing.studentName = replacement.studentName;
        existing.studentId = replacement.studentId;
        existing.username = replacement.username;
        existing.loginMode = replacement.loginMode;
        existing.cookieSaveTime = replacement.cookieSaveTime;

        if (replacement.sessionArtifactJson != null && !replacement.sessionArtifactJson.isEmpty()) {
            existing.sessionArtifactJson = replacement.sessionArtifactJson;
            existing.sessionGeneration = replacement.sessionGeneration;
            existing.sessionState = replacement.sessionState;
            existing.artifactSchemaVersion = replacement.artifactSchemaVersion;
            existing.cookie = replacement.cookie;
        } else if (existing.sessionArtifactJson == null || existing.sessionArtifactJson.isEmpty()) {
            existing.cookie = replacement.cookie;
        }
        return existing;
    }

    private String storageKeyForRecord(AccountRecord record) {
        return SessionRegistry.normalizeAccountKey(record != null ? record.key : "");
    }

    private SessionArtifact decodeArtifact(AccountRecord record) {
        if (record == null || record.sessionArtifactJson == null || record.sessionArtifactJson.isEmpty()) {
            return null;
        }
        return SessionArtifactCodec.decode(record.sessionArtifactJson);
    }

    private SessionState sessionStateOf(AccountRecord record) {
        if (record == null || record.sessionState == null) return SessionState.EMPTY;
        try {
            return SessionState.valueOf(record.sessionState);
        } catch (IllegalArgumentException ignored) {
            return SessionState.EMPTY;
        }
    }

    /**
     * Checks a persisted account without relying on UserManager.currentSchool.
     * A valid artifact must be scoped to the supplied school and, for RFC mode,
     * contain at least one cookie matching a discovered SERVICE_REQUIRED
     * audience.  A canonical-SCNU flat header is intentionally non-restorable.
     */
    public boolean hasRestorableSession(SchoolConfig school, AccountRecord record) {
        if (school == null || record == null || !school.id.equals(record.schoolId)) return false;
        SessionArtifact artifact = decodeArtifact(record);
        if (artifact == null || artifact.getSchemaVersion() != SessionArtifact.CURRENT_SCHEMA_VERSION) return false;
        if (record.artifactSchemaVersion > 0
                && artifact.getSchemaVersion() != record.artifactSchemaVersion) return false;
        if (!artifact.getSchoolScope().equals(SchoolSessionScope.fromSchool(school))) return false;

        SessionState state = sessionStateOf(record);
        if (state != SessionState.ACTIVE && state != SessionState.INACTIVE) return false;

        if (artifact instanceof SessionArtifact.LegacyCookieHeader) {
            return !SchoolSessionScope.isCanonicalScnu(school)
                    && !((SessionArtifact.LegacyCookieHeader) artifact).getHeader().isEmpty();
        }
        if (artifact instanceof SessionArtifact.RfcCookieBundle) {
            SessionArtifact.RfcCookieBundle bundle = (SessionArtifact.RfcCookieBundle) artifact;
            for (com.tyust.course.session.PersistedCookie cookie : bundle.getCookies()) {
                if (bundle.matchesAnyServiceAudience(cookie)) return true;
            }
        }
        return false;
    }

    public boolean hasRestorableSession() {
        if (currentSchool == null) return false;
        return hasRestorableSession(currentSchool, findAccountRecord(getCurrentAccountKey()));
    }

    private boolean hasRestorableCurrentSession() {
        return hasRestorableSession();
    }

    /**
     * Persist a snapshot already accepted by SessionRegistry.  It only updates
     * the snapshot's account record; it never changes a different foreground
     * account or school.
     */
    public boolean persistSessionSnapshot(SessionSnapshot snapshot) {
        if (snapshot == null || appContext == null) return false;
        List<AccountRecord> records = loadAccountRecords();
        AccountRecord record = null;
        for (AccountRecord candidate : records) {
            if (snapshot.getAccountStorageKey().equals(storageKeyForRecord(candidate))) {
                record = candidate;
                break;
            }
        }
        if (record == null) return false;

        SessionArtifact artifact = snapshot.getArtifact();
        record.sessionGeneration = snapshot.getGeneration();
        record.sessionState = snapshot.getState().name();
        record.sessionArtifactJson = artifact != null ? SessionArtifactCodec.encode(artifact) : "";
        record.artifactSchemaVersion = artifact != null ? artifact.getSchemaVersion() : 0;
        if (artifact instanceof SessionArtifact.LegacyCookieHeader) {
            record.cookie = ((SessionArtifact.LegacyCookieHeader) artifact).getHeader();
        } else if (artifact == null || artifact instanceof SessionArtifact.RfcCookieBundle) {
            // In particular, do not flatten an RFC bundle into the old field.
            record.cookie = "";
        }
        saveAccountRecords(records);

        if (snapshot.getAccountStorageKey().equals(getCurrentAccountStorageKey())) {
            savedCookie = artifact instanceof SessionArtifact.LegacyCookieHeader
                    ? ((SessionArtifact.LegacyCookieHeader) artifact).getHeader()
                    : "";
            if (snapshot.getState() == SessionState.EXPIRED || snapshot.getState() == SessionState.EMPTY) {
                isLoggedIn = false;
            }
        }
        return true;
    }

    /**
     * Convenience bridge for callers that own the confirmed-expired decision.
     * No current-account inference is made for a different account.
     */
    public boolean markAndPersistConfirmedExpired(String accountStorageKey, long expectedGeneration) {
        SessionSnapshot expired = SessionRegistry.markConfirmedExpired(accountStorageKey, expectedGeneration);
        return expired != null && persistSessionSnapshot(expired);
    }

    /**
     * Account-record-aware installation boundary for typed login callbacks.
     * The login flow owns the SessionInstallTarget; this method only validates
     * scope/account identity, delegates the CAS to SessionRegistry, and
     * persists the accepted immutable snapshot.
     */
    public SessionInstallResult installSession(SessionInstallTarget target, SessionArtifact artifact) {
        if (target == null || artifact == null) {
            return SessionInstallResult.InvalidScope.INSTANCE;
        }
        SchoolConfig targetSchool = getSchoolById(target.getSchoolScope().getSchoolId());
        if (targetSchool == null
                || !SchoolSessionScope.fromSchool(targetSchool).equals(target.getSchoolScope())
                || !artifact.getSchoolScope().equals(target.getSchoolScope())) {
            SessionRegistry.cancelLogin(target);
            return SessionInstallResult.InvalidScope.INSTANCE;
        }

        String targetStorageKey = SessionRegistry.normalizeAccountKey(target.getAccountStorageKey());
        AccountRecord targetRecord = findAccountRecordByStorageKey(targetStorageKey);
        boolean active = currentSchool != null
                && SchoolSessionScope.fromSchool(currentSchool).equals(target.getSchoolScope())
                && targetStorageKey.equals(getCurrentAccountStorageKey());
        if (targetRecord != null && !targetSchool.id.equals(targetRecord.schoolId)) {
            SessionRegistry.cancelLogin(target);
            return SessionInstallResult.InvalidScope.INSTANCE;
        }
        if (targetRecord == null && !active) {
            // An inactive result belongs to an existing account.  Do not create
            // an unaddressable record from a late callback after a switch.
            SessionRegistry.cancelLogin(target);
            return SessionInstallResult.InvalidScope.INSTANCE;
        }

        SessionInstallResult result = SessionRegistry.install(target, artifact, active);
        SessionSnapshot snapshot = snapshotFromInstallResult(result);
        if (snapshot == null) return result;

        if (targetRecord == null) {
            // This is the active first-login case; build its metadata before
            // persisting the new typed artifact.
            upsertCurrentAccountRecord();
        }
        persistSessionSnapshot(snapshot);

        if (active) {
            savedCookie = artifact instanceof SessionArtifact.LegacyCookieHeader
                    ? ((SessionArtifact.LegacyCookieHeader) artifact).getHeader()
                    : "";
            isLoggedIn = true;
            saveLoginState();
            refreshRuntimeForCurrentAccount();
        }
        return result;
    }

    private SessionSnapshot snapshotFromInstallResult(SessionInstallResult result) {
        if (result instanceof SessionInstallResult.InstalledActive) {
            return ((SessionInstallResult.InstalledActive) result).getSnapshot();
        }
        if (result instanceof SessionInstallResult.InstalledInactive) {
            return ((SessionInstallResult.InstalledInactive) result).getSnapshot();
        }
        return null;
    }

    private boolean installCurrentLegacyCookie(String cookie, boolean active) {
        String header = cookie != null ? cookie.trim() : "";
        if (currentSchool == null || header.isEmpty()) {
            return false;
        }
        if (SchoolSessionScope.isCanonicalScnu(currentSchool)) {
            // No SCNU endpoint/audience has been authorized yet, and a flattened
            // header is never a substitute for an RFC bundle.
            Log.w(TAG, "拒绝为 canonical SCNU 安装扁平 Cookie 会话");
            savedCookie = "";
            isLoggedIn = false;
            return false;
        }

        ensureCurrentAccountKey();
        String accountStorageKey = getCurrentAccountStorageKey();
        SchoolSessionScope scope = SchoolSessionScope.fromSchool(currentSchool);
        SessionInstallTarget target = SessionRegistry.beginLogin(accountStorageKey, scope);
        SessionArtifact.LegacyCookieHeader artifact = new SessionArtifact.LegacyCookieHeader(
                scope,
                header,
                System.currentTimeMillis(),
                SessionArtifact.CURRENT_SCHEMA_VERSION
        );
        SessionInstallResult result = installSession(target, artifact);
        if (snapshotFromInstallResult(result) == null) {
            Log.w(TAG, "未安装旧版会话: " + result.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    private AccountRecord findAccountRecord(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return null;
        for (AccountRecord record : loadAccountRecords()) {
            if (accountKey.equals(record.key)) return record;
        }
        return null;
    }

    private AccountRecord findAccountRecordByStorageKey(String accountStorageKey) {
        if (accountStorageKey == null || accountStorageKey.isEmpty()) return null;
        for (AccountRecord record : loadAccountRecords()) {
            if (accountStorageKey.equals(storageKeyForRecord(record))) return record;
        }
        return null;
    }

    public List<AccountRecord> getSavedAccounts() {
        return new ArrayList<>(loadAccountRecords());
    }

    public List<AccountRecord> getAccountsForCurrentSchool() {
        List<AccountRecord> result = new ArrayList<>();
        if (currentSchool == null) return result;
        for (AccountRecord record : loadAccountRecords()) {
            if (currentSchool.id.equals(record.schoolId)) {
                result.add(record);
            }
        }
        return result;
    }

    public String getCurrentAccountKey() {
        if (currentAccountKey == null || currentAccountKey.isEmpty()) {
            currentAccountKey = buildOrDefaultAccountKey(currentSchool, getUsername(), studentId, studentName);
        }
        return currentAccountKey != null ? currentAccountKey : "";
    }

    public String getCurrentAccountStorageKey() {
        return SessionRegistry.normalizeAccountKey(getCurrentAccountKey());
    }

    public boolean switchToAccount(String accountKey) {
        AccountRecord record = findAccountRecord(accountKey);
        if (record == null) return false;
        boolean switched = applyAccountRecord(record, true);
        if (switched) {
            SessionRegistry.bumpActiveContextEpoch();
        }
        return switched;
    }

    private boolean applyAccountRecord(AccountRecord record, boolean persist) {
        if (record == null) return false;
        SchoolConfig school = getSchoolById(record.schoolId);
        if (school == null) {
            Log.w(TAG, "切换账号失败，找不到学校: " + record.schoolId);
            return false;
        }

        boolean restorable = hasRestorableSession(school, record);
        SessionArtifact artifact = restorable ? decodeArtifact(record) : null;

        currentSchool = school;
        studentName = record.studentName != null ? record.studentName : "";
        studentId = record.studentId != null ? record.studentId : "";
        currentAccountKey = record.key != null ? record.key : "";
        if (currentAccountKey.isEmpty()) {
            currentAccountKey = buildOrDefaultAccountKey(school, record.username, studentId, studentName);
        }
        String accountStorageKey = getCurrentAccountStorageKey();
        savedCookie = artifact instanceof SessionArtifact.LegacyCookieHeader
                ? ((SessionArtifact.LegacyCookieHeader) artifact).getHeader()
                : "";
        isLoggedIn = restorable;
        isDemoMode = false;
        sessionPassword = sessionPasswords.containsKey(currentAccountKey) ? sessionPasswords.get(currentAccountKey) : "";

        if (restorable) {
            SessionRegistry.restore(accountStorageKey, artifact, record.sessionGeneration, true);
        } else {
            // Do not revive an expired, malformed, mismatched, or canonical
            // SCNU flat-header record into the process runtime.
            SessionRegistry.restore(accountStorageKey, null, record.sessionGeneration, false);
        }

        if (persist && appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(KEY_LOGGED_IN, restorable)
                    .putString(KEY_STUDENT_NAME, studentName)
                    .putString(KEY_STUDENT_ID, studentId)
                    .putString(KEY_COOKIE, savedCookie)
                    .putString(KEY_CURRENT_SCHOOL_ID, school.id)
                    .putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey)
                    .putString(KEY_USERNAME, record.username != null ? record.username : "")
                    .putString(KEY_LOGIN_MODE, record.loginMode != null ? record.loginMode : "cookie")
                    .putLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis())
                    .apply();
        }

        refreshRuntimeForCurrentAccount();
        Log.d(TAG, "已切换账号，school=" + school.name);
        return true;
    }

    public void refreshRuntimeForCurrentAccount() {
        try {
            CourseApiClient apiClient = CourseApiClient.getInstance();
            apiClient.clearDisplayParamsCache();
            AccountRecord record = findAccountRecord(getCurrentAccountKey());
            SessionArtifact artifact = record != null && currentSchool != null
                    && hasRestorableSession(currentSchool, record)
                    ? decodeArtifact(record)
                    : null;
            if (artifact != null) {
                apiClient.installSessionArtifact(getCurrentAccountStorageKey(), artifact);
            } else {
                apiClient.clearCookies(getCurrentAccountStorageKey());
            }
            SmartSelector.getInstance().reloadForCurrentAccount();
        } catch (Exception e) {
            Log.w(TAG, "刷新账号运行态失败: " + e.getMessage());
        }
    }

    // 清除登录状态（退出登录时调用）
    public synchronized void clearLoginState() {
        String accountStorageKeyToClear = getCurrentAccountStorageKey();
        String accountKeyToClear = currentAccountKey;
        // Cancels any captcha/login continuation before the generation changes.
        // A late callback then cannot retain credentials or replace this session.
        sessionRefreshCoordinator.cancel(accountStorageKeyToClear);
        SessionSnapshot cleared = SessionRegistry.clear(accountStorageKeyToClear);
        persistSessionSnapshot(cleared);
        isLoggedIn = false;
        studentName = "";
        studentId = "";
        savedCookie = "";
        sessionPassword = "";
        currentAccountKey = "";
        if (accountKeyToClear != null && !accountKeyToClear.isEmpty()) {
            sessionPasswords.remove(accountKeyToClear);
        }

        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .remove(KEY_LOGGED_IN)
                    .remove(KEY_STUDENT_NAME)
                    .remove(KEY_STUDENT_ID)
                    .remove(KEY_COOKIE)
                    .remove(KEY_COOKIE_SAVE_TIME)
                    .remove(KEY_USERNAME)
                    .remove(KEY_LOGIN_MODE)
                    .remove(KEY_CURRENT_ACCOUNT_KEY)
                    .apply(); // 注意：这里不再 remove KEY_CURRENT_SCHOOL_ID，实现学校记忆
        }

        try {
            CourseApiClient.getInstance().clearCookies(accountStorageKeyToClear);
        } catch (Exception e) {
            Log.w(TAG, "清理账号运行期 Cookie 失败: " + e.getMessage());
        }

        Log.d(TAG, "登录状态已清除");
    }

    // 别名方法，方便 Kotlin 调用
    public void logout() {
        clearLoginState();
    }

    // ========== 原有方法 ==========

    public void setLoggedIn(boolean loggedIn) {
        this.isLoggedIn = loggedIn;
        if (loggedIn) {
            saveLoginState();
        }
    }

    public boolean isLoggedIn() {
        return isLoggedIn;
    }

    public void setDemoMode(boolean demoMode) {
        this.isDemoMode = demoMode;
    }

    public boolean isDemoMode() {
        return isDemoMode;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
        saveLoginState();
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
        saveLoginState();
    }
}
