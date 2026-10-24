package com.example.c001apk.util

import android.content.Intent
import com.example.c001apk.MyApplication
import com.example.c001apk.ui.captcha.CaptchaActivity
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 服务端对动态详情等接口要求人机验证（err_request_captcha_v2）；
 * 命中时拉起验证页，等待凭证后重放一次请求。
 */
object CaptchaInterceptor : Interceptor {

    private val verifying = AtomicBoolean(false)

    @Volatile
    private var latch: CountDownLatch? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (request.url.scheme != "https" ||
            (host != "api.coolapk.com" && host != "api2.coolapk.com")
        ) return chain.proceed(request)

        val deviceCode = TokenDeviceUtils.getLastingDeviceCode()
        val proof = CaptchaStore.current(deviceCode)
        val signed = if (proof != null) withValidate(request, proof) else request
        val response = chain.proceed(signed)
        if (verifying.get()) return response

        val challenge = response.peekBody(16384).string().let { CaptchaChallenge.fromBody(it) }
            ?: return response

        // 并发命中挑战时仅首个请求发起验证，其余直接返回原响应
        if (!verifying.compareAndSet(false, true)) return response
        val waiting = CountDownLatch(1)
        latch = waiting
        try {
            MyApplication.context.startActivity(
                Intent(MyApplication.context, CaptchaActivity::class.java)
                    .putExtra(CaptchaActivity.EXTRA_CAPTCHA_ID, challenge.captchaId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waiting.await(2, TimeUnit.MINUTES)
            val newProof = CaptchaStore.takePending() ?: return response
            response.close()
            val retryResponse = chain.proceed(withValidate(request, newProof))
            // 仅当服务端接受重试才落盘凭证；仍被挑战则连同既有凭证一并清除，防止反复验证
            val stillChallenged = retryResponse.peekBody(16384).string()
                .let { CaptchaChallenge.fromBody(it) } != null
            if (stillChallenged) CaptchaStore.reject()
            else CaptchaStore.confirm(newProof, deviceCode)
            return retryResponse
        } catch (e: InterruptedException) {
            CaptchaStore.takePending()
            return response
        } finally {
            verifying.set(false)
            latch = null
        }
    }

    fun notifyVerified() {
        latch?.countDown()
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
