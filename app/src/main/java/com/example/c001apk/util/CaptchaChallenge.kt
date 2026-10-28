package com.example.c001apk.util

import org.json.JSONObject

data class CaptchaChallenge(val captchaId: String, val field: String) {

    /**
     * 校验结果有效性并生成请求凭证； NEC 校验通过的凭证形如 NEC:<captchaId 前 8 位>:<validate>
     */
    fun proof(validate: String): String? {
        if (validate.isEmpty() || validate.length > 8192 || Regex("[\\s\\x00-\\x1f]").containsMatchIn(validate))
            return null
        return "NEC:${captchaId.substring(0, 8)}:$validate"
    }

    companion object {
        const val DEFAULT_ID = "414e5c9b866a03db03f860a1a9101672"
        val ID_PATTERN = Regex("^[a-zA-Z0-9]{16,64}$")
        private val FIELD_PATTERN = Regex("^_[a-zA-Z0-9_]{1,63}$")

        /**
         * 从响应体解析验证码挑战； 仅 err_request_captcha_v2 响应有效。
         * extra 取 messageExtra ?? extra，字符串二次解析失败回落顶层；
         * captchaId 不可信时回落默认值；键缺失回落表单字段协议，
         * 显式空串表示 validate Cookie 协议。
         */
        fun fromBody(body: String, lenient: Boolean = false): CaptchaChallenge? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (json.optString("messageStatus") != "err_request_captcha_v2") {
                // 登录接口的挑战不一定带 err_request_captcha_v2：按提示文案或 err_request_captcha* 识别，
                // captchaId 在整个 JSON（含字符串形式的嵌套）里查找，缺失回落默认值
                if (!lenient) return null
                val status = json.optString("messageStatus")
                val message = json.optString("message")
                if (!status.startsWith("err_request_captcha") && !message.contains("人机验证"))
                    return null
                val id = findString(json, "captchaId").orEmpty()
                return CaptchaChallenge(if (ID_PATTERN.matches(id)) id else DEFAULT_ID, "")
            }
            val raw = json.opt("messageExtra") ?: json.opt("extra")
            val extra = when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull() ?: json
                else -> json
            }
            val id = extra.optString("captchaId")
            val fieldRaw: String? =
                if (extra.has("captchaField")) extra.optString("captchaField") else null
            val field = when {
                fieldRaw == null -> "_v2_post_token"
                fieldRaw.trim().isEmpty() -> ""
                FIELD_PATTERN.matches(fieldRaw) -> fieldRaw.trim()
                else -> "_v2_post_token"
            }
            return CaptchaChallenge(
                if (ID_PATTERN.matches(id)) id else DEFAULT_ID,
                field
            )
        }

        private fun findString(node: Any?, key: String, depth: Int = 0): String? {
            if (depth > 4) return null
            when (node) {
                is JSONObject -> {
                    if (node.has(key)) return node.optString(key)
                    for (k in node.keys()) findString(node.opt(k), key, depth + 1)?.let { return it }
                }
                is org.json.JSONArray -> for (i in 0 until node.length())
                    findString(node.opt(i), key, depth + 1)?.let { return it }
                is String -> if (node.trimStart().startsWith("{"))
                    return findString(runCatching { JSONObject(node) }.getOrNull(), key, depth + 1)
            }
            return null
        }
    }
}
