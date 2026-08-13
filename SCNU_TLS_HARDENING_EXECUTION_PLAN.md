# SCNU TLS、能力开关与隐私收敛：Luna 执行计划

## 0. 文档地位与执行规则

本文件是对根目录 `PLAN.md` 已完成会话改造的**小范围安全收敛增量**。它不重写、不替换、更不回退 `PLAN.md` 中已冻结的 SessionArtifact、AccountCookieJar、SessionRequestContext、SessionRegistry、三态响应分类或会话刷新协调器。

执行者：5.6 Luna MAX。执行前先完整阅读：

1. 根目录 `PLAN.md`；
2. 本文件；
3. 本文件列出的目标文件当前版本。

严格规则：

- 只改本计划列出的文件；测试文件和 `app/build.gradle` 仅在本计划明确需要时修改。
- 不提交、不推送、不创建 PR，除非用户另行明确授权。
- 不访问真实帐号，不把帐号、密码、Cookie、Ticket、Token、姓名或 URL query 写入源码、fixture、测试输出或日志。
- 不猜测新的 SCNU 协议 URL、字段、Cookie audience 或失效 schema。
- 不创建新的登录、Cookie、请求上下文或会话传递链；必须复用现有 `AccountCookieJar`、现有长拦截器、`SessionRequestContext` 和 `CourseApiClient.validateCookie(...)`。
- 不因测试或真机验证暂时失败而恢复/扩大 trust-all TLS；失败只能作为验证失败报告。

## 1. 已确认事实与本次目标

### 1.1 保留的已验证能力（不可关闭）

`app/src/main/java/com/tyust/course/session/ProtocolCapabilities.kt` 中 canonical SCNU 的下列能力已验证可用，必须保持：

```kotlin
const val LOGIN_ENABLED: Boolean = true
const val ACADEMIC_PROFILE_ENABLED: Boolean = true
```

因此本次不得修改：

- `ScnuSsoLoginManager` 的五段 SSO 登录流程；
- `ScnuEvidenceProfile` 中已经记录的已验证证据；
- 成绩、学业情况、课表等由 `isAcademicProfileAvailable(...)` 放行的 SCNU 学业查询；
- RFC Cookie Bundle 的安装、恢复、匹配与持久化逻辑。

### 1.2 本次必须达成的结果

1. 删除一次性协议探测脚本，彻底消除脚本输出 Cookie / 跳转 query 的泄露面。
2. 删除三处包含学生姓名的本地 debug 日志。
3. **仅关闭 canonical SCNU 的选课能力**：`COURSE_SELECTION_ENABLED = false`；不影响非 SCNU 学校。
4. canonical SCNU 不启动 `CookieWatchdog` 周期探测，避免后台通过全局 trust-all client 访问教务。
5. canonical SCNU 的登录后 `validateCookie(...)` 使用默认严格 TLS 的专用 OkHttpClient；该严格 client 与原 client 共享 CookieJar、超时、重定向和现有请求/会话拦截器。
6. 暂不删除全局 trust-all TLS。它仍是 TYUST 等遗留学校的兼容路径，删除动作必须等后续真机证书验证完成后再单独执行。

### 1.3 明确不在本次范围

- 不关闭 `LOGIN_ENABLED` 或 `ACADEMIC_PROFILE_ENABLED`。
- 不改 `SessionResponseClassifier`、`SessionProbe`、过期广播语义或自动刷新策略。
- 不让 SCNU `CookieWatchdog` 改走另一套探测协议；本阶段是停用其后台探测，而不是猜测新的探测页。
- 不将所有 `CourseApiClient` API 改成双 client；严格 TLS 仅用于 canonical SCNU 的 `validateCookie(...)` 最终发包点。
- 不删除全局 trust-all，不改变 TYUST/ZJUT 的当前请求行为。
- 不为测试引入运行时依赖注入、第二套 CookieJar、第二套拦截器或新的 Client Factory。

## 2. 当前代码锚点（执行前重新核实）

| 目标 | 当前锚点 | 要点 |
| --- | --- | --- |
| capability | `session/ProtocolCapabilities.kt` | 三个 Boolean 均为 `true`；只改选课项。 |
| 全局 client | `network/CourseApiClient.java` 构造函数 | `AccountCookieJar`、长 interceptor、超时、trust-all TLS 均在此。 |
| 登录验证 | `CourseApiClient.validateCookie(SchoolConfig, SessionRequestContext, Callback)` | 构造带 tag 的 request 后目前由 `client.newCall(...)` 发出；这是唯一切换点。 |
| 后台探测 | `utils/CookieWatchdog.kt` 的 `start(...)` | 目前启动 Handler 定时任务，随后通过 `validateCookie(...)` 探测。 |
| 登录验收 | `LoginActivity.performLoginValidation(...)` | SSO Session 安装完成后调用 `validateCookie`；验证失败会清理刚安装的 Session，不能跳过。 |
| PII 日志 | `manager/UserManager.java` | `saveLoginState`、`loadLoginState`、账号切换处均打印 `studentName`。 |
| 一次性脚本 | `scripts/scnu_sso_probe.sh` | 非 app 运行时依赖，只是手动协议发现脚本。 |

如果上述任一锚点已因他人修改而不存在或语义明显不同：停止该步骤，报告差异；不要按行号盲改，也不要自行扩展设计。

## 3. 分阶段实施步骤

每一阶段完成后执行本阶段的静态检查；不要积累到最后才发现范围漂移。所有文本编辑使用 `apply_patch`。

### 阶段 A：删除探测脚本与清除姓名日志

#### A1. 先查引用，再删除脚本

执行只读检查：

```powershell
rg -n "scnu_sso_probe" README.md docs .github scripts app -g '!app/build/**'
```

- 若无 app/CI/文档引用：删除 `scripts/scnu_sso_probe.sh`。
- 若只有文档引用：删除脚本，并删除或改写该文档入口，不能留下不可用命令。
- 若发现 Java/Kotlin/Gradle/CI 的运行时依赖：停止并报告。这与“脚本一次性、无运行时引用”的前提冲突，不能擅自删除。

不要尝试修补脚本中的 `curl -w`、`awk` 或 Cookie 输出。删除是本范围内最小且彻底的消除泄露源方案。

#### A2. 清除三处 `studentName` 日志

只改日志文字，不能改 SharedPreferences、账号 key、`AccountRecord.studentName`、UI 显示或学生绑定逻辑。

在 `UserManager.java` 达到以下等价结果：

```java
// saveLoginState
Log.d(TAG, "登录状态已保存: isLoggedIn=" + isLoggedIn);

// loadLoginState
Log.d(TAG, "登录状态已加载: isLoggedIn=" + isLoggedIn
        + ", school=" + (currentSchool != null ? currentSchool.name : "null"));

// switch account
Log.d(TAG, "已切换账号，school=" + school.name);
```

可以保留学校名称和是否登录；不得保留姓名的完整值、掩码值、首字、hash 或拼接后的账号标识。

#### A3. A 阶段检查

```powershell
rg -n "scnu_sso_probe" README.md docs .github scripts app -g '!app/build/**'
rg -n "Log\.[divew]\([^\n]*(studentName|student=|已切换账号:)" app/src/main -g '*.java' -g '*.kt'
git diff --check
```

第一条在脚本删除后不应再出现；第二条若命中非日志的数据模型代码不是失败，但任何包含 `Log.` 与学生姓名值的命中必须人工逐项确认已清除。

### 阶段 B：收紧 SCNU capability，不影响已验证功能

仅在 `ProtocolCapabilities.kt` 修改：

```kotlin
const val LOGIN_ENABLED: Boolean = true
const val ACADEMIC_PROFILE_ENABLED: Boolean = true
const val COURSE_SELECTION_ENABLED: Boolean = false
```

保留现有 `isCourseSelectionAllowed` 与 `isAcademicProfileAvailable` 方法，不新增 school-id 判断，不复制 capability gate 到各 UI。

理由：现有调用方已经在 UI、Route、GrabService/Alarm 等发包前复用 `isCourseSelectionAllowed(...)`；改常量即可关闭 canonical SCNU 选课，同时非 SCNU 因 `!isCanonicalScnu(school)` 仍放行。

不得：

- 将 `unavailableMessage()` 改成“登录未验证”；本次是选课关闭，登录与学业功能仍可用。
- 为 SCNU academic API 添加新 gate 或把 `ACADEMIC_PROFILE_ENABLED` 设为 `false`。
- 通过 host 字符串包含关系识别 SCNU；继续用 `SchoolSessionScope.isCanonicalScnu(...)`。

阶段 B 静态检查：

```powershell
rg -n "LOGIN_ENABLED|ACADEMIC_PROFILE_ENABLED|COURSE_SELECTION_ENABLED" app/src/main/java/com/tyust/course/session/ProtocolCapabilities.kt
```

### 阶段 C：在 Watchdog 入口停用 canonical SCNU 周期探测

目标文件：`app/src/main/java/com/tyust/course/utils/CookieWatchdog.kt`。

#### C1. 精确改动

在 `start(ctx, intervalMs)` 的最开始、**早于** `if (running) return` 和任何 `Handler.postDelayed(...)`，读取当前学校并执行：

```kotlin
val currentSchool = UserManager.getInstance().currentSchool
if (SchoolSessionScope.isCanonicalScnu(currentSchool)) {
    stop()
    Log.d(TAG, "SCNU CookieWatchdog disabled: strict background session probe is not enabled")
    return
}
```

按实际 package 新增 `SchoolSessionScope` import。

这必须在 `start()` 而不是 `check()`：若从 `check()` 才返回，旧 Handler 仍会每个周期空转，且已存在的 watcher 状态不会被彻底清理。`stop()` 会移除 callback、清空 watched school/account/generation/epoch，因此可以安全处理从非 SCNU 切换到 SCNU 后已有的 watcher。

#### C2. 保持原有行为

- 不改 `checkRunnable`、`SessionProbeContext`、三态分类、退避间隔、广播或 `validateCookie` 的 callback。
- 非 canonical SCNU（例如同名但 host/basePath 不符合）按现有逻辑运行；不能过度关闭其他学校。
- 不要求修改 `MainActivity`：它继续正常调用 `CookieWatchdog.start()`，入口自身负责安全短路，避免增加重复 gate。

#### C3. C 阶段检查

静态确认 `start()` 中的短路发生在调度前：

```powershell
rg -n "fun start|isCanonicalScnu|postDelayed|CookieWatchdog disabled" app/src/main/java/com/tyust/course/utils/CookieWatchdog.kt
```

### 阶段 D：为 SCNU 登录验收使用严格 TLS client

目标文件：`app/src/main/java/com/tyust/course/network/CourseApiClient.java`。

#### D1. 设计边界

当前 `client` 被配置成 trust-all TLS，原因是对部分遗留学校的证书兼容。不能删除其配置，也不能将全站 endpoint 切到 strict client。

新建的严格 client 必须满足：

- 使用默认 OkHttp/平台证书校验：**绝不**调用 `sslSocketFactory(...)`，绝不设置接受任意主机的 `hostnameVerifier(...)`，绝不使用信任所有证书的 `TrustManager`。
- 复用同一个 `cookieJar` 实例。
- 复用**同一个**现有请求拦截器实例：包括 `SessionRequestContext` tag 读取、AccountCookieJar 帐号绑定、移除内部 header、现有本地缓存保护以及响应三态分类。
- 与现有 client 一致的 `followRedirects`、`followSslRedirects`、连接/读取超时。
- 仅供 canonical SCNU 的 `validateCookie(...)` 使用。

#### D2. 最小重构方式（不可复制拦截器）

1. 在字段区新增：

```java
private final OkHttpClient strictTlsClient;
private final Interceptor sessionAwareInterceptor;
```

并增加 `okhttp3.Interceptor` import。

2. 将构造函数中已有的长 `.addInterceptor(chain -> { ... })` lambda 原样移动为一次赋值：

```java
sessionAwareInterceptor = chain -> {
    // 保持原 lambda 的完整既有逻辑和 finally 清理，不改变语义。
};
```

只允许为了抽取移动代码/修复 Java 作用域而改格式；不得改变其中的分类条件、Cookie 绑定或安全哨兵逻辑。

3. 在同一个类中新增私有无参 helper，例如：

```java
private OkHttpClient.Builder newBaseClientBuilder() {
    return new OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(sessionAwareInterceptor);
}
```

使用项目现有的 `java.util.concurrent.TimeUnit.SECONDS` 写法即可；不要为此调整全文件 imports。

4. 构造函数中依次创建两份 builder：

```java
OkHttpClient.Builder insecureBuilder = newBaseClientBuilder();
OkHttpClient.Builder strictBuilder = newBaseClientBuilder();
```

5. 将**原有、逐字等价**的 trust-all `SSLContext` / `sslSocketFactory` / `hostnameVerifier` 代码仅应用到 `insecureBuilder`；异常日志可以保留，但不能在异常时令 strict builder 降级。

6. 构造完成：

```java
client = insecureBuilder.build();
strictTlsClient = strictBuilder.build();
```

不要使用 `client.newBuilder()` 产生 strict client；它会继承 trust-all 配置，违反本计划。

#### D3. 严格 client 的唯一使用点

仅在最终 overload：

```java
validateCookie(SchoolConfig school, SessionRequestContext context, Callback callback)
```

中，在当前 request 构造完成后选择 Call client：

```java
OkHttpClient requestClient = SchoolSessionScope.isCanonicalScnu(school)
        ? strictTlsClient
        : client;
requestClient.newCall(request).enqueue(callback);
```

选择依据只能是现有显式 `SchoolConfig` 经 `SchoolSessionScope.isCanonicalScnu(school)` 判定；不得从 URL host、重定向目标、Cookie Domain 或请求 tag 缺失时猜测学校。

不得修改 `validateCookie` 的签名、`SessionRequestContext` tag、request URL、callback，或 `LoginActivity` 的成功/失败语义。这样 `LoginActivity.performLoginValidation(...)` 仍以原有安装 target/generation/epoch 检查验收 SSO Session；唯一改变是其 SCNU 网络验证具备正常的服务端证书与主机名校验。

#### D4. 失败行为（必须保留）

若 SCNU `studentInfoPath`、页面标记或姓名解析在严格 TLS 下不能完成：

- `validateCookie` 正常回调失败/原有页面验证失败；
- `LoginActivity` 按现有 `discardPostInstallFailure()` 处理该次未完成登录；
- 显示现有失败信息即可；
- **不得**回退到 trust-all client；
- **不得**放宽 `success` 判定、跳过登录后验证或捏造学生姓名。

严格 TLS 只证明连接证书/主机名可信，不证明 SCNU 业务页面解析已验证；二者必须分开对待。

### 阶段 E：测试与构建验证

#### E1. 不重造测试基础设施

`app/build.gradle` 已含：

```gradle
testImplementation 'junit:junit:4.13.2'
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
```

因此不要新增另一套 HTTP 库或改生产代码为可注入 Client Factory。

如果严格 TLS 的本地 JVM 测试确实需要 `HeldCertificate` / `HandshakeCertificates`，可**仅**追加与当前 OkHttp 一致版本的 test 依赖：

```gradle
testImplementation 'com.squareup.okhttp3:okhttp-tls:4.12.0'
```

不要升级 OkHttp 版本，不新增 production dependency。

#### E2. 最小测试清单

1. **Capability 回归测试**：assert canonical SCNU 的 `LOGIN_ENABLED` 与 `ACADEMIC_PROFILE_ENABLED` 仍为 true，`isAcademicProfileAvailable(scnu)` 为 true；`isCourseSelectionAllowed(scnu)` 为 false；任意既有非 SCNU SchoolConfig 仍允许选课。
2. **scope 边界测试**：复用既有 `SchoolSessionScope` 测试，证明仅 canonical（https + `jwxt.scnu.edu.cn` + 空 basePath）命中；相同 host 但 `/` 或非 canonical basePath 不被误判。不要复制 Scope 实现。
3. **严格 TLS 拒绝测试**：MockWebServer 使用未受信任自签名证书时，严格路径必须以 TLS handshake/peer-unverified 类失败结束；不得调用 hostname verifier bypass。
4. **严格 TLS 受信任成功与 Cookie 读取测试**：若加入 `okhttp-tls`，使用测试专用 CA/服务端证书和显式信任该测试 CA 的测试 socket factory，验证正常 HTTPS 响应可读取 `Set-Cookie` 并由共享 `AccountCookieJar` 按现有帐户上下文保存/取回。

第 4 项的 test-only trust manager 仅用于模拟“可信 CA 成功”的测试环境，不能进入 `src/main`，不能替代平台默认严格 TLS，也不能成为 production strict client 的配置。

若现有 `CourseApiClient` singleton 无法在普通 JVM 测试中安全替换 client：

- 不要为测试大规模重构生产构造函数；
- 至少保留 1、2 项纯 JVM 回归测试和 `rg` 静态断言；
- 将 3、4 项作为真机验证清单执行；
- 在最终报告中明确哪项是自动化验证、哪项需真机验证。不得伪报“严格 TLS 已被 MockWebServer 覆盖”。

#### E3. 构建命令（Windows）

使用已验证可兼容的完整 JBR 21：

```powershell
$env:JAVA_HOME='C:\Users\asus\.jdks\jbr-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat :app:testDebugUnitTest --no-daemon
./gradlew.bat :app:assembleDebug --no-daemon
git diff --check
```

不要用 Java 8，也不要用 Android Studio 的 JBR 25；此前二者分别与当前 AGP/Gradle 组合不兼容。若构建环境本身失败，报告完整错误，不改 Gradle wrapper、AGP、代理或 TLS 代码来绕过。

## 4. 真机验证门槛与后续全局 TLS 删除

### 4.1 本次提交前的真机验证

使用**自己的授权帐号**，关闭抓包代理/用户 CA 中间人后进行。不要把真实 Cookie 或完整 URL query 贴入 issue、日志或提交信息。

| 场景 | 预期 |
| --- | --- |
| SCNU SSO 登录 | 统一身份认证和既有五段登录流程成功；登录后 student-info 验证通过严格 TLS。 |
| SCNU 重启恢复 | 既有 RFC 会话恢复不被本次改动破坏；不启动 CookieWatchdog。 |
| SCNU 学业页面 | 成绩、学业情况、课表等 `ACADEMIC_PROFILE_ENABLED` 既有功能仍能使用。 |
| SCNU 选课入口/后台服务 | 在任何网络选课请求前被 capability 拒绝，不产生选课发包。 |
| TYUST 登录与教务请求 | 行为与改动前一致；本次仍保留其全局兼容 client。 |
| 非 SCNU 学校 | Watchdog 和既有 capability 行为不因 canonical SCNU 短路而变化。 |

SCNU 登录验证失败时，不可“为了让登录成功”重新打开 trust-all；记录不含敏感数据的错误类别、状态码和时间即可。

### 4.2 全局 trust-all 删除的后续独立任务（本次不实施）

只有在至少 TYUST 和 SCNU 的真机 HTTPS 登录/教务关键路径均通过、且维护者确认不存在必须兼容的证书异常学校后，才能另建一份独立计划执行：

1. 删除 `trustAllCerts` 定义；
2. 删除 `SSLContext.init(null, trustAllCerts, ...)`；
3. 删除 `sslSocketFactory(...)` 与接受任意 hostname 的 verifier；
4. 让原 `client` 使用默认严格 TLS；
5. 重新验证全部 HTTPS 学校；
6. 搜索并确认生产目录不存在 trust-all / always-true hostname verifier。

在该门槛前，严格 client 与遗留 client 并存是有意、受范围限制的过渡状态，不是允许扩大 TLS bypass 的先例。

## 5. 最终交付核对表

- [ ] `scripts/scnu_sso_probe.sh` 已删除，且无运行时/CI/文档死链接。
- [ ] 三处 UserManager 日志不再输出学生姓名或其派生值。
- [ ] `LOGIN_ENABLED == true`。
- [ ] `ACADEMIC_PROFILE_ENABLED == true`，SCNU 学业查询没有被本次关闭。
- [ ] `COURSE_SELECTION_ENABLED == false`，现有 gate 对 canonical SCNU 拒绝选课请求。
- [ ] canonical SCNU 调用 `CookieWatchdog.start()` 会 `stop()` 并在调度前返回。
- [ ] strictTlsClient 共享同一 CookieJar、超时、重定向和同一拦截器，但不含 trust-all TLS/hostname bypass。
- [ ] 仅 `validateCookie` 的 canonical SCNU 调用选择 strictTlsClient；其他 API 和非 SCNU 不变。
- [ ] `LoginActivity` 的现有安装 target/generation/epoch 与失败清理逻辑未改变。
- [ ] 自动化测试、Debug 构建和 `git diff --check` 通过；未通过的真机项已诚实列出。
- [ ] 最终报告列出：改动文件、测试命令与结果、真机验证结果、全局 trust-all 删除仍被哪些证据阻塞。

