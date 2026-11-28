# 构建与签名

## 固定基线

| 项目 | 版本 |
|---|---|
| JDK | 17（本次主机 OpenJDK 17.0.21.1 ARM64） |
| Gradle wrapper | 8.9 |
| Android Gradle Plugin | 8.7.2 |
| Kotlin / Compose Compiler / Serialization plugin | 2.1.20 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| 官方 SDK build-tools | 34.0.0 |
| Compose BOM | 2025.04.01 |
| CameraX / ZXing Java core | 1.4.2 / 3.5.3 |
| Android UI 测试：Espresso / runner / JUnit 扩展 | 3.7.0 / 1.7.0 / 1.3.0 |

wrapper 的 Gradle 8.9 分发 SHA-256 固定为：

```text
d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
```

Gradle 的常规依赖和插件分别固定版本；`gradle.lockfile` 记录实际解析结果。依赖构件校验和与交付中的依赖清单用于检测变更，不构成对上游二进制的独立复现或安全背书。仓库使用 Google Maven、Aliyun Maven Central 镜像和 Maven Central，首次构建需要联网；清空缓存后不能保证无网络构建。

## 标准 Android 开发机

Linux x86_64 / macOS / Windows 使用官方匹配宿主架构的 Android SDK。安装 JDK 17，设置 `JAVA_HOME` 和 `ANDROID_HOME`；SDK 许可由使用者阅读接受，不能通过复制他人的许可哈希代替。

```sh
sdkmanager 'platforms;android-35' 'build-tools;34.0.0'
./gradlew :protocol:test :app:assembleDebug
./gradlew :app:lintDebug :app:lintRelease
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:writeDependencyInventory
```

Debug 输出：`app/build/outputs/apk/debug/app-debug.apk`。UI 测试 APK 输出：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

`android.builder.sdkDownload=false` 防止 AGP 静默安装其他 SDK 包；缺少已声明的 SDK 包时构建会直接报错。

## 本次 Android 手机上 ARM64 Ubuntu / proot 构建

Google 常规 Linux SDK 包内的 aapt2 / zipalign / adb 是宿主工具，不能因为目标 APK 是 ARM64 就假设这些工具能在 ARM64 Ubuntu 中运行。以下流程保留官方 SDK 的真实版本和元数据，仅对 AGP 实际调用的 aapt2 作显式覆盖，不重命名或伪装 Debian 工具。

1. 安装 JDK 17 与 Python 3。运行：

   ```sh
   python3 tools/prepare-host-tools.py
   ```

   它取得固定的官方 Command-line Tools 12.0，以及第三方固定校验和的静态 AArch64 SDK 工具包；验证 aapt2 是 ELF64 little-endian AArch64、没有 `PT_INTERP` / `DT_NEEDED`，并执行 `version` 和 `daemon`。工具包源码 commit 为 `fe637bf075f1b734b4b01d281f9f58899245ed92`。二进制由 lzhiyong/android-sdk-tools 发布，本项目未独立重建或验证其构建可重现性。

2. 阅读官方 SDK 许可后，自行确认 sdkmanager 提示并安装：

   ```sh
   tools/android-sdk/cmdline-tools/12.0/bin/sdkmanager \
     --sdk_root="$PWD/tools/android-sdk" \
     'platforms;android-35' 'build-tools;34.0.0'
   ```

   本任务中用户已明确授权接受许可与安装这两个包；官方 SDK 包已成功安装。许可正文另存 `tools/sdk-terms/android-sdk-license.txt`。不在源码分发内附带整个 SDK 或其许可接受记录。

3. 执行构建：

   ```sh
   python3 tools/build-android.py -- \
     :protocol:test :app:assembleDebug :app:lintDebug
   ```

   脚本默认一次一个 Gradle worker；需要时可用 `--workers 2`。它在 AArch64 上显式传入 `android.aapt2FromMavenOverride`，日志保存在 `artifacts/logs/`。AGP 会提示此选项为 experimental，这是已知工具链边界。

   proot 主机上的 MockWebServer 使用 `JAVA_TOOL_OPTIONS=-Djdk.net.usePlainSocketImpl=true` 避开宿主 JVM socket 实现问题；这是主机 JVM 选项，不会改变 APK 的 Android 网络配置。脚本仅在未显式设置该变量时赋默认值。

手机内存紧张时可把以下参数附在 Gradle tasks 后：

```sh
-Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' \
-Pkotlin.compiler.execution.strategy=in-process
```

当前 SDK 目录还包含一次 AGP 元数据解析时自动下载的 x86 platform-tools 37.0.3；它未执行，也不是本机 adb 可用的证明。源码归档不包含此 SDK 目录。

## Release 签名

本次用户明确授权生成新的独立身份。私钥为 PKCS12 / RSA 3072 / SHA256withRSA，alias `native-release`，保存在**工程外**的专用目录；脚本默认位置是工程同级的 `.zhinengtika-native-signing/`。密码不写入源码、Gradle 文件、命令行或交付包。证书只是本版更新身份，不表示服务运营方认可本应用。

已有本次身份后：

```sh
python3 tools/build-android.py --release -- \
  :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest :protocol:test \
  :app:lintDebug :app:lintRelease \
  :app:writeDependencyInventory
```

在另一台开发机首次建立自己的身份，先明确选择独立身份并妥善准备备份，再运行 `--release --init-signing`。脚本拒绝把私钥放到工程内，拒绝覆盖已有/不完整身份。不要因脚本报错而重新生成更新用私钥。

也可自行提供环境变量：

```sh
export ZHINENGTIKA_KEYSTORE=/secure/path/release.p12
export ZHINENGTIKA_KEY_ALIAS=native-release
# 通过安全的环境注入设置两个密码变量，勿提交到源码：
# ZHINENGTIKA_STORE_PASSWORD / ZHINENGTIKA_KEY_PASSWORD
./gradlew :app:assembleRelease
```

不提供签名环境时，Gradle 允许产生 unsigned Release 供开发者自行签名；它不是本次已签名交付物。Release 启用 R8 与资源收缩，对应 mapping 也作为交付证据保留。未来版本更新需要同一私钥；本任务不把私钥复制到公共 Download，维护者需自行妥善备份。

## 安装包核验

```sh
python3 tools/verify-apks.py
```

脚本对 Debug、Release、UI 测试 APK 执行官方 `apksigner.jar` 验签、aapt2 metadata / manifest 检查、原生 zipalign 的 16 KiB 检查、ZIP CRC 与 ELF LOAD 对齐检查；输出 SHA-256、公开证书指纹、原生库清单与原始检查输出，不读取业务账号。此脚本本身不安装或运行应用；本轮独立取得的真机测试、Release UI 与性能证据见[测试报告](TESTING.md)。

## 设备验收准备

[验收脚本](tools/acceptance-device.py)可对完整交付目录生成命令计划；默认不启动 ADB。先运行[主机合成检查](tools/check-device-acceptance.py)，再参照[设备验收方案](docs/device-acceptance.md)确认测试设备、用户和包状态。其 `--run` 仅适用于当前用户为 0、没有其他用户且三个包均不存在的已授权测试设备；不适用于本轮已有安装和 user999 的手机。计划生成和主机合成检查均不算设备验收。

```sh
python3 tools/check-device-acceptance.py
python3 tools/acceptance-device.py \
  --delivery /path/to/unpacked-delivery \
  --output /path/to/new-plan.json
```

## 本轮重建与测试依赖修复

2026-11-28 的真机 Android 17 / API 37 暴露了原 Espresso 3.5.0 的 `InputManager.getInstance()` 反射初始化失败。按照官方[修复提交](https://github.com/android/android-test/commit/fddcda4adc8eaba515fbf2d790cf8dfe5c1b722d)，将 `androidTestImplementation` 的 runner 更新到 1.7.0、JUnit 扩展更新到 1.3.0，并显式依赖 Espresso core 3.7.0，更新锁和测试许可记录；未改业务实现、Compose 版本或 Release 运行依赖。compile/target/min SDK 保持 35/35/26。

此轮确实重新运行了完整签名构建，成功日志是 `artifacts/logs/20261128T043932.019568Z.log`：4 分 27 秒，135 项任务中 29 项执行、106 项 `UP-TO-DATE`；协议测试未重跑。三个最终 APK 已重新静态核验，Debug 字节与摘要不变；Release 体积相同，但版本控制元数据变化使摘要改变；新测试 APK 为 1,053,614 字节。不能把本轮写成只补文档并复用所有旧摘要，也不声称位级可重复构建。最终摘要及设备运行对应关系见[真机记录](docs/device-results-20261128.md)。

本轮通过另外明确授权的手机工具/Shizuku shell 在 user0 上保留数据更新 Release 与测试包，Debug 保持原包，随后两个 Compose 测试通过。干净单用户脚本没有执行。shell 环境缺少两个标准 classpath 变量的问题仅在确认无活动测试后、为当前命令从 zygote 导入解决，没有修改系统环境配置；不能将这一设备特定修正当作其他设备的通用安装步骤。

## 下载来源与固定摘要

| 工具 | 来源 | 校验 |
|---|---|---|
| CLI 12.0 / 11076708 | https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip | SHA-256 `2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258` |
| 静态 AArch64 SDK tools 36.0.0 | https://github.com/lzhiyong/android-sdk-tools/releases/download/36.0.0/android-sdk-tools-static-aarch64.zip | SHA-256 `cbe5446eedfe788d7fe01af3681f18d1544dce1bf5064e4847e150a95c8f44e4` |
| 官方 platform 35 base revision 2 | https://dl.google.com/android/repository/platform-35_r02.zip | 官方 SHA-1 `0bb560a90a7a2cbd0dd8348224d518b638fe7949` |
| 官方 build-tools 34.0.0 | https://dl.google.com/android/repository/build-tools_r34-linux.zip | 官方 SHA-1 `d6d58e0c6925a9e4d9a541e84cd1f405c2f9d2a9` |

源码不包含预编译依赖、SDK、大型主机工具下载或秘密材料。工具下载/仓库标签可能变化；脚本用固定摘要检测变化并停止。上述摘要只能固定本次取得的内容，不能替代上游可重复构建的证明。

## 生成交付目录

完成三个 APK、协议测试、两种 Lint、依赖清单和 APK 核验后，更新[依赖许可清单](docs/third-party-notices.md)，提交所有计划交付的源码与文档。然后运行[打包脚本](tools/package-delivery.py)，显式传入本次成功的构建日志：

```sh
python3 tools/collect-dependency-notices.py
python3 tools/collect-debug-test-notices.py
# 提交完整源码与文档，确保 Git 工作区干净
python3 tools/package-delivery.py \
  --build-log artifacts/logs/20261128T043932.019568Z.log
```

脚本检查 APK SHA-256 未在验签后改变、32 项协议测试全部通过、Debug/Release Lint 没有错误、依赖清单与许可清单一致；重新执行验收脚本的主机合成检查，并在交付报告中生成默认命令计划，全程不启动 ADB。本轮还要求 `artifacts/evidence/device-20261128/session-final.json` 与三包最终 SHA 一致，重新解析最终 instrumentation 完整 stdout，核对命令退出码 0、stderr 为空和两个确切方法成功；交付清单的 `deviceTestsExecuted` 据独立设备证据记为 true，并明确 `legacyRunnerExecuted: false`，不从纯静态 APK 报告推导运行结果。

在手机 Download/DSH-Go 下新建带时间与提交号的目录，拒绝覆盖同名交付。目录包含三个 APK、完整源码 ZIP、独立 Git bundle、报告 ZIP、R8 mapping、公开证书、构建/测试原始证据和 SHA256SUMS。私钥、密码、SDK 和依赖缓存不进入交付。

只补充文档或验收工具、复用此前已交付 APK 时，才额外传入 `--apk-source-commit 原交付源码提交`。脚本比较 `app/`、`protocol/`、`gradle/`、根 Gradle 配置、wrapper 和构建入口，任何差异都要求先重新构建；清单记录真实源码提交与构建状态。本轮测试依赖与锁已改变且已重新构建，不能再引用初版提交并声称构建输入未变。默认设备计划使用占位 serial，只供审查；它与独立真机证据分别保存，不将未执行计划记为完成。设备证据随报告放在 `evidence/device-20261128/`，不混入静态 APK 核验报告的执行标记。

可用 `git clone 交付的.git.bundle 新目录` 恢复独立历史；直接解压源码 ZIP 也可构建。复现 Release 时需要维护者妥善保存的同一签名私钥；源码和公开证书不能恢复私钥。交付清单记录源码提交与最终构建日志，设备与真实服务验证仍以[测试报告](TESTING.md)的状态为准。
