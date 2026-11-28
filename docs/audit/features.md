> 第一阶段只读审计存档。本文关于“未安装/尚不能构建”的结论描述当时环境；后续已获授权安装工具并生成 APK。当前状态请以工程根目录 BUILDING.md、TESTING.md 和交付证据为准。

# 智能题卡业务与迁移审计

## 范围与结论

- 审计对象：fork 固定提交 `ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c`（已发布修复版 1.8.1）的只读源码；对照 upstream 固定提交 `4d192e4e61a7f854c8f48be739e7cff513b6b242` 的提取快照。仓库、分支与提交记录见 [repository-baseline.json](repository-baseline.json)。
- 方法：读 GUI、Logic、StaticClass、启动入口、资源清单、Android assets 与上游 Core/Widgets/QML 实现；不是 README 摘要。运行时代码/服务未在本审计中执行；服务端可用性及真机行为不作推断。
- fork 没有 `.ui` 文件，GUI 由 C++ 手写 QWidget 创建；Android 还嵌 QtWebView/QML 单文件壳。上游有两个前端目标：QtWidgets 和单独的 QtQuick/QML 主应用。不要把 QWidget 与 QML 的导航/能力当作同一屏幕。
- 首要迁移决定：fork 具备完整的“上传作业答案”UI 和提交 API；upstream 新版两个前端均未提供该提交入口/answer-submit 请求。不能把它当作过时代码自动删掉，必须由产品决定明确保留或明确下线。
- 本次只在审计目录创建本报告；旧仓库没有修改。

## Fork 功能矩阵

| 领域 | 用户可见功能及流程 | 平台/状态 | 关键实现与边界 |
|---|---|---|---|
| 主导航 | Android 主 `题卡 → 解析 → 设置` 三页；桌面主 `题卡 → 设置`，桌面查到题卡后另开详情窗。Android 查题成功直接切到 `解析 → 答案及解析`。 | fork 已有 | [MainWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/MainWidget.cpp#L10)、[MainWidget.h](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/MainWidget.h#L20) |
| 题卡分类/列表 | 六个学科（语文、数学、英语、物理、化学、生物）及 `undefined` 自定义/访问列表；从打包的 all/latest 分类列表加载，设置可切优先最新（修改后询问重启）。 | Android/桌面 | [MultipleSubjectsTemplateListView.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/MultipleSubjectsTemplateListView.cpp#L10)、[MultipleSubjectsTemplateListModelList.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/MultipleSubjectsTemplateListModelList.cpp#L36)；`Resource/Resource.qrc` 列出桌面 all/latest 列表和内置题卡 JSON，Android 对应文件放在 `android/assets/templateList`、`android/assets/templateData`。Android QRC 本身仅列图标，业务 assets 不能漏打包。 |
| 名称搜索 | 在题卡页点“搜索”打开独立窗口；输入名称子串（不区分大小写）搜索打包的 all 分类；显示进度文本，可停止，点结果后确定，把编号带回题卡页并加载。搜索窗口关闭时会停线程并等待。 | 两平台 | [SelectWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SelectWidget.cpp#L92)、[SearchWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SearchWidget.cpp#L61)、[TemplateSearcher.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/TemplateSearcher.cpp#L19)。fork 搜索只扫内置 all 数据；不会检索最新列表以外的下载题卡历史。 |
| 编号/缓存取题 | 可手输或点列表项回填题卡编号；确定时优先内置 assets/Qt resources，其次运行时题卡缓存，再请求新教育接口；网络结果写本地题卡缓存，同时写 `name\ncode\n` 自定义列表。空编号、请求错误有提示。 | 两平台 | [SelectWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SelectWidget.cpp#L106)、[XinjiaoyuNetwork.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuNetwork.cpp#L32)。题卡接口使用当前首位账号或 public user 的 token 与 studentId；不是无账号的离线全题库。 |
| 扫码 | 题卡页“扫码”先让用户选拍照/选图片；拍摄经 Android 原生相机，文件可从系统文件选择；扫描成功填编号并继续取题，失败/取消恢复按钮状态。 | 两平台（相机 Android 路径） | [SelectWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SelectWidget.cpp#L191)、[CallAndroidNativeComponent.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/CallAndroidNativeComponent.cpp#L46)、[QRCodeScanner.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/QRCodeScanner.cpp#L19)。fork 会将图片作为 multipart 发往第三方 `2weima.com`；可选灰度 JPEG 压缩，超过 1600×900 时等比缩小至该边界、质量 70。API 临时签名信息写 `setting.json`，源码会 `qDebug` 解码结果。扫描不是纯本地隐私流程。 |
| 解析/答案/原题 | 详情有“答案及解析”（可切 `Only Answer`）和“原题”（按钮把当前题原文+答案解析拼接显示）；共享横向 `ALL` 和逐题/子题导航。Android 有全屏视图；Windows/桌面可“另存为”HTML。 | Android/桌面 | [TemplateDetailWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/TemplateDetailWidget.cpp#L12)、[AnswerAndAnalysisWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/AnswerAndAnalysisWidget.cpp#L4)、[QuestionWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/QuestionWidget.cpp#L4)、[WebViewWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/WebViewWidget.cpp#L7)、[AnalysisWebRawData.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/AnalysisWebRawData.cpp#L130)。数据结构要保留父题/子题、ordered 编号、题号显示、选择项、题干、答案和解析；含嵌套子题时页面对父题/子题的呈现方式并不相同。 |
| 上传作业（必须显式决策） | Android 详情有“答案及解析/原题/上传”三个子页；桌面详情也有上传页。登录时可见；未登录时隐藏/禁用。选择题单选/多选；主观题可拍摄/选多张图片/从剪贴板粘贴图片或 URL，单图可复制、删除、预览。题图先上传新教育文件服务取得 URL，再组成答卷。支持切换“正确答案优先”或“上传结果优先”回填；需逐题对应及保序。 | fork 已有；upstream 新版 UI 已移除 | [TemplateDetailWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/TemplateDetailWidget.cpp#L15)、[UploadWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadWidget.cpp#L8)、[UploadChildWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadChildWidget.cpp#L25)、[PlusSignLabel.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/PlusSignLabel.cpp#L36)、[PixmapLabel.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/PixmapLabel.cpp#L55)。提交 JSON 根含 `schoolId/studentId/templateCode/answers[]`，答案项含 `questionId/answer/rawScan`；提交前统计未答题并询问是否继续，再展示当前目标账号与不可撤回确认，成功/失败反馈。原始答卷可在文本窗查看/编辑/校验 JSON 后上传。服务端 answer endpoint 仍有效代码见 [UploadWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadWidget.cpp#L76)。不要把图片上传与答案最终提交混为一步。 |
| 账号 | 账号列表支持多账号；登录账号插到首位即为当前账号；从列表选其他账号会移至首位；登出当前账号后下一账号接管，无账号则用公共账号。上传 tab 依赖登录态。用户账号登录失败/缺部分实名学校资料有确认/提示。 | fork 已有 | [SettingWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SettingWidget.cpp#L99)、[UserData.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/UserData.cpp#L10)、[Setting.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/Setting.cpp#L99)。fork 没有当前账号有效性状态点/重新登录交互。 |
| 偏好设置 | 字体系列、应用字号、小字体字号和预览；优先列最新题卡；二维码图片压缩；固定学校/年级 combo；检查版本；常见问题/已知问题/关于/Qt/开源依赖/许可证。 | fork 已有 | [SettingWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SettingWidget.cpp#L9)。学校与年级控件各只有一个硬编码值，没有连接到题卡检索或账号状态；迁移不应误做成有效可选业务。字体预览不等于把所有现存控件即时换字体；外观组标注重启生效。 |
| 缓存/用户历史 | 题卡历史/在线数据写临时目录；设置页显示 Temp 递归大小，“删除缓存”二次确认后递归删整个 Temp 并退出。 | fork 已有 | [Global.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/Global.cpp#L7)、[SettingWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SettingWidget.cpp#L323)。这会一起删除 `templateList_undefined` 和 `TemplateFile/<code>`，不是只删图片；用户确认语义必须清楚。Android 原生相机结果用 app cache 文件，读取后删除；1.8.1 updater APK 在 native cache `/updates`，不一定计入 Global Temp 计数/删除范围。 |
| 导出/导入 | 桌面解析页另存 HTML；Ctrl+C 快捷键实际上把 HTML 写进 Temp 并把本地文件 URL 放剪贴板；答卷“查看原数据”是编辑并上传 JSON，不是导出备份。无账号/设置导入导出界面，无本地题卡 JSON 导入页面。 | fork 已有上述能力/边界 | [WebViewWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/WebViewWidget.cpp#L23)、[WebViewWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/WebViewWidget.cpp#L81)、[UploadWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadWidget.cpp#L216)。静态可见 HTML 包装处 `<title` 缺 `>`，且打开文件结果未校验；如迁移导出，应修复并保留 UTF-8/HTML 可离线浏览。 |
| 自动/手动更新 | 1.8.1 启动后事件循环运行再异步查询；手动“检查更新”按钮；对新版本非阻塞弹窗，可应用内下载、浏览器打开/复制发布页、取消；下载大小/SHA256 校验后再交安装；Android FileProvider/未知来源权限设置与返回重试。 | fork 1.8.1 已修复 | [main.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/main.cpp#L50)、[AutoUpdate.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Singleton/AutoUpdate.cpp#L108)、[AutoUpdate.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Singleton/AutoUpdate.cpp#L206)、[AutoUpdate.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Singleton/AutoUpdate.cpp#L298)、[CallAndroidNativeComponent.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/CallAndroidNativeComponent.cpp#L75)。详见“已有修复”。 |

## 导航与关键交互

### Fork

```text
Android 主页：题卡 ──查卡/扫码──> 解析
   ├─ 名称搜索弹窗 ─┐                 ├─ 答案及解析（Only Answer 切换）
   ├─ 列表点选编号 ─┼─> 确定 ────────>├─ 原题（显示答案按钮）
   ├─ 手输编号 ────┘                 └─ 上传（登录账号可见）
   └─ 设置（关闭时保存设置）

桌面：题卡（查卡后开独立详情窗口）──> 设置
详情窗：答案及解析 / 原题 / 上传；桌面可另存HTML
```

- 列表项点击是“回填编号”，不是立即打开题卡；最终要再点确定。搜索结果亦先选中、点确定。
- 题卡编号加载来源顺序：内置 assets/resource → 本地缓存 → 服务端。新在线卡会写临时离线数据与未分类历史。
- 单题导航项基于服务端 ordered；父题拥有 children 时展示主/子题组合编号；全部项是 `ALL`。翻页重置到对应 HTML。
- 上传照片会单独产生网络上传；最终提交还会发送答案 JSON。提交确认不可撤回。
- 全屏只用于 Android HTML；另存为只在桌面（LIMITED 编译配置除外）。

### Upstream 两种前端

- QtWidgets 主导航为 `题卡 → 学案 → 设置`；详情新版本只有“答案及解析/原题”，无 fork 作业提交页。学案页按学科→版次→模块→文件浏览，页大小 10，下一页继续，点文件下载；完成后自动调用系统打开。
- QML 手机主界面是响应方向/屏宽比的图标磁贴：首页含扫码、列表、资源、搜索、公告、设置；StackView 有工具栏返回/系统 Esc/Back。背景图从可配 URL 预取最多 8 张并每 5 秒轮换；切入子页暂停轮换，返回首页续播。
- QML 详情明确分为“答案和解析 / 答案 / 原题”，有横向 `All` 与逐题列表；显示题卡名、滚动文本、图片异步进度。没有上传 tab、手写答题、原始答卷编辑或提交确认。
- QML 扫码是摄像头实时取景、扫码线动画、切换摄像头、选择图片；ZXing-C++ 本地线程解码，不把 QR 图片上传给 2weima。权限由 QMLUtils 请求；返回编号后调用 TemplateFetcher。
- QML 公告有 unread 红点、点标题折叠/展开并标记已读；新未读公告获取完成后会自动 push 公告页。

## 用户状态与设置文件

### Fork `setting.json`

- 位置：`Global::configPath()/setting.json`；Android 用 Qt `AppConfigLocation`，Data 与 Temp 分开。进程启动加载；Android 登录、登出及设置改动多数立即存，退出再保存；桌面大多退出保存。[main.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/main.cpp#L18)、[Setting.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/Setting.cpp#L17)、[Setting.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/Setting.cpp#L64)
- 字段：`accounts[]`（accessToken、authorization、clientSession、password、schoolId、detailData、studentId、username）；`detailData` 是 base64 JSON 编码，并非加密；QR API scanner JSON；font/fontPointSize/smallFontPointSize；listLatestTemplatePreferentially；compressQRCodeImage。
- 密码以普通字符串写 JSON，没有系统 KeyStore 加密；用户口令、会话 token、学校/学生 ID 都是敏感状态，迁移时不能照搬“明文持久化+可见”设计。登录框还预填了演示账号字段并对同一字符串作特殊判断，点确定会 early-return 且没有恢复按钮；报告不复述其凭据字面值。
- 当前账户即列表第一项；public user 从 `DATABASE_DOMAIN` 异步载入 token/authorization/session/studentId。
- `SettingWidget` 的学校/年级选项硬编码且无业务使用；不应被视为多校切换功能。

### Fork 文件与缓存路径

| 数据 | fork 路径/生命周期 | 行为 |
|---|---|---|
| 配置/账号 | Android AppConfig `setting.json` | 登录和偏好 |
| Data 目录 | Android AppData | fork 当前路径创建，但题卡历史/数据主要不放这里 |
| 在线题卡历史 | Temp `templateList_undefined` | UTF-8 名称、编号两行一对；新项 append |
| 题卡原始 JSON | Temp `TemplateFile/<templateCode>` | 下次按编号直接离线读取；文件名来自编号 |
| 图片目录 | Temp/Image | 目录初始化；fork WebView 没有发现持久图片缓存业务调用 |
| 相机临时图 | app cache `callNativeCamera_result.jpg` | 取图后删除 |
| 更新 APK | app cache `/updates/<release asset>` | 1.8.1 通过 QSaveFile/校验再安装；与 Temp 全缓存按钮分离 |

## Upstream-only / 差异矩阵

| 能力 | Fork 1.8.1 | Upstream 4d192e4 | 迁移含义 |
|---|---|---|---|
| 前端组织 | 单 QtWidgets 主体；Android 内 QtWebView 包装 | Core + Widgets + QML 三目标；QML 有全套原生页面，Widgets 仍单独保留 | 先决定目标前端；不能只把当前 QWidget 原样当上游主界面。fork C++ 代码手写布局，查无 `.ui`。 |
| 用户历史/列表 | all/latest 六学科资源 + Temp `templateList_undefined`；历史 append、不去重 | 六学科静态列表 + 独立“用户历史”；访问新卡会去重并移动至头部，Data `templateList_UserHistory` 关机持久化 | 必须导入旧 Temp 历史到新 UserHistory，保持名称/编号、顺序及重复去重策略；否则用户会发现历史为空。 |
| 本地题卡数据 | 内置 assets/resource + Temp `TemplateFile/<code>` | 内置资源 + Data `TemplateFile/<code>` | 旧缓存若不迁移，可能只剩联网获取；建立路径桥接与缓存文件验证。 |
| 主观作业答卷上传 | 完整选择题/图片题、答案回填、编辑 JSON、确认及 answer submit API | 新版 QML/Widgets 都无答案提交 UI；上游仍留图片上传 helper 与 TemplateAnswerData/tree model，但没有 answer endpoint submit 调用/界面 | 这是 fork-only 商业能力；必须由产品决定保留/下线。若保留需恢复作业页、答题模型、图片上传、未答计数、账号切换、不可撤回确认和 API schema。 |
| 二维码 | 拍照/文件；上传第三方解码；压缩开关/凭据 | ZXing-C++ 本地解码实时帧或图片，切摄像头，异步扫描 | 上游路线省掉外传图片和 API token，建议优先；仍需保留摄像头/图片文件、失败恢复、权限和模板编号回填。 |
| 公告 | 无 | QML 公告页、版本/平台过滤、未读状态、红点、首开自动打开；read hash 写 Config/AnnouncementAsRead | 新增远端公告状态与已读迁移；列表正文来自 DATABASE_DOMAIN，离线错误展示不能锁主 UI。 |
| 学案资源下载 | 无 | Widgets `学案` 页和 QML `资源` tile；服务端目录按学段/科目/版次/模块，10条分页；保存 Download/ZhiNengTiKa/<层级>/<文件名>，Widgets 自动打开、QML toast 显示路径/进度 | 属于 upstream-only 真正下载业务；需要存储权限、进度、重试/错误状态、路径与下载后打开能力。后端初始化目前固定 schoolId。 |
| 设置 | font+字号、小字体、排序、二维码压缩、学校年级占位、缓存、更新、FAQ/About | font/字号、QML style、animeImageUrl，自定义背景 URL/预置图源；QML可删导入题卡。账号状态查询/失败重新登录。 | 旧 `smallFontPointSize/listLatestTemplatePreferentially/compressQRCodeImage/apiQRCodeScanner` 在上游没消费；新 `qmlStyle/animeImageUrl` 是新增键。迁移要明示废弃项并保留可选项/主题体验。 |
| 更新 | fork 1.8.1 GitHub Releases 更新检查已修复并连到 UI | 上游 ApplicationUpdateChecker/LibraryUpdateChecker 类存在，但 QML main 未注册；Widgets 版本检查按钮逻辑已注释；用户不可触发 | 不能用上游 core 类替换已修复 fork updater；继续保证版本、release资产、超时、校验、浏览器/安装状态与入口连通。 |
| 解析显示 | 两视图：答案解析（内嵌纯答案切换）/原题（当前题显示答案解析），Android 全屏 | QML 三个显式视图：答案解析/纯答案/原题；QML 进度、滚动；Widgets 两视图、无全屏（上游 QWidget） | 列明平台差异；纯答案、逐题/All、原题显示答案、图片公式加载都属功能而非皮肤。 |
| 题卡搜索 | thread 搜索 all 静态列表，可取消；本地资源详情走共用加载器 | 搜索静态模板列表和 UserHistory；TemplateSummary 包括学科、卷册 | 扩展历史查找，不要改成仅编号；结果展示/列表选中/确认三步关系要明确。 |
| 缓存清理 | 删除整个 Temp 并退出 | QML 设置项仅删除 Data TemplateFile 与 UserHistory 并提示重启；Widgets 有学案下载，不同于题卡缓存 | 明确提供“清临时文件”与“删离线题卡历史”两个分级动作，避免数据意外丢失。 |

## 迁移对照：持久化键与旧数据

| Fork 当前状态 | Upstream 字段/路径 | 建议 |
|---|---|---|
| `accounts` 中账号/token/session/password/schoolId/studentId/detailData | 同名字段仍直接写 `setting.json`；模型本身兼容 | 兼容读取旧格式，但不导出或日志打印凭证；首次迁移时将密码转入 Android Keystore/平台安全存储、短期 token 按需重登；先保留原文件备份/回滚，避免静默清空账号。Base64 detailData 必须按 JSON 解码，而不是误当加密。 |
| `font`, `fontPointSize` | 同名字段 | 直接兼容；验证字体缺失时 fallback；字号有效范围夹紧。 |
| `smallFontPointSize` | upstream 不读取 | 可映射到 QML 小字/列表密度偏好，或保留旧值供返回；不可悄悄忽略造成明显视觉变化。 |
| `listLatestTemplatePreferentially` | upstream 没有该 preference，改为学科+UserHistory | 旧用户仍可访问学科全量列表；latest flag 可归档而不阻断主列表。 |
| `compressQRCodeImage`、`apiQRCodeScanner` | upstream ZXing 本地识别，不消费这两键 | 不迁移临时 QR API 凭据；说明扫码隐私路线变化。压缩选项若仍提供，限定用于本地解码前降采样，不上传。 |
| 无 | `qmlStyle`, `animeImageUrl` | 安全检查/白名单已知图源；默认样式按设备可用样式 fallback；允许用户自定义图片 URL 前需明确远端请求与隐私。 |
| Temp `templateList_undefined` | upstream Global 迁移代码只改名 `Data/templateList_undefined` → UserHistory | fork 的实际旧文件在 Temp，不是 Data；上游自动 rename 不会碰到它。自定义迁移需读 Temp，按两行记录格式兼容异常末尾行，合并到 Data/UserHistory，按 templateCode 去重并保序。 |
| Temp `TemplateFile/<code>` | upstream `Data/TemplateFile/<code>` | 逐文件校验、复制或安全重用；别在第一次启动时删旧 Temp。源代码当前没有旧目录自动迁移。 |
| 公告已读无 | Config `AnnouncementAsRead` | 新功能独立持久化；按标题+detail 摘要匹配已读公告，不要迁入旧账号数据。 |
| 无 | Download `ZhiNengTiKa/<subject>/<edition>/<module>/<filename>` 学案文件 | 作为用户外部下载保存，不能被“清 App Cache”误删；路径/存储授权另行处理。 |

## 已有修复（fork 1.8.1）

来自当前 fork 代码与已完成 Actions 记录；不表示真机验收：

1. 根因修复：启动不再在最低版本检查时禁用整个主窗或在 `a.exec()` 前 `exit(1)`；`QTimer::singleShot(0, ...)` 在主事件循环开始后异步检查。离线/请求失败/取消不再让所有主控件失去响应。[main.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/main.cpp#L50)
2. 替换旧加密/Gitee最低版本门禁为 fork GitHub Release；严格三段版本比较、唯一 ARM64 release asset 匹配。检查与下载异步、有总时限/无活动超时、取消、有限大小、校验 SHA256；通过后才落盘安装。[UpdateRelease.h](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Singleton/UpdateRelease.h#L22)、[AutoUpdate.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Singleton/AutoUpdate.cpp#L108)
3. 弹窗改非阻塞 `open()`，下载/浏览器/复制 URL/取消路径都有动作；Android 浏览器 Intent 改 HTTPS URI + browsable；JNI 异步返回只代表请求排队，不虚报打开或安装完成。FileProvider 只给 cache APK，未知来源设置可返回再试。[AutoUpdate.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Singleton/AutoUpdate.cpp#L206)、[IntentActivity.java](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/android/src/com/LFWQSP2641/xinjiaoyu/IntentActivity.java#L1)
4. 删除非法安装权限 `INSTALL_PACKAGES`，保留用户授权安装的权限；更新文件仅在 App Cache `/updates`。
5. CI run `46044013692`：Qt 6.4.3 updater 测试 16/16、Network 测试 8/8，ARM64 release 编译和签名通过。本地核对 APK package、1.8.1/10801、arm64-v8a、SHA256 `1280df0caa5010960007e6c7a678fb44f3828ca241e1549038b06a0b104d52d3`；证书与 fork 1.8.0 一致。没有完成真机逐页触控、真实下载后 browser/install 流程。

## 安全、数据外发与静态风险

- **不要引用任何账号口令、token、签名或真实用户字段样例。** 源码内有默认演示登录字段，但本报告不复述其字面值。
- 旧 fork `setting.json` 明文存 password/token/session；QML upstream 登录密码字段虽显示遮罩，但 `SettingWidget.qml` 同屏展示 accessToken/authorization/clientSession/student/school ID；upstream `AccountManager` 还把账户状态响应 `qDebug()`。设计迁移时只显示账号描述和脱敏状态，按需请求凭证，避免日志写服务响应/QR 内容。
- fork 二维码图片发送第三方 2weima 处理，答案图片发送新教育上传 API，最终答案 JSON 发 `/student/homework/answer`；应在UI准确说明图片外发与答案提交不可撤回。upstream ZXing 本地扫码是隐私改善。
- 题卡正文和答案 HTML 来自服务端/内置资产，再交给 Qt WebView/QML 富文本；迁移保留公式/图片与链接加载时，应限制不可信 HTML/URL scheme、下载路径和缓存命名，别直接拼可控 HTML/路径。
- 网络多数业务仍在 GUI 线程用同步 `Network::postData/getData` 嵌套事件循环（超时只避免无限等待，并未保证交互不卡）；1.8.1 修复只保证 updater 不禁用主窗，不代表登录/取题/上传/二维码扫描都非阻塞。
- 静态问题：fork 登录框预填特殊字段与哨兵判断触发时不会恢复确定按钮；用户名为空/错误时已有局部不同路径。搜索空字符串会命中所有名称。自定义列表模型的插行范围参数固定 `size,1` 与当前 item 数不一致；导入列表行尾无换行时裁掉最后字符。均建议迁移实现时写用例，不应复制缺陷。
- 用户数据危险动作：fork “删除缓存”实为删除整个 Temp 然后退出；upstream “删除用户已导入题卡”会清历史和离线题卡文件；upstream Global 启动会删除 Data 下 `Resource`、`Image` 文件夹。UI必须给准确范围与恢复提示。
- 取题 HTML 导出页头缺少 title 标签的 `>` 且文件 open/write 错误不处理；Ctrl+C 实际复制 file URL。若需保留，应把“导出HTML”和“复制链接/内容”拆清楚并修复。

## 源码索引

- Fork 启动/导航/详情：[main.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/main.cpp)、[MainWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/MainWidget.cpp)、[TemplateDetailWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/TemplateDetailWidget.cpp)
- Fork 设置/用户：[SettingWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SettingWidget.cpp)、[UserData.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/UserData.cpp)、[Setting.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/Setting.cpp)、[Global.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/Global.cpp)
- Fork 题卡/上传/扫描：[SelectWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/SelectWidget.cpp)、[AnalysisWebRawData.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/AnalysisWebRawData.cpp)、[UploadWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadWidget.cpp)、[QRCodeScanner.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/QRCodeScanner.cpp)
- Upstream main/QML：`upstream-reference/ZhiNengTiKaQML/Resource/qml/main.qml`、`SettingWidget.qml`、`QRCodeScannerWidget.qml`、`TemplateDetailWidget.qml`、`AnnouncementWidget.qml`、`DownloadResourceFileWidget.qml`
- Upstream Core：`upstream-reference/ZhiNengTiKaCore/src/Logic/AccountManager.cpp`、`ResourceFileFetcher.cpp`、`AnnouncementManager.cpp`、`TemplateHandle/TemplateFetcher.cpp`、`StaticClass/Global.cpp`
- Upstream Widgets：`upstream-reference/ZhiNengTiKaWidgets/src/GUI/MainWidget.cpp`、`ResourceFileWidget.cpp`、`TemplateDetailWidget.cpp`

## 验收重点

迁移验收至少覆盖旧 setting.json 账号兼容及安全落盘、旧 Temp 历史/离线卡拷贝、空/失效/公共用户登录、账号换序/登出、离线/网络题卡、all/逐题/子题/纯答案/原题/全屏、QR图片与实时ZXing、本地缓存清理差异、答卷单选多选/主观题多图/回填/JSON编辑/未答提醒/二次确认/成功失败、HTML导出、公告已读/无网络、学案分页下载/取消/权限/打开，以及更新下载失败不能挡住主界面。所有自动化通过都不能代替相机、浏览器、安装器与真实触控实机验收。
