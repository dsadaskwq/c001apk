package com.example.c001apk.util

import android.content.Intent
import android.util.Log
import com.example.c001apk.MyApplication
import com.example.c001apk.ui.captcha.CaptchaActivity
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 服务端要求人机验证（err_request_captcha_v2）时拉起验证页，拿到凭证后重放请求。
 *
 * - 覆盖 API 域名（动态详情、评论列表、发评论等）与账号域名（登录）；
 * - 同一时刻同一范围内只弹一次验证页，并发被挑战的请求（如动态详情 + 评论区）
 *   等待同一次验证结果后各自重放，不再有请求直接拿到挑战响应而加载失败；
 * - captchaField 为空走 validate Cookie 协议；非空且为表单请求时，凭证写入该表单字段。
 */
object CaptchaInterceptor : Interceptor {

    private val API_HOSTS = setOf("api.coolapk.com", "api2.coolapk.com")
    private const val ACCOUNT_HOST = "account.coolapk.com"
    private const val SCOPE_API = "api"
    private const val SCOPE_ACCOUNT = "account"
    private const val WAIT_MINUTES = 2L
    private const val TAG = "CaptchaInterceptor"
    private const val ACCOUNT_PAGE_URL = "https://account.coolapk.com/auth/login?type=mobile"
    // 官方登录页（account.coolapk.com）提交验证结果所用的表单字段，值为易盾原始 validate
    private const val ACCOUNT_FORM_FIELD = "yidunCaptcha"

    private class Session {
        // 验证页 -> 发起验证的请求
        val verified = CountDownLatch(1)

        // 发起验证的请求 -> 同批等待的请求
        val published = CountDownLatch(1)

        @Volatile
        var proof: String? = null
    }

    // 最近一次账号接口的识别结果与返回体摘要，供登录页长按“登录”复制
    @Volatile
    var lastAccountLog: String = ""

    // 从登录页 HTML/JS 中提取到的 captchaId（登录与 API 使用的验证 ID 可能不同）
    @Volatile
    private var accountCaptchaId: String? = null

    private val PAGE_ID_REGEX =
        Regex("""captcha_?id["']?\s*[:=]\s*["']([a-zA-Z0-9]{16,64})["']""", RegexOption.IGNORE_CASE)

    private fun record(line: String) {
        Log.i(TAG, line)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lastAccountLog = "[$time] $line\n" + lastAccountLog.take(30000)
    }

    private val lock = Any()
    private val sessions = HashMap<String, Session>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val isApi = host in API_HOSTS
        if (request.url.scheme != "https" || (!isApi && host != ACCOUNT_HOST))
            return chain.proceed(request)

        val scope = if (isApi) SCOPE_API else SCOPE_ACCOUNT
        val deviceCode = TokenDeviceUtils.getLastingDeviceCode()
        // 已落盘凭证仅用于 API 域名；登录请求与登录态无关，不携带
        val usedProof = if (isApi) CaptchaStore.current(deviceCode) else null
        val response = chain.proceed(
            if (usedProof != null) withValidate(request, usedProof) else request
        )
        val challenge = parseChallenge(response, lenient = !isApi) ?: return response

        // 本请求等待期间，别的请求已完成验证并落盘：直接用新凭证重放，不再重复弹窗
        if (isApi && challenge.field.isEmpty()) {
            val latest = CaptchaStore.current(deviceCode)
            if (latest != null && latest != usedProof) {
                response.close()
                return chain.proceed(withValidate(request, latest))
            }
        }

        var leader = false
        val session = synchronized(lock) {
            sessions[scope] ?: Session().also {
                sessions[scope] = it
                leader = true
            }
        }

        if (!leader) return awaitAndReplay(chain, request, response, challenge, session)

        try {
            MyApplication.context.startActivity(
                Intent(MyApplication.context, CaptchaActivity::class.java)
                    .putExtra(CaptchaActivity.EXTRA_CAPTCHA_ID, challenge.captchaId)
                    .apply {
                        if (!isApi) putExtra(CaptchaActivity.EXTRA_BASE_URL, ACCOUNT_PAGE_URL)
                    }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            session.verified.await(WAIT_MINUTES, TimeUnit.MINUTES)
            val newProof = CaptchaStore.takePending()
            session.proof = newProof
            // 先放行同批等待的请求，再处理自己的重放
            session.published.countDown()
            if (newProof == null) return response

            response.close()
            val retryResponse = chain.proceed(withProof(request, challenge, newProof))
            // 仅 API 域名的 Cookie 协议凭证可复用 24 小时：服务端接受后才落盘，
            // 仍被挑战则连同既有凭证一并清除，防止反复验证
            if (isApi && challenge.field.isEmpty()) {
                if (parseChallenge(retryResponse) != null) CaptchaStore.reject()
                else CaptchaStore.confirm(newProof, deviceCode)
            }
            if (!isApi) parseChallenge(retryResponse, lenient = true)
            return retryResponse
        } catch (e: InterruptedException) {
            CaptchaStore.takePending()
            Thread.currentThread().interrupt()
            return response
        } finally {
            session.published.countDown()
            synchronized(lock) { sessions.remove(scope) }
        }
    }

    // 验证页销毁（完成或用户退出）时唤醒各范围内发起验证的请求
    fun notifyVerified() {
        val active = synchronized(lock) { sessions.values.toList() }
        active.forEach { it.verified.countDown() }
    }

    private fun awaitAndReplay(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
        challenge: CaptchaChallenge,
        session: Session
    ): Response {
        return try {
            if (!session.published.await(WAIT_MINUTES + 1, TimeUnit.MINUTES)) return response
            val proof = session.proof ?: return response
            response.close()
            chain.proceed(withProof(request, challenge, proof))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            response
        }
    }

    // 响应是挑战则返回之；图片等非 JSON 响应直接跳过，避免无谓读取
    private fun parseChallenge(response: Response, lenient: Boolean = false): CaptchaChallenge? {
        val body = response.body ?: return null
        if (body.contentType()?.type == "image") return null
        val text = try {
            response.peekBody(if (lenient) 262144L else 16384L).string()
        } catch (e: IOException) {
            return null
        }
        if (lenient) {
            PAGE_ID_REGEX.find(text)?.let { m ->
                accountCaptchaId = m.groupValues[1]
                // 记录初始化代码上下文，便于核对 initNECaptcha 的参数与回传字段名
                val from = (m.range.first - 400).coerceAtLeast(0)
                val to = (m.range.last + 700).coerceAtMost(text.length)
                record("found captchaId in ${response.request.url.encodedPath}: ${m.groupValues[1]} ctx=${text.substring(from, to)}")
                Regex("yidunCaptcha|function verify|createNEGuardian|negToken|NEGuardian|\\.getToken", RegexOption.IGNORE_CASE)
                    .findAll(text).take(8).forEach { v ->
                        val a = (v.range.first - 100).coerceAtLeast(0)
                        val b = (v.range.last + 700).coerceAtMost(text.length)
                        record("page ctx[${v.value}]=${text.substring(a, b)}")
                    }
                Regex("<script[^>]*\\ssrc=[^>]*>", RegexOption.IGNORE_CASE)
                    .findAll(text).take(20).forEach { record("script tag: ${it.value}") }
            }
        }
        if (!text.trimStart().startsWith("{")) return null
        var challenge = CaptchaChallenge.fromBody(text, lenient)
        if (lenient && challenge != null) {
            val fromPage = accountCaptchaId
            if (challenge.captchaId == CaptchaChallenge.DEFAULT_ID && fromPage != null)
                challenge = challenge.copy(captchaId = fromPage)
            val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            record(
                "challenge id=${challenge.captchaId} " +
                    "default=${challenge.captchaId == CaptchaChallenge.DEFAULT_ID} " +
                    "message=${json?.optString("message")} status=${json?.opt("status")} " +
                    "messageStatus=${json?.optString("messageStatus")} " +
                    "keys=${json?.keys()?.asSequence()?.toList()?.joinToString(",")} " +
                    "tail=${text.takeLast(500)}"
            )
        } else if (lenient) {
            record("account json, not challenge: len=${text.length} head=${text.take(600)} tail=${text.takeLast(300)}")
        }
        return challenge
    }

    // 按挑战协议附带凭证：登录表单写入 yidunCaptcha；字段协议写入挑战指定字段；否则走 validate Cookie
    private fun withProof(request: Request, challenge: CaptchaChallenge, proof: String): Request {
        val body = request.body
        if (request.url.host == ACCOUNT_HOST && body is FormBody) {
            val raw = proof.split(":", limit = 3).getOrNull(2) ?: return request
            return withFormField(request, body, ACCOUNT_FORM_FIELD, raw)
        }
        if (challenge.field.isNotEmpty() && body is FormBody) {
            return withFormField(request, body, challenge.field, proof)
        }
        return withValidate(request, proof)
    }

    private fun withFormField(request: Request, body: FormBody, name: String, value: String): Request {
        val builder = FormBody.Builder()
        for (i in 0 until body.size) {
            if (body.name(i) == name) continue
            builder.addEncoded(body.encodedName(i), body.encodedValue(i))
        }
        builder.add(name, value)
        return request.newBuilder().method(request.method, builder.build()).build()
    }

    // 剥离既有 validate 后重设，避免重放请求出现双份凭证；发送前 URL 编码
    private fun withValidate(request: Request, proof: String): Request {
        val cookies = request.header("Cookie")
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("validate=") }
            .orEmpty()
        val encoded = URLEncoder.encode(proof, "UTF-8")
        return request.newBuilder()
            .header("Cookie", (cookies + "validate=$encoded").joinToString("; "))
            .build()
    }
}
