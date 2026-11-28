# 第三方组件与许可证声明

## 说明

- 本项目原创代码采用 AGPLv3（见根目录 `LICENSE` 与应用内 `assets/legal/AGPL-3.0.txt`）。
  下列第三方依赖各自按其原始许可证授权，**不因本项目采用 AGPLv3 而改变许可**。
- 版本依据本仓库 `build.gradle.kts` / `app/build.gradle.kts` / `protocol/build.gradle.kts`
  当前声明。依赖在构建时由 Gradle 解析；完整、逐字的第三方 LICENSE/NOTICE 应以实际解析到的
  Maven/Google Maven 构件内附文件为准。
- 应用内提供已核验全文的许可证：`assets/legal/AGPL-3.0.txt`（项目许可，完整逐字正文，等同于
  根目录 LICENSE）、`assets/legal/Apache-2.0.txt`（Apache 官方站点
  https://www.apache.org/licenses/LICENSE-2.0.txt，覆盖 AndroidX/Compose/Kotlin/OkHttp/Coil/
  CameraX/ZXing core 等）、`assets/legal/MIT-jsoup.txt`（jsoup 官方站点
  https://jsoup.org/license，2026-11-28 获取 HTTP 200）。其余依赖仅列出许可证名、来源与核对
  方式，**不复制无法从可核验来源取得的条款文本**。ML Kit 已从依赖移除，不再列为其条款。
- 以 `releaseRuntimeClasspath` 的实际解析结果生成 `docs/dependency-artifacts.json`（构件坐标、SHA-256、
  随附许可条目及其摘要）和应用内 `assets/legal/Third-party-artifact-notices.txt`。
  部分构件没有随附 LICENSE/NOTICE，此情况在清单中明确标注；不能将文件提取等同于完整法律审查。

## 一、Apache License 2.0

以下依赖按 Apache-2.0 授权，完整文本见 `assets/legal/Apache-2.0.txt`：

| 组件 | 声明版本 | 用途 |
|---|---:|---|
| AndroidX core-ktx | 1.15.0 | Kotlin 扩展 |
| AndroidX activity-compose | 1.10.0 | Compose Activity 集成 |
| AndroidX lifecycle-runtime-ktx / -compose | 2.8.7 | 生命周期 |
| AndroidX navigation-compose | 2.8.9 | Navigation Compose |
| Compose BOM | 2025.04.01 | Compose 版本对齐 |
| Compose UI / ui-tooling / ui-tooling-preview | BOM | Compose UI 与预览 |
| Material3 | BOM | Material 3 组件与主题 |
| material-icons-extended | BOM | 图标 |
| ui-test-junit4 / ui-test-manifest | BOM | Compose UI 测试 |
| Kotlin coroutines（android/core/test） | 1.10.1 | 协程 |
| kotlinx-serialization-json | 1.8.0 | JSON 序列化 |
| OkHttp / MockWebServer | 4.12.0 | 网络与本地测试服务器 |
| Coil（coil-compose） | 2.7.0 | 图片加载 |
| CameraX（camera-camera2 / lifecycle / view） | 1.4.2 | 相机预览与扫码输入 |

来源与核对：AndroidX 各 artifact 的 POM 与 source jar 内 LICENSE（Google Maven，
https://dl.google.com/dl/android/maven2/）；Kotlin 依赖见 https://github.com/JetBrains/kotlin；
OkHttp 见 https://github.com/square/okhttp；Coil 见 https://github.com/coil-kt/coil；
CameraX 见 https://developer.android.com/jetpack/androidx/releases/camera。

## 二、MIT License

| 组件 | 声明版本 | 用途 | 核对来源 |
|---|---:|---|---|
| jsoup | 1.19.1 | HTML 可见文本提取（选项字母） | https://jsoup.org/ ；构件 POM / LICENSE（Maven Central：`org.jsoup:jsoup`） |

jsoup 官方采用 MIT License。已从官方 https://jsoup.org/license 取得全文，附在
`assets/legal/MIT-jsoup.txt`，包含 Jonathan Hedley 的版权归属。

## 三、Eclipse Public License 1.0（测试工具）

| 组件 | 声明版本 | 用途 |
|---|---:|---|
| JUnit 4 | 4.13.2 | JVM 单元测试与 Compose 测试断言 |

JUnit 4.13.2 的[官方许可页](https://junit.org/junit4/license.html)明确标为 **Eclipse Public License 1.0**，
不是 LGPL 或 JDK 的 GPLv2 with Classpath Exception。它用于 JVM 测试与单独的 instrumentation 测试包，
不进入 Release 运行时依赖；完整文本以构件随附的 `LICENSE-junit.txt` 为准。

AndroidX test（`androidx.test.ext:junit:1.3.0`、`androidx.test:runner:1.7.0`）及
Espresso（`androidx.test.espresso:espresso-core:3.7.0`）按 Apache-2.0
授权，仅用于 `androidTest`，不随生产包使用。Hamcrest `core` / `library` / `integration` 1.3 是测试传递依赖，采用 BSD 3-Clause；实际缓存 JAR 的版权、条件和免责声明逐字保留在[Debug/测试依赖声明](debug-test-notices.txt)，对应构件和文本摘要见[补充清单](debug-test-artifacts.json)。这份补充按锁文件范围收集缓存构件，不冒充 APK 内打包类的完整清单。

## 四、ZXing Java core（Apache-2.0）

| 组件 | 声明版本 | 用途 |
|---|---:|---|
| com.google.zxing:core | 3.5.3 | 本机二维码解码（纯 Java） |

扫码现由 CameraX 取景 + ZXing Java core 3.5.3 在设备端完成：CameraX 的 Y plane 构建
`PlanarYUVLuminanceSource`，相册图片经有界采样后构建 `RGBLuminanceSource`，均由
`MultiFormatReader` 解码。ZXing 解码器本身是纯 Java，**不依赖专有 ML Kit、不做模型下载、
无遥测**；CameraX 与 Compose 的传递依赖仍包含 native `.so`，详见 APK 核验清单。二维码图片
不上传到第三方。上游 ZXing（`https://github.com/zxing/zxing`）采用
Apache License 2.0，完整文本见 `assets/legal/Apache-2.0.txt`。**尚未在真实相机上实测，
不声称扫码成功。**

注：旧上游 C++ 项目使用的 ZXing-C++ 与这里的 ZXing Java core 是不同实现；本原生版只使用
Java core 3.5.3 构件，未使用 ZXing-C++ 及其 native 库。

## 五、构建工具链（非运行时依赖）

| 组件 | 版本 | 许可证 / 说明 |
|---|---|---|
| Android Gradle Plugin | 8.7.2 | Apache-2.0，Google |
| Gradle（wrapper） | 8.9 | Apache-2.0，Gradle 社区 |
| Kotlin / KGP / Compose compiler plugin / Serialization plugin | 2.1.20 | Apache-2.0，JetBrains |
| JDK 17 | 17.x | GPLv2 with Classpath Exception（OpenJDK） |
| Android SDK platform 35 / build-tools 34.0.0 | 按官方包 | 见 Google SDK 许可；由用户按明确许可安装 |
| 静态 aarch64 aapt2（lzhiyong/android-sdk-tools 36.0.0） | 36.0.0 | Apache-2.0 上游源码；此处仅作本机构建工具覆盖，不随 APK 分发 |

正式构建说明、固定下载摘要、签名与工具链边界见 [BUILDING.md](../BUILDING.md)；
实际测试范围见 [TESTING.md](../TESTING.md)。

## 六、旧项目遗留组件（本重写版未使用）

旧 fork/upstream 曾使用以下组件；本原生重写版**不依赖、不随包分发**它们，列出仅供审计追溯：

| 组件 | 旧许可证线索 | 本版状态 |
|---|---|---|
| Qt（LGPLv3/GPLv3 双许可，模块可能不同） | LGPLv3 等 | 未使用 |
| Qt-AES（固定 commit） | Unlicense | 未使用；协议加密在新项目中独立实现 |
| android_openssl（KDAB） | Apache-2.0 | 未使用 |
| ZXing-C++ | Apache-2.0 | 未使用；本版改用 ZXing Java core 3.5.3（纯 Java，无 native .so）本地扫码 |
| Microsoft Yahei UI.ttc | 无再分发许可 | 未内置，改用系统字体 |
| 旧题卡数据 `Resource/templateData` | 归属内容权利人，未澄清 | 未内置 |

## 七、聚合方式与分发边界

`tools/collect-dependency-notices.py` 根据 `writeDependencyInventory` 实际解析清单校验构件 SHA-256，
提取 jar/aar 中的 LICENSE / NOTICE / COPYING，并保存到应用的开源说明。构件未内附文本时，
清单明确标记，并列出缓存 POM 的原始许可声明及其 SHA-256；不推断遗漏的版权归属。

JUnit 4.13.2 的 `LICENSE-junit.txt` 已从实际缓存构件提取为 `assets/legal/EPL-1.0-JUnit.txt`。
构件 SHA-256 为 `8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3`，
许可文本 SHA-256 为 `9648bb2891b9813970bddb68d4be8a5e6ec8280d0180a53dfb29236b579c55bb`。
JUnit 只用于测试；Release 的实际解析清单与 Debug/测试的锁文件补充范围分开记录。补充声明由 `tools/collect-debug-test-notices.py` 生成，不改变应用内 Release 许可资产或已验签 APK。

## 八、设置页头像与图标

1.0.3 设置页内置 LFWQSP2641、2034974607 与 OpenAI 的公开 GitHub 头像，显示时无需联网。原图来源、获取时间及 SHA-256 见[头像资源记录](contributor-avatars.json)；头像与标识权利归各自权利人所有，不适用项目代码的 AGPL 许可，也不表示背书。

仓库入口使用 Primer Octicons 的 [GitHub mark](https://github.com/primer/octicons/blob/main/icons/mark-github-16.svg)，转换为 Android VectorDrawable；其 MIT 原始版权和许可全文附于应用内 `assets/legal/Contributor-assets.txt`，可从“设置 → 开源说明”查看。

完整对应源码与 APK 一同交付，并在原仓库的 [原生版 GitHub Release](https://github.com/2034974607/ZhiNengTiKa/releases/tag/native-v1.0.3) 公开分发；本项目未提供任何外部业务服务。APK 自身没有 GitHub 上传、遥测或广告 SDK。源码中的业务兼容密钥不构成安全边界，不在本文件披露。发布记录与报告的时间范围见[发布说明](github-release.md)。
