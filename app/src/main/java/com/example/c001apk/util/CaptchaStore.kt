package com.example.c001apk.util

import java.security.MessageDigest

/**
 * 人机验证凭证管理； 滑块完成后先内存持有，服务端接受重试后才落盘，
 * 凭证与登录态、设备码绑定，24 小时内随请求以 validate Cookie 发送。
 */
object CaptchaStore {

    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var pending: String? = null

    fun stage(proof: String) {
        pending = proof
    }

    fun takePending(): String? = pending.also { pending = null }

    fun confirm(proof: String, deviceCode: String) {
        PrefManager.captchaProof = proof
        PrefManager.captchaProofAt = System.currentTimeMillis()
        PrefManager.captchaIdentity = identity(deviceCode)
    }

    // 重试仍被挑战：凭证无效，连同既有落盘凭证一并清除
    fun reject() {
        pending = null
        clear()
    }

    fun current(deviceCode: String): String? {
        val proof = PrefManager.captchaProof
        if (proof.isEmpty()) return null
        if (System.currentTimeMillis() > PrefManager.captchaProofAt + MAX_AGE_MS) {
            clear(); return null
        }
        if (PrefManager.captchaIdentity != identity(deviceCode)) {
            clear(); return null
        }
        return proof
    }

    fun clear() {
        PrefManager.captchaProof = ""
        PrefManager.captchaProofAt = 0L
        PrefManager.captchaIdentity = ""
    }

    private fun identity(deviceCode: String): String {
        val content = "$deviceCode\n${PrefManager.isLogin}\n${PrefManager.uid}\n${PrefManager.token}"
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
