package com.example.c001apk.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.c001apk.util.PrefManager

/**
 * 陌生设备登录的短信验证：直接加载官方 validateLogin 页面（带登录时的会话 cookie），
 * 页面自带短信输入与风控 token；验证成功后服务端写入 uid/username/token cookie，读出后回传。
 */
class SmsVerifyActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
            ?.takeIf { Regex("^https://account\\.coolapk\\.com/.*$").matches(it) }
        if (url == null) {
            finish()
            return
        }
        supportActionBar?.apply {
            title = "短信验证"
            setDisplayHomeAsUpEnabled(true)
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // 清掉旧的登录态 cookie，避免误判；再写入登录阶段拿到的会话 cookie
        listOf("uid", "username", "token", "SESSID").forEach {
            cm.setCookie("https://account.coolapk.com", "$it=; Max-Age=0")
        }
        intent.getStringArrayListExtra(EXTRA_COOKIES)?.forEach {
            cm.setCookie("https://account.coolapk.com", "$it; Path=/")
        }
        cm.flush()

        val hint = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_HINT) ?: "请验证您的身份"
            setPadding(48, 24, 48, 16)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = PrefManager.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    // 验证成功后会跳转到 forward 站点：此时 cookie 已写入，拦截跳转直接收尾
                    if (checkLogin()) return true
                    return false
                }

                override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                    checkLogin()
                }

                override fun onPageFinished(view: WebView, u: String?) {
                    checkLogin()
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        })
        // 页面按 CoolMarket 客户端模式渲染
        webView.loadUrl(
            url,
            mapOf("X-Requested-With" to "com.coolapk.market", "X-App-Id" to "com.coolapk.market")
        )
    }

    private fun checkLogin(): Boolean {
        if (done) return true
        val cookie = CookieManager.getInstance().getCookie("https://account.coolapk.com").orEmpty()
        val map = cookie.split(";").mapNotNull {
            val i = it.indexOf("=")
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
        val uid = map["uid"].orEmpty()
        val token = map["token"].orEmpty()
        if (uid.isEmpty() || token.isEmpty()) return false
        done = true
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(RESULT_UID, uid)
                .putExtra(RESULT_NAME, map["username"].orEmpty())
                .putExtra(RESULT_TOKEN, token)
        )
        finish()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) finish()
        return true
    }

    override fun onDestroy() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_HINT = "hint"
        const val EXTRA_COOKIES = "cookies"
        const val RESULT_UID = "uid"
        const val RESULT_NAME = "username"
        const val RESULT_TOKEN = "token"
    }
}
