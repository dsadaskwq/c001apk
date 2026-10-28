package com.example.c001apk.ui.captcha

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.c001apk.util.CaptchaChallenge
import com.example.c001apk.util.CaptchaInterceptor
import com.example.c001apk.util.CaptchaStore
import com.example.c001apk.util.PrefManager
import android.webkit.WebResourceRequest

/**
 * 内嵌网易易盾组件完成人机验证，回传 validate 后组装凭证交给网络层重放。
 */
class CaptchaActivity : AppCompatActivity() {

    private lateinit var captchaId: String
    private lateinit var baseUrl: String
    private lateinit var webView: WebView
    private lateinit var root: LinearLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captchaId = intent.getStringExtra(EXTRA_CAPTCHA_ID)
            ?.takeIf { CaptchaChallenge.ID_PATTERN.matches(it) }
            ?: CaptchaChallenge.DEFAULT_ID

        // 登录的验证 ID 在易盾后台绑定了站点域名，需以账号站点作为页面来源；仅放行 coolapk.com 的 https 地址
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
            ?.takeIf { Regex("^https://([a-z0-9-]+\\.)*coolapk\\.com/.*$").matches(it) }
            ?: CAPTCHA_BASE_URL

        val title = TextView(this).apply {
            text = "人机验证"
            textSize = 18f
            setPadding(48, 32, 48, 16)
        }
        val reload = Button(this).apply { text = "重新加载" }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = PrefManager.USER_AGENT
            webViewClient = object : WebViewClient() {
                // 主框架导航一律拦截，防止 Bridge 暴露给重定向后的页面
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = request.isForMainFrame
            }
            addJavascriptInterface(Bridge(), "AndroidBridge")
        }
        reload.setOnClickListener { webView.loadDataWithBaseURL(baseUrl, html(), "text/html", "utf-8", null) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            // WebView 用权重占满标题与按钮之间的剩余空间，保证验证内容完整可见、可滚动
            addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            // 重新加载按钮放在最下方，避免遮挡向上展开的验证面板
            addView(
                reload,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(32, 8, 32, 16) }
            )
        }
        setContentView(root)
        this.root = root
        webView.loadDataWithBaseURL(baseUrl, html(), "text/html", "utf-8", null)
    }

    override fun onDestroy() {
        CaptchaInterceptor.notifyVerified()
        root.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private fun html(): String = """
        <!doctype html><html lang="zh-CN"><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>:root{color-scheme:light dark}html,body{margin:0;padding:0}body{font:16px sans-serif;padding:16px 24px 120px;color:CanvasText;background:Canvas;box-sizing:border-box}#wrap{min-height:520px;display:flex;align-items:center;justify-content:center}#captcha{width:100%;max-width:340px}p{line-height:1.6;margin:0 0 8px}</style>
        </head><body><p id="status">正在加载验证组件…</p><div id="wrap"><div id="captcha"></div></div>
        <script>
        function statusText(text){document.getElementById('status').textContent=text;}
        function startCaptcha(){
          var timeout=setTimeout(function(){statusText('验证组件加载超时，请点击重新加载');},15000);
          var script=document.createElement('script');
          script.src='https://cstaticdun.126.net/load.min.js';
          script.onerror=function(){clearTimeout(timeout);statusText('验证组件加载失败，请检查网络后重新加载');};
          script.onload=function(){
            if(typeof initNECaptcha!=='function'){clearTimeout(timeout);statusText('验证组件不可用，请重新加载');return;}
            try { initNECaptcha({captchaId:'$captchaId',element:'#captcha',protocol:'https',
              mode:'embed',apiVersion:2,width:Math.min(320,window.innerWidth-48)+'px',lang:'zh-CN',
              onVerify:function(err,data){
                if(!err && data && data.validate){window.AndroidBridge.onVerify(data.validate);}
                else{statusText('验证未通过，请按提示重试');}
              }
            },function(){clearTimeout(timeout);statusText('请按下方提示完成人机验证');},
              function(err){clearTimeout(timeout);statusText('无法初始化验证（id='+'$captchaId'.substring(0,8)+'，'+(err&&(err.message||err.code)||JSON.stringify(err)||'未知错误')+'），请检查网络后重新加载');});
            } catch(e){clearTimeout(timeout);statusText('验证组件初始化失败，请重新加载');}
          };
          document.head.appendChild(script);
        }
        window.addEventListener('load',startCaptcha);
        </script></body></html>
    """.trimIndent()

    private inner class Bridge {
        @JavascriptInterface
        fun onVerify(validate: String) {
            val proof = CaptchaChallenge(captchaId, "").proof(validate)
            if (proof == null) {
                runOnUiThread {
                    Toast.makeText(this@CaptchaActivity, "验证结果无效，请重新验证", Toast.LENGTH_SHORT).show()
                }
                return
            }
            CaptchaStore.stage(proof)
            runOnUiThread {
                Toast.makeText(this@CaptchaActivity, "验证完成", Toast.LENGTH_SHORT).show()
                finish()
            }
            CaptchaInterceptor.notifyVerified()
        }
    }

    companion object {
        const val EXTRA_CAPTCHA_ID = "captchaId"
        const val EXTRA_BASE_URL = "baseUrl"

        // 易盾 SDK 要求页面为 https origin；同源实现使用同一占位域名
        private const val CAPTCHA_BASE_URL = "https://captcha.c001apk.invalid/"
    }
}
