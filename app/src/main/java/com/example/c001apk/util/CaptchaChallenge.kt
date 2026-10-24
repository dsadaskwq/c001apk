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
        private val ID_PATTERN = Regex("^[a-zA-Z0-9]{16,64}$")
        private val FIELD_PATTERN = Regex("^_[a-zA-Z0-9_]{1,63}$")

        /**
         * 从响应体解析验证码挑战； 仅 err_request_captcha_v2 响应有效。
         * captchaId 不可信时回落默认值； 空的 captchaField 表示 validate Cookie 协议。
         */
        fun fromBody(body: String): CaptchaChallenge? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (json.optString("messageStatus") != "err_request_captcha_v2") return null
            val extra: JSONObject = json.optJSONObject("messageExtra")
                ?: json.optJSONObject("extra")?.let {
                    if (it.length() > 0) it else null
                }
                ?: json.optString("messageExtra").takeIf { it.isNotEmpty() }
                    ?.let { str -> runCatching { JSONObject(str) }.getOrNull() }
                ?: json
            val id = extra.optString("captchaId")
            val field = extra.optString("captchaField", "").trim()
            return CaptchaChallenge(
                if (ID_PATTERN.matches(id)) id else DEFAULT_ID,
                if (field.isEmpty() || FIELD_PATTERN.matches(field)) field else "_v2_post_token"
            )
        }
    }
}
