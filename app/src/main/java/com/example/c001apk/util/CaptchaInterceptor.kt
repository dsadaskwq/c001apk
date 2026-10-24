package com.example.c001apk.util

import android.content.Intent
import com.example.c001apk.MyApplication
import com.example.c001apk.ui.captcha.CaptchaActivity
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 服务端对动态详情等接口要求人机验证（err_request_captcha_v2）；
 * 命中时拉起验证页，等待凭证后重放一次请求。
 */
object CaptchaInterceptor : Interceptor {

    @Volatile
    private var verifying = false

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
        val signed = if (proof != null) appendValidate(request, proof) else request
        val response = chain.proceed(signed)
        if (verifying) return response

        val challenge = response.peekBody(16384).string().let { CaptchaChallenge.fromBody(it) }
            ?: return response

        verifying = true
        val waiting = CountDownLatch(1)
        latch = waiting
        try {
            MyApplication.context.startActivity(
                Intent(MyApplication.context, CaptchaActivity::class.java)
                    .putExtra(CaptchaActivity.EXTRA_CAPTCHA_ID, challenge.captchaId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waiting.await(2, TimeUnit.MINUTES)
            val newProof = CaptchaStore.current(deviceCode)
            if (newProof != null && newProof != proof) {
                response.close()
                return chain.proceed(appendValidate(signed, newProof))
            }
            return response
        } catch (e: InterruptedException) {
            return response
        } finally {
            verifying = false
            latch = null
        }
    }

    fun notifyVerified() {
        latch?.countDown()
    }

    private fun appendValidate(request: Request, proof: String): Request =
        request.newBuilder()
            .header(
                "Cookie",
                (request.header("Cookie")?.let { "$it; " } ?: "") + "validate=$proof"
            )
            .build()
}
