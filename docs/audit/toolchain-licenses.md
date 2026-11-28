> 第一阶段只读审计存档。本文关于“未安装/尚不能构建”的结论描述当时环境；后续已获授权安装工具并生成 APK。当前状态请以工程根目录 BUILDING.md、TESTING.md 和交付证据为准。

# ZhiNengTiKa 原生 Android 构建条件与许可证审计

审计范围：
- 旧修复库：`/root/workspace/ZhiNengTiKa-release`
- 上游独立快照：`/root/workspace/ZhiNengTiKa-native-audit/upstream-reference`
- 本报告只读检查上述目录；未修改旧仓库、签名材料或用户文件。

## 一、结论先行

1. 当前容器确认是 **Linux arm64/aarch64、8 个逻辑 CPU**；本机有 JDK 17 和 JDK 21 的安装文件，JDK 17 适合 Android/AGP 构建。
2. 通过文件盘点，在常用路径中没有发现 Android SDK 的 `adb`、`aapt/aapt2`、`sdkmanager`、`emulator`，也没有发现 `qmake`/Qt 或独立 `gradle` 可执行文件；因此**本地无法据此证明可以直接构建或跑 UI/性能测试**。本次没有安装大型依赖。
3. 旧修复库的 CI 工作流具备可复现的 Qt Android 构建能力：Actions 中会 checkout 固定的 Qt-AES 与 android_openssl、安装 JDK17、SDK platform/build-tools 31、NDK 23.1.7779620、Qt 6.4.3 Android arm64_v8a，并运行 QtTest、qmake、androiddeployqt。未推送、未触发 CI。
4. 上游快照的 README 指定 Qt 6.7.2、NDK 25.1.8937393、JDK17；其 `.gitmodules` 还包含 ZXing-C++。这与旧修复库的 Qt 6.4.3/NDK r23b 构建链不是同一套，不应混用未验证的产物。
5. **版本兼容关键结论**：官方 Kotlin Gradle 配置表当前列出 KGP/Kotlin 2.1.20 的 fully-supported AGP 上限为 8.7.2；因此 `AGP 8.9.2 + Kotlin 2.1.20` 不能标为官方 fully-supported 组合。若坚持 AGP 8.9.2，应选择官方表中覆盖 AGP 8.9.2 的 Kotlin/KGP 2.3.10（其 fully-supported AGP 范围为 8.2.2–9.0.0），并让 Compose Compiler Gradle plugin 与 Kotlin 版本一致。
6. Dagger 官方 KSP 文档写明 Dagger/Hilt 的 KSP 支持自 Dagger 2.60+、KSP 2.3.9+ 起稳定。因此如果原生重构必须使用 Hilt+KSP，建议先采用 Hilt 2.60.1、KSP 2.3.10，而不是把 Hilt 2.56.2 + KSP 2.1.20-1.0.32 宣称为官方 KSP stable 组合。
7. 仓库内的 `Microsoft Yahei UI.ttc` 是高风险许可项：仓库没有随附字体许可，Microsoft 官方 FAQ 明确 Windows 字体通常不允许随意再分发/嵌入应用。原生重构应删除它，使用设备系统字体或取得明确授权；不要把它复制进 APK。

## 二、当前主机与工具链盘点

### 已确认

| 项目 | 结果 | 依据/说明 |
|---|---|---|
| OS | Ubuntu 24.04.5 LTS (Noble) | `/usr/lib/os-release` |
| CPU 架构 | AArch64/arm64，CPU architecture 8 | `/proc/cpuinfo`；JDK release 也标为 `OS_ARCH="aarch64"` |
| 逻辑 CPU | 8（processor 0–7） | `/proc/cpuinfo` |
| JDK 17 | 存在，`17.0.21.1`，Ubuntu arm64 | `/usr/lib/jvm/java-17-openjdk-arm64/release` |
| JDK 21 | 存在，`21.0.13.1`，Ubuntu arm64 | `/usr/lib/jvm/java-21-openjdk-arm64/release` |
| git | `/usr/bin/git` 可被路径盘点发现 | 只读路径盘点 |

### 未确认可用/未发现

以下名称在 `/usr/bin`、`/opt`、`/root`、`/usr/lib` 的针对性路径盘点中没有发现；由于当前会话的 bash 沙箱不可用且审批关闭，无法运行 `--version` 进一步确认。这里的“未发现”不等于对整台主机做了无限制扫描：

- Android SDK：`adb`、`aapt`、`aapt2`、`sdkmanager`、`avdmanager`、`emulator` 未发现。
- Gradle：系统 `gradle` 未发现；旧工程自带 wrapper 但其运行需要网络/缓存和 JDK，未在本机执行。
- Qt：`qmake`、`androiddeployqt` 未发现；Qt 主机/Android 工具链未发现。
- NDK：未发现已安装的 Android NDK 路径。
- Kotlin 编译器、Android Studio、可用 AVD：未发现可直接使用的路径/配置。

尝试使用只读 bash 做版本盘点时，工作区沙箱后端不可用，系统拒绝了 workspace-write 运行；审批关闭，按要求没有改用危险权限或重复请求。故本节仅报告可由 `read/glob` 直接确认的事实。

### 旧库 CI 的可用能力（仅静态检查，未触发）

`/root/workspace/ZhiNengTiKa-release/.github/workflows/android.yml` 中明确配置：

- Ubuntu 22.04 runner。
- `actions/setup-java@v4`，Temurin JDK 17。
- Android SDK：platform-tools、`platforms;android-31`、`build-tools;31.0.0`、`ndk;23.1.7779620`。
- `jurplel/install-qt-action@v4`：Qt 6.4.3、Android arm64_v8a、`qtwebview`、`qtshadertools`。
- checkout 固定 Qt-AES commit `fe02771df612ca80fdb12b1d48b6e089e1933e6b` 和 android_openssl commit `b71f1470962019bd89534a2919f5925f93bc5779`。
- qmake/make/androiddeployqt、zipalign、apksigner、aapt 及 QtTest 流程均在工作流中声明。

该工作流的签名步骤需要仓库 secrets；本审计没有读取或输出任何 secrets，也没有执行发布/推送/CI 触发。

## 三、旧库与上游项目结构/依赖

### 旧修复库

- `xinjiaoyu.pro` 使用 Qt Core/Widgets/Gui/Network；Android 额外使用 `core-private`、QML、Quick、WebView、Qt WebView wrapper。
- `third_party/Qt-AES` 和 `third_party/android_openssl` 是构建所需目录，但当前旧库工作树没有这两个目录；README 要求构建前按固定 commit checkout。
- 旧库当前工程使用 Qt-AES 的 `qaesencryption.cpp/.h` 直接编译，并将 android_openssl 的 `openssl.pri` 纳入 Android 构建。
- 旧库 Android Gradle 模板使用 AGP 7.2.1、Gradle wrapper 7.4.2、AndroidX core 1.7.0；这是 Qt 6.4.3 旧工程模板，不是原生 Compose 工程。
- 二维码扫描在旧修复库中是 `QRCodeScanner` 网络/API 方案；没有发现旧库集成 ZXing-C++ 的源码/目录。

### 上游独立快照

- 上游 README 指定 Qt 6.7.2 + Android NDK 25.1.8937393 + JDK17。
- `.gitmodules`：`3rd/android_openssl`、`3rd/Qt-AES`、`3rd/zxing-cpp`，均通过 Git 子模块引入；快照中未找到对应独立 LICENSE 文件，分发前应核对每个子模块实际 commit 的许可文件。
- 上游工程拆为 `ZhiNengTiKaCore`、`ZhiNengTiKaQML`、`ZhiNengTiKaWidgets`，二维码读取通过 ZXing-C++ 静态库。
- 上游 README 致谢 Qt-AES 与 ZXing-C++，上游 SettingWidget 的开源许可文本列出：Qt（LGPL v3）、ZXing-C++（Apache 2.0）、android_openssl（Apache 2.0）、Qt-AES（Unlicense）。该 UI 文本可作为线索，但不能替代随分发物附带的正式 LICENSE/NOTICE。

## 四、许可证/版权审计

### 已检查并可确认

| 组件/资源 | 发现 | 分发注意事项 |
|---|---|---|
| 主项目 | 旧库和上游快照均包含完整 GNU AGPL v3 LICENSE | 修改/再分发应保留许可证、版权和修改声明；AGPL 的对应源代码义务需由发布方落实。 |
| Qt | 旧库 UI 文本声称 Qt 为 LGPL v3；Qt 官方说明 Qt 是商业/开源双许可，主要开源选项为 LGPLv3/GPLv3，具体模块可能不同 | 必须随 APK/源码提供适用的 Qt 许可与第三方许可信息，并满足 LGPL 动态库、替换/重链接和对应源代码等要求；不能仅凭 UI 文本断言全部 Qt 模块同一许可证。 |
| Qt-AES 固定 commit | 官方 commit LICENSE 原文为 Unlicense/公共领域式授权 | 保留其 LICENSE/来源链接；不应把 Qt-AES 的宽松许可误写成 AGPL。来源：`https://raw.githubusercontent.com/bricke/Qt-AES/fe02771df612ca80fdb12b1d48b6e089e1933e6b/LICENSE` |
| android_openssl 固定 commit | 官方 commit LICENSE 为 Apache License 2.0 | 随分发物保留 Apache 2.0 文本及 NOTICE/版权归属（若该 commit 含 NOTICE）；来源：`https://raw.githubusercontent.com/KDAB/android_openssl/b71f1470962019bd89534a2919f5925f93bc5779/LICENSE` |
| ZXing-C++（上游） | 上游源码使用 ZXing-C++，官方仓库 LICENSE 为 Apache License 2.0；当前快照实际子模块 commit 未在报告中解析 | 在原生重构中使用时，锁定 commit 并随该 commit 的 LICENSE/NOTICE 分发；来源：`https://raw.githubusercontent.com/zxing-cpp/zxing-cpp/master/LICENSE` |
| AES/加密 | 旧库的 `XinjiaoyuEncryptioner` 使用 Qt-AES，AES-256/ECB/PKCS7 配置是应用代码 + Qt-AES 依赖 | 依赖的 Unlicense 与应用本身的 AGPL 分开标示；不要把业务密钥/API 凭据写入报告或构建产物。 |
| Android OpenSSL | 仅是 OpenSSL Android 预构建/集成依赖，固定仓库为 KDAB android_openssl | Apache 2.0 notice 与对应源代码/构建来源应保留；不要把它误称为 Qt 或 AGPL 代码。 |

### 高风险/待补证据

1. **Microsoft Yahei UI.ttc**：旧库资源中存在 `Resource/font/Microsoft Yahei UI.ttc`，仓库没有字体许可文件，也没有在 qrc/源码中发现明确引用。Microsoft 官方字体 FAQ 说明 Windows 字体通常不能复制到其他电脑/服务器或随应用再分发，应用/游戏嵌入需要另外许可。建议原生工程删除该文件，使用系统字体或取得字体方明确授权。来源：`https://learn.microsoft.com/en-us/typography/fonts/font-faq`。
2. **题卡/模板数据**：旧库 `Resource/templateData`、模板列表及上游资源未附逐项许可；旧库 SettingWidget 明确提示题卡数据所有权归“山东金榜苑文化传媒有限责任公司”。这类数据不应默认被 AGPL 覆盖，重构或再分发前需确认授权/来源和是否能随 APK 发行。
3. **图标/品牌资源**：`xinjiaoyuico.ico/png`、Android 图标和界面文字的版权/商标归属未在独立文件中完整说明。保留原作者版权和项目归属；对外发布前补齐 NOTICE/版权页。
4. **Qt 第三方组件**：Qt WebView、Qt Quick/QML、Qt Android bindings 可能带有各自第三方依赖。应从实际 Qt 6.4.3/6.7.2 安装包的 `licenses/` 和 Qt 官方第三方许可证页面生成完整 SBOM，不要只列 Qt 主许可证。
5. **上游子模块许可**：快照 `.gitmodules` 指向三个子模块，但快照中没有可供本地读取的子模块 LICENSE 文件。锁定真实 commit 后逐个复制 LICENSE/NOTICE，尤其是 ZXing-C++。

官方/来源链接：
- Qt 开源许可义务：`https://www.qt.io/development/open-source-lgpl-obligations`
- Qt 6 第三方许可证列表：`https://doc.qt.io/qt-6/licenses-used-in-qt.html`
- Qt-AES 固定 commit LICENSE：`https://raw.githubusercontent.com/bricke/Qt-AES/fe02771df612ca80fdb12b1d48b6e089e1933e6b/LICENSE`
- android_openssl 固定 commit LICENSE：`https://raw.githubusercontent.com/KDAB/android_openssl/b71f1470962019bd89534a2919f5925f93bc5779/LICENSE`
- ZXing-C++ LICENSE：`https://raw.githubusercontent.com/zxing-cpp/zxing-cpp/master/LICENSE`
- Microsoft 字体再分发 FAQ：`https://learn.microsoft.com/en-us/typography/fonts/font-faq`

## 五、原生 Kotlin/Compose/Hilt/KSP/Room 版本建议

### 推荐稳定基线（优先采用）

| 层 | 建议版本 | 核验/理由 |
|---|---:|---|
| JDK | 17 | AGP 8.x 官方要求 JDK17；主机已有 JDK17。 |
| AGP | 8.7.2 | 与 Kotlin/KGP 2.1.20 在 Kotlin 官方 fully-supported 表的交集内；比 8.9.2 更保守。 |
| Gradle | 8.9 | AGP 8.7.x 的官方配套范围；使用项目 wrapper 固定版本。 |
| Kotlin/KGP | 2.1.20 | Kotlin 官方 Gradle 兼容表列出 Gradle 7.6.3–8.12.1、AGP 7.3.1–8.7.2。 |
| Compose Compiler plugin | 2.1.20 | Kotlin 2.x 使用 `org.jetbrains.kotlin.plugin.compose`，版本与 Kotlin/KGP 对齐；不要再单独猜旧 `androidx.compose.compiler` 版本。 |
| KSP | 2.1.20-1.0.32 | Google Maven metadata 中存在该版本；适用于 Kotlin 2.1.20，但 Dagger 官方 KSP stable 门槛不满足，见下方说明。 |
| Hilt | 2.56.2 | Google Maven/Central 中存在；若暂时不使用 Hilt KSP，可使用 kapt。不能把该版本和 KSP 2.1.20 宣称为 Dagger 官方 KSP stable 组合。 |
| Room | 2.7.1 | Google Maven metadata 中存在的稳定版本；Room compiler 若用 KSP，应与 KSP 处理器配置一致。 |
| Compose BOM | 2025.04.01 | Google Maven metadata 中存在；用 BOM 对齐 Compose UI/Foundation/Material，不把 BOM 版本误当 Compose compiler 版本。 |
| Navigation Compose | 2.8.9 | Google Maven metadata 中存在的稳定版本；与该时间点的 Compose 基线可用，但最终应由 CI resolution/build 验证。 |
| CameraX | 1.4.2 | Google Maven metadata 中存在的稳定版本；若保留扫码/相机功能，执行真实设备权限和旋转测试。 |
| compileSdk/targetSdk | 35 | 适合新原生工程的目标 API；需在 CI 安装对应 platform/build-tools，并按业务 minSdk 单独决定。 |

### 如果必须采用 AGP 8.9.2

不要使用 `Kotlin 2.1.20 + AGP 8.9.2` 作为“官方 fully-supported”组合。可选矩阵：

- AGP 8.9.2 + Gradle 8.11.1 + JDK17；AGP 8.9 官方发布说明要求 Gradle 8.11.1 和 JDK17。
- Kotlin/KGP 2.3.10 + Compose Compiler Gradle plugin 2.3.10；Kotlin 官方兼容表列出 KGP 2.3.10 的 fully-supported 范围为 Gradle 7.6.3–9.0.0、AGP 8.2.2–9.0.0，覆盖 AGP 8.9.2/Gradle 8.11.1。
- KSP 2.3.10（Google Maven 中存在）；Hilt 2.60.1。Dagger 官方 KSP 文档明确 Dagger/Hilt KSP 自 2.60+ 与 KSP 2.3.9+ 起稳定。
- Room、Compose BOM、Navigation、CameraX 可继续分别固定为上表版本；先用 CI 的 dependency resolution、编译和 instrumentation test 验证，不能只因 artifact 存在就声称完整兼容。

**版本来源（官方）**：
- Android Gradle Plugin 8.9：`https://developer.android.com/build/releases/agp-8-9-0-release-notes`
- Kotlin Gradle/AGP/Gradle 兼容表：`https://kotlinlang.org/docs/gradle-configure-project.html`
- Compose Compiler Gradle plugin：`https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler`
- Dagger/Hilt KSP 稳定说明：`https://dagger.dev/dev-guide/ksp.html`
- KSP 官方入门：`https://kotlinlang.org/docs/ksp-quickstart.html`
- KSP Google Maven metadata：`https://repo1.maven.org/maven2/com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin/maven-metadata.xml`
- Hilt Google Maven Central metadata：`https://repo1.maven.org/maven2/com/google/dagger/hilt-android/maven-metadata.xml`
- Room Google Maven metadata：`https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml`
- Navigation Compose Google Maven metadata：`https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose/maven-metadata.xml`
- CameraX Google Maven metadata：`https://dl.google.com/dl/android/maven2/androidx/camera/camera-camera2/maven-metadata.xml`
- Compose BOM Google Maven metadata：`https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml`

上述 Maven metadata 只能证明指定 artifact/version 已发布；完整兼容仍需在锁定的 Gradle/AGP/JDK/compileSdk 环境中构建验证。

## 六、设备性能与 UI 测试可行性

### 当前状态

- 主机是 8-core arm64 Ubuntu proot，适合运行 Java/Gradle 等 arm64 工具，但当前没有已发现的 SDK platform-tools、emulator、aapt2 或 qmake/Qt，因此不能在此刻执行完整 Android build。
- 没有发现 AVD 配置；不能声称存在可启动的 emulator。
- 没有发现 adb；不能连接/安装 APK、采集 logcat、执行 Espresso/Compose UI test 或 benchmark。
- 因而本次没有性能指标、帧率、启动时间、内存、功耗或真机触控结论。

### 具备工具后的验证计划

1. **编译门禁**：Gradle wrapper 固定；`./gradlew --version`、`dependencies`/lockfile、`assembleDebug`、`lint`、`test`、`connectedCheck`。
2. **Compose UI**：关键页面用 `compose-ui-test` 验证弹窗、返回、导航、按钮可点击、WebView/相机权限状态；用语义节点而不是固定坐标。
3. **Android 设备**：用 `adb devices` 确认设备，安装 debug APK，执行启动/返回/浏览器 Intent/未知来源安装授权/旋转/后台恢复/连续取消重试；保存脱敏 logcat。
4. **性能**：在真实 arm64 设备优先；用 Macrobenchmark/AndroidX Benchmark 测冷启动、热启动、主页首帧和页面切换，结合 Perfetto/FrameTiming 检查掉帧与主线程阻塞。模拟器只能作为补充，不等同于真实设备性能。
5. **扫码/相机**：至少覆盖运行时 CAMERA 授权、相机拒绝/再次授权、旋转、低光二维码、返回后资源释放；ZXing-C++ 与 CameraX 两条链要分别验证。
6. **弱网/更新**：用可控测试服务器或 MockWebServer 验证超时、取消、断点/错误响应、下载校验；不要把在线 GitHub 访问当作稳定测试夹具。

**最终判断**：当前可做静态许可证审计和版本规划；原生 Android 的本地构建、设备 UI 验收和性能基准测试目前均不可宣称已执行。应优先在 CI 或配有完整 SDK/Qt/adb 的开发机上补齐并记录可复现证据。

## 七、补充 shell 盘点：本机 arm64 能否直接构建

主代理随后在同一工作区取得了可执行的只读 shell 盘点结果（未安装依赖、未触发 CI）：

- 主机为 aarch64/arm64；JDK 21 可用（此前文件盘点也确认 JDK17 和 JDK21 安装文件存在）。
- `/usr/lib/android-sdk` 目录存在，但 `build-tools` 为空；没有可直接使用的完整 SDK 工具链。
- 没有 `gradle`、`sdkmanager` 或 `adb` 可执行命令；系统能看到的 Gradle 仅为 **4.4.1**，不能满足 AGP 8.x（AGP 8.9.2 需要 Gradle 8.11.1，AGP 8.7.x 需要 8.x）。
- apt 仓库的 `android-sdk-build-tools` 候选为 **29.0.3 arm64**。这只能作为一个可能的本地原生 aapt/aapt2 旧包候选，既不是目标 compileSdk/build-tools 35，也不是旧 Qt CI 的 31.0.0；不能据此宣称新工程可构建。
- 本机没有可用 AVD/emulator 证据；没有 adb 就无法把 APK 安装到手机、采集 logcat 或做 connected/Compose UI 测试。

### Google SDK CLI 与 arm64 的边界

- Google 官方 Android Studio/Command-line Tools 页面提供的是常规 Linux 工具包；对本机 aarch64，不应假设该包内的 `sdkmanager`、`platform-tools/adb`、`build-tools/aapt2/zipalign`、`emulator` 能直接执行。标准 Linux 下载通常面向 x86_64，官方页面没有可供本次主机直接使用的 arm64 Linux 命令行工具包证据。
- Gradle 本身主要是 JVM 程序，JDK 17/21 arm64 可以运行 Gradle wrapper；但 Android Gradle Plugin 后续仍会调用 SDK 中的本机二进制（特别是 aapt2、zipalign、adb/emulator 相关工具），因此“有 arm64 JDK”不等于“能完成 Android APK 构建”。
- Debian/Ubuntu apt 的 arm64 旧 build-tools 或自行编译的单个工具，不能替代与 AGP、compileSdk、build-tools 版本配套的完整 Google SDK。尤其不能用 build-tools 29.0.3 作为 AGP8.9.2/compileSdk35 的稳定基线。

### 可验证的替代路径

1. **首选：x86_64 CI/构建机**。在 GitHub Actions 或其他 x86_64 Linux runner 安装 JDK17、Gradle wrapper 8.9/8.11.1、SDK platform/build-tools 35、对应 NDK，并把依赖版本锁定；这是当前最可靠的可复现路径。旧修复项目已有 Actions 流程可参考，但本次未触发它。
2. **本机 arm64 仅做轻量工作**：可用已有 JDK 做文本/源码审计；若另行取得纯 JVM Gradle wrapper，可先做依赖解析或非 Android 单元测试，但不能把成功等同于 APK 完整构建，除非同时证明每个 SDK native tool 的架构和版本。
3. **需要本机安装/调试时**：使用另一台 x86_64 Linux/Windows/macOS 主机运行 Android SDK/Gradle/模拟器，并通过 USB/Wi-Fi adb 连接实际 arm64 手机；APK 的目标 ABI 仍可设为 arm64-v8a。也可以使用 x86_64 CI 产物下载到本机，再通过有 adb 的设备执行安装和 UI 验收。
4. **不建议**在本机直接依赖 box64/QEMU/非官方二进制翻译来做发布构建；即使偶尔跑通，也不属于稳定、可验证的发布链，尤其不应用于签名发布。

### Artifact 证据边界

工作区已有的远程 Actions artifact 可证明“远程 x86_64 构建链成功”，不能证明当前 arm64 容器可构建：

- `/root/workspace/ZhiNengTiKa-release-artifacts/46044013692/build.log`：更新测试 `16 passed`、网络测试 `8 passed`、Gradle `BUILD SUCCESSFUL`。
- `/root/workspace/ZhiNengTiKa-release-artifacts/46044013692/verification.json`：APK 为 `arm64-v8a`，版本 1.8.1/versionCode 10801，且 `device_ui_tested: false`。
- 该 artifact 的 APK 是远程 Actions 产物；“产出 arm64 APK”表示目标 ABI，不表示构建主机必须是 arm64。
- `/root/workspace/ZhiNengTiKa-release-artifacts/46044013692/public-release-verification.json` 还记录过在线 APK 下载超时；它与本机 SDK 能否构建无关，也不能替代设备 UI 验证。

因此当前推荐结论是：**不要在此 arm64 容器上安装大型 SDK 或用 Gradle 4.4.1 尝试原生发布构建；把构建/签名/模拟器测试放到固定版本的 x86_64 CI 或开发机，把 arm64 手机作为真实 UI/安装验收设备。**
