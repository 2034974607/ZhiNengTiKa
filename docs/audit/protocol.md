> 第一阶段只读审计存档。本文关于“未安装/尚不能构建”的结论描述当时环境；后续已获授权安装工具并生成 APK。当前状态请以工程根目录 BUILDING.md、TESTING.md 和交付证据为准。

# 新教育智能题卡协议审计

## 范围与结论

- 审计基准：fork `master` 的 `ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c` 与 upstream `dev` 的 `4d192e4e61a7f854c8f48be739e7cff513b6b242`；比较关系记录在 [repository-baseline.json](repository-baseline.json)。
- 审计方法：静态检查两份 C++ 实现及调用方；没有请求真实服务，没有使用账号凭据，也未做设备网络验证。协议可用性、服务端当前响应及账号权限因此仍未验证。
- 登录、题卡请求、AES/MD5 算法与图片上传在 fork/upstream 中一致。答案提交只在 fork 1.8.1 中观察到，是应保留的业务能力。
- 本报告故意不含任何账号凭据、token、会话值或 AES 密钥。客户端内置对称密钥不能视为安全边界。

## 端点与协议

| 流程 | 端点/方法 | 静态观察到的请求与响应 |
|---|---|---|
| 登录 | `POST https://www.xinjiaoyu.com/api/v3/server_system/auth/login` | 请求 JSON 含 `username`、`password`、`t`；用户名和密码各自经 AES 加密后 Base64 编码。成功响应按 `code == 200` 处理，读取 `data.accessToken`、`data.token`、`data.info` 以及 `data.info.school.studentId/schoolId`。登录 Authorization 使用固定 `JBY` 前缀值。证据：[UserData.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/UserData.cpp#L10)、[AccountManager.cpp](https://github.com/LFWQSP2641/ZhiNengTiKa/blob/4d192e4e61a7f854c8f48be739e7cff513b6b242/ZhiNengTiKaCore/src/Logic/AccountManager.cpp#L75)。 |
| 题卡 | `GET https://www.xinjiaoyu.com/api/v3/server_homework/homework/template/question/list?templateCode=…&studentId=…&isEncrypted=true` | 需要通用认证头；成功 JSON 通过 `data.questions[0].questionsAnswers` 取得题目。证据：[XinjiaoyuNetwork.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuNetwork.cpp#L32)、[上游同名实现](https://github.com/LFWQSP2641/ZhiNengTiKa/blob/4d192e4e61a7f854c8f48be739e7cff513b6b242/ZhiNengTiKaCore/src/StaticClass/XinjiaoyuNetwork.cpp#L32)。 |
| 作业图片上传 | `POST https://www.xinjiaoyu.com/api/v3/server_homework/homework/upload` | `multipart/form-data`，文件字段名为 `file`。成功响应中的 `data.accessUrl` 与 `data.filename` 被组合成后续答卷使用的文件 URL。它与最终答卷提交是两个独立的网络事务。证据：[XinjiaoyuNetwork.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuNetwork.cpp#L121)。 |
| 答卷提交 | `POST https://www.xinjiaoyu.com/api/v3/server_homework/student/homework/answer` | fork 专有 UI/调用。根对象为 `schoolId`、`studentId`、`templateCode`、`answers[]`；每项为 `questionId`、`answer`、`rawScan`。选择题把选项字母合并放在 `answer`；主观题将一张或多张上传图片 URL 以逗号连接放入 `rawScan`。成功响应的代码检查在旧实现中依赖整段 JSON 字符串完全相等。证据：[UploadWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadWidget.cpp#L76)、[UploadChildWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadChildWidget.cpp#L94)。 |

## 认证头与摘要

普通学生端请求沿用下列头部（头名称大小写按 HTTP 语义处理）：

- `app: student`、`client: android`、`Content-Type: application/json`、`User-Agent: okhttp/4.9.3`。
- `Authorization`、`accessToken`、`clientSession` 来自当前学生账号或旧程序加载的公共用户状态。
- `t` 为毫秒 Unix 时间戳字符串。
- `encrypt` 为 `MD5(UTF-8("jbyxinjiaoyu" + t + clientSession + "android"))` 的小写十六进制文本；它不是 HMAC。实现使用字节串直接拼接后计算 MD5。[XinjiaoyuNetwork.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuNetwork.cpp#L7)、[XinjiaoyuEncryptioner.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuEncryptioner.cpp#L20)。
- 登录请求在其 JSON 正文和头部中分别生成 `t`；旧实现存在两者取值可能相差数毫秒的窗口。新实现应一次生成并复用，除非服务端实测要求相反。
- 旧公共用户凭据从 `DATABASE_DOMAIN` 下的若干独立资源读取；这不是可嵌入新 APK 的稳定公共 API，也不得复制其实时凭据、缓存值或历史个人数据。

## 加密及题卡解码

- 两份旧代码都声明 AES-256 / ECB / PKCS7，并以 32 个 ASCII 字节作为 AES key；不是将该 ASCII 文本先按十六进制解码。输入 UTF-8 字节经 PKCS7 padding 与 AES 加密后使用标准 Base64。解密题卡字段时按 Base64 解码、AES 解密并去 padding。Android JCE 中 `AES/ECB/PKCS5Padding` 对 16 字节分组提供等价的块填充行为，但需通过字节级测试验证互操作。[XinjiaoyuEncryptioner.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuEncryptioner.cpp#L3)。
- 模板中 `question.answer` 和 `question.answerExplanation` 是加密文本；其余题干/选项字段由旧实现直接消费。题目对象包含 `id`、`content`、`optionA` 等选项字段；题卡条目有 `ordered`、嵌套 `question` 与可选 `childQuestionList`。显示编号和题目数不能只靠顶层数组序号推导。解析证据：[XinjiaoyuNetwork.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuNetwork.cpp#L40)、[AnalysisWebRawData.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/AnalysisWebRawData.cpp#L130)。
- 新实现应以 JSON parser 读取 `code`，校验必需字段、数组边界和解密失败；不要沿用 `response.mid(8, 3)` 或整段响应字节完全匹配。兼容未知/空字段时保留原文错误上下文，但不得将 token、密码或完整用户响应写日志。
- 题干/答案可能包含 HTML、图片链接与公式片段。渲染应限制 URL scheme、禁止危险导航，并避免将服务端字符串直接拼入自建 HTML。

## 旧实现缺陷与新实现约束

1. 旧题卡响应仅从 `questions` 的第一个元素取数组，子题解密处理只覆盖一层；新解析应支持嵌套结构并对缺字段、空数组、无效 Base64 与 padding 错误返回可解释的失败状态。
2. 登录靠 `startsWith("{\"code\":200,")`，题卡和图片上传靠固定字节偏移判断成功，答案提交靠固定响应全文。统一改为 JSON parser + 明确的 HTTP 状态、业务 `code` 与字段校验。
3. 旧网络层在若干操作中同步等待。新网络请求应异步执行、支持取消和明确超时；读取/图片下载与 UI 状态解耦。
4. 答卷 POST 超时或连接中断时，客户端无法知道服务端是否已处理。标记为“结果未知”，展示人工查询/重新确认途径；不得自动重试这个可能不可逆的写操作。
5. 图片上传可安全按明确的上传响应错误重试，但只有在服务端确认幂等规则后才能自动重试。上传成功而答卷失败可能留下孤立文件；UI需保留本地答卷草稿并允许用户检查。
6. 最终提交前显示目标账号、未答数量及不可撤回确认；不要让编辑原始 JSON 的功能绕过 schema 校验、账号确认或状态管理。
7. TLS 证书校验必须保持系统默认开启。日志与崩溃报告应过滤 Authorization、accessToken、clientSession、用户名、学生/学校 ID、答卷内容及上传 URL。

## QR 码路径

- fork 把扫码图片发送到第三方 `2weima.com`：先访问其页面与签名接口，再将图片 POST 到 `img.2weima.com`，从响应 JSON 的 `data` 取解析结果。旧实现会缓存临时签名参数，并把解析响应写入调试日志。[QRCodeScanner.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/QRCodeScanner.cpp#L19)。
- upstream 新 QML 的相机帧/图片走本地 ZXing-C++ 解析，不需要将 QR 图片发送给第三方，隐私更好。建议新原生应用使用本地 CameraX + 条码解码库，并支持相册/文件选择；二维码识别只是提取模板编号，不向第三方传图。
- 这项替换会改变外部服务依赖与隐私行为：UI应明确说明新应用本地解析，不要再保留旧的 `apiQRCodeScanner` 凭据/缓存。

## Upstream 资源 API 边界

Upstream 学案资源实现请求 `/api/v3/server_resource/...`，但静态代码使用随机生成的长 Authorization、随机 accessToken、伪造 clientSession，并将学校 ID 固定为 `100117`。[ResourceFileFetcher.cpp](https://github.com/LFWQSP2641/ZhiNengTiKa/blob/4d192e4e61a7f854c8f48be739e7cff513b6b242/ZhiNengTiKaCore/src/Logic/ResourceFileFetcher.cpp#L12)、[请求头构造](https://github.com/LFWQSP2641/ZhiNengTiKa/blob/4d192e4e61a7f854c8f48be739e7cff513b6b242/ZhiNengTiKaCore/src/Logic/ResourceFileFetcher.cpp#L140)。这只能证明源码尝试访问相关端点，不能证明服务端授权、真实可用性或这些字段有效。它不构成新应用可以依赖的认证方案；未取得真实且获授权的协议依据前，不应将学案资源 API 当作已验证功能实现。

## 可测试协议夹具

- 纯计算测试：给定固定 `t` 与 `clientSession`，断言摘要为两者按 UTF-8 拼接后的 MD5 小写 hex；用随机非真实文本生成测试输入，不在测试 fixture 中加入真实账号数据。
- AES 往返测试：对 ASCII、中文 UTF-8、空串、恰好 16 字节和多块文本做 encrypt/decrypt round-trip，并额外使用脱敏服务端 ciphertext fixture 进行跨实现互操作；不从离线真实用户题卡复制 fixture。
- JSON 解析测试：覆盖 code 为 number/string、业务失败、缺字段/空数组、顶层题、多层子题、选择题、解密字段损坏及错误响应。保留模板 schema 样本时仅使用人工构造的通用内容。
- 上传/提交测试：以 MockWebServer 验证 multipart 字段、提交 JSON schema、错误 code、超时/取消和“结果未知”状态；任何提交重试行为必须被测试为默认关闭。
- 真实服务端端到端测试需显式授权并使用测试账号/测试作业；本次未执行，也没有推断服务端可用性。

## 参考源码

- fork： [UserData.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/Logic/UserData.cpp)、[XinjiaoyuNetwork.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuNetwork.cpp)、[XinjiaoyuEncryptioner.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/XinjiaoyuEncryptioner.cpp)、[UploadWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadWidget.cpp)、[UploadChildWidget.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/GUI/UploadChildWidget.cpp)、[QRCodeScanner.cpp](https://github.com/2034974607/ZhiNengTiKa/blob/ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c/src/StaticClass/QRCodeScanner.cpp)。
- upstream： [AccountManager.cpp](https://github.com/LFWQSP2641/ZhiNengTiKa/blob/4d192e4e61a7f854c8f48be739e7cff513b6b242/ZhiNengTiKaCore/src/Logic/AccountManager.cpp)、[XinjiaoyuNetwork.cpp](https://github.com/LFWQSP2641/ZhiNengTiKa/blob/4d192e4e61a7f854c8f48be739e7cff513b6b242/ZhiNengTiKaCore/src/StaticClass/XinjiaoyuNetwork.cpp)、[ResourceFileFetcher.cpp](https://github.com/LFWQSP2641/ZhiNengTiKa/blob/4d192e4e61a7f854c8f48be739e7cff513b6b242/ZhiNengTiKaCore/src/Logic/ResourceFileFetcher.cpp)。
- 业务功能矩阵：[features.md](features.md)。
