package com.example.c001apk.util

import java.security.MessageDigest

/**
 * 人机验证凭证缓存； 与登录态、设备码绑定，24 小时内随请求以 validate Cookie 发送。
 */
object CaptchaStore {

    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

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

    fun save(proof: String, deviceCode: String) {
        PrefManager.captchaProof = proof
        PrefManager.captchaProofAt = System.currentTimeMillis()
        PrefManager.captchaIdentity = identity(deviceCode)
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
