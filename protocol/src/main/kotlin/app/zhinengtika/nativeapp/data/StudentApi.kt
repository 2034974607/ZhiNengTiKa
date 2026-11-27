package app.zhinengtika.nativeapp.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StudentApi(
    baseUrl: String = "https://www.xinjiaoyu.com/",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val sessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun login(username: String, password: String): StudentAccount {
        require(username.isNotBlank()) { "请输入账号" }
        require(password.isNotEmpty()) { "请输入密码" }
        val timestamp = clockMillis().toString()
        val clientSession = sessionId()
        val payload = buildJsonObject {
            put("username", ProtocolCrypto.encryptToBase64(username))
            put("password", ProtocolCrypto.encryptToBase64(password))
            put("t", timestamp.toLong())
        }
        val request = Request.Builder()
            .url(endpoint("api/v3/server_system/auth/login"))
            .headers(headers("JBY", "", clientSession, timestamp))
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        val root = parseSuccess(execute(request))
        val data = root.objectAt("data") ?: throw ApiException(null, "登录响应缺少 data")
        val info = data.objectAt("info") ?: throw ApiException(null, "登录响应缺少用户资料")
        val school = info.objectAt("school") ?: throw ApiException(null, "登录响应缺少学校资料")
        val accessToken = data.stringAt("accessToken")?.takeIf(String::isNotBlank)
            ?: throw ApiException(null, "登录响应缺少 accessToken")
        val token = data.stringAt("token")?.takeIf(String::isNotBlank)
            ?: throw ApiException(null, "登录响应缺少 token")
        val studentId = school.stringAt("studentId")?.takeIf(String::isNotBlank)
            ?: throw ApiException(null, "登录响应缺少 studentId")
        val schoolId = school.stringAt("schoolId")?.takeIf(String::isNotBlank)
            ?: throw ApiException(null, "登录响应缺少 schoolId")
        return StudentAccount(
            username = username,
            accessToken = accessToken,
            authorization = "JBY $token",
            clientSession = clientSession,
            studentId = studentId,
            schoolId = schoolId,
            displayName = info.stringAt("realName") ?: info.stringAt("name").orEmpty(),
        )
    }

    suspend fun getTemplate(code: String, account: StudentAccount): TemplateCard {
        require(code.isNotBlank()) { "请输入题卡编号" }
        val url = endpoint("api/v3/server_homework/homework/template/question/list")
            .newBuilder()
            .addQueryParameter("templateCode", code)
            .addQueryParameter("studentId", account.studentId)
            .addQueryParameter("isEncrypted", "true")
            .build()
        val request = Request.Builder()
            .url(url)
            .headers(headers(account.authorization, account.accessToken, account.clientSession, clockMillis().toString()))
            .get()
            .build()
        val body = execute(request)
        return TemplateParser.parse(body, code)
    }

    suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        account: StudentAccount,
    ): String {
        require(bytes.isNotEmpty()) { "图片为空" }
        val part = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody("multipart/form-data".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(endpoint("api/v3/server_homework/homework/upload"))
            .headers(headers(account.authorization, account.accessToken, account.clientSession, clockMillis().toString()))
            .post(oneShot(part))
            .build()
        val root = parseSuccess(execute(request))
        val data = root.objectAt("data") ?: throw ApiException(null, "图片上传响应缺少 data")
        val accessUrl = data.stringAt("accessUrl")
            ?: throw ApiException(null, "图片上传响应缺少 accessUrl")
        val returnedName = data.stringAt("filename")
            ?: throw ApiException(null, "图片上传响应缺少 filename")
        require(accessUrl.startsWith("https://")) { "图片服务返回了不安全的 URL" }
        val encodedName = URLEncoder.encode(returnedName, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val slash = accessUrl.lastIndexOf('/')
        require(slash >= "https://".length) { "图片服务返回了无效的 URL" }
        return accessUrl.substring(0, slash + 1) + encodedName
    }

    suspend fun submitAnswers(
        account: StudentAccount,
        templateCode: String,
        answers: List<AnswerSubmission>,
        onCallFinished: () -> Unit = {},
    ) {
        val request = try {
            val payload = buildJsonObject {
                put("schoolId", account.schoolId)
                put("studentId", account.studentId)
                put("templateCode", templateCode)
                put("answers", buildJsonArray {
                    answers.forEach { answer ->
                        add(buildJsonObject {
                            put("questionId", answer.questionId)
                            put("answer", answer.answer)
                            put("rawScan", answer.rawScan)
                        })
                    }
                })
            }
            Request.Builder()
                .url(endpoint("api/v3/server_homework/student/homework/answer"))
                .headers(headers(account.authorization, account.accessToken, account.clientSession, clockMillis().toString()))
                .post(oneShot(payload.toString().toRequestBody(jsonType)))
                .build()
        } catch (error: Exception) {
            // No Call exists yet. A failed local setup is terminal too, and must release the
            // process-only guard. The persistent UNKNOWN marker remains the caller's concern.
            runCatching(onCallFinished)
            throw error
        }
        parseSuccess(execute(request, onCallFinished))
    }

    private fun oneShot(body: okhttp3.RequestBody): okhttp3.RequestBody = object : okhttp3.RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
        override fun isOneShot() = true
    }

    private fun headers(
        authorization: String,
        accessToken: String,
        clientSession: String,
        timestamp: String,
    ): okhttp3.Headers = okhttp3.Headers.Builder()
        .add("app", "student")
        .add("client", "android")
        .add("Content-Type", "application/json")
        .add("User-Agent", "okhttp/4.9.3")
        .add("Authorization", authorization)
        .add("accessToken", accessToken)
        .add("clientSession", clientSession)
        .add("t", timestamp)
        .add("encrypt", ProtocolCrypto.requestDigest(timestamp, clientSession))
        .build()

    private fun endpoint(path: String): HttpUrl = base.newBuilder()
        .addPathSegments(path)
        .build()

    private suspend fun execute(
        request: Request,
        onCallFinished: () -> Unit = {},
    ): String = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        // Cancellation only asks OkHttp to stop. Release in-flight guards after its final
        // callback, or a synchronous setup failure, never merely because the caller left.
        fun complete(result: Result<String>) {
            if (!finished.compareAndSet(false, true)) return
            val callback = runCatching(onCallFinished)
            continuation.resumeWith(callback.exceptionOrNull()?.let { Result.failure(it) } ?: result)
        }
        try {
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                    complete(Result.failure(error))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw HttpStatusException(it.code)
                            val body = it.body ?: throw ApiException(null, "服务器返回空响应")
                            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                                throw ApiException(null, "服务器响应超过 8 MiB")
                            }
                            val source = body.source()
                            if (source.request(MAX_RESPONSE_BYTES + 1L)) {
                                throw ApiException(null, "服务器响应超过 8 MiB")
                            }
                            val text = source.readString(Charsets.UTF_8)
                            if (text.isBlank()) throw ApiException(null, "服务器返回空响应")
                            text
                        }
                    }
                    complete(result)
                }
            })
        } catch (error: Exception) {
            complete(Result.failure(error))
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    }

    private fun parseSuccess(body: String): JsonObject {
        val root = TemplateParser.parseObject(body)
        TemplateParser.requireSuccess(root)
        return root
    }

    private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.stringAt(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return null
        val content = primitive.contentOrNull ?: return null
        return content.takeIf { it.isNotBlank() && it != "null" }
    }
}
