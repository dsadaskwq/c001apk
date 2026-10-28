package com.example.c001apk.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.InputType
import android.text.Spanned
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import com.example.c001apk.R
import com.example.c001apk.databinding.ActivityLoginBinding
import com.example.c001apk.ui.base.BaseActivity
import com.example.c001apk.ui.main.MainActivity
import com.example.c001apk.util.ActivityCollector
import com.example.c001apk.util.CookieUtil.isGetCaptcha
import com.example.c001apk.util.CookieUtil.isGetSmsLoginParam
import com.example.c001apk.util.CookieUtil.isPreGetLoginParam
import com.example.c001apk.util.CookieUtil.isTryLogin
import com.example.c001apk.util.LoginUtils.createRandomNumber
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginActivity : BaseActivity<ActivityLoginBinding>() {

    private val viewModel by viewModels<LoginViewModel>()
    private var isLoginPass = true

    private val smsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == Activity.RESULT_OK && d != null) {
                viewModel.onSmsLoginSuccess(
                    d.getStringExtra(SmsVerifyActivity.RESULT_UID).orEmpty(),
                    d.getStringExtra(SmsVerifyActivity.RESULT_NAME).orEmpty(),
                    d.getStringExtra(SmsVerifyActivity.RESULT_TOKEN).orEmpty()
                )
            } else {
                Toast.makeText(this, "已取消短信验证", Toast.LENGTH_SHORT).show()
            }
        }

    private val filter =
        InputFilter { source: CharSequence, _: Int, _: Int, _: Spanned?, _: Int, _: Int ->
            if (source == " ")
                return@InputFilter ""
            else
                return@InputFilter null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initObserve()

        isPreGetLoginParam = true
        viewModel.onPreGetLoginParam()

        /*viewModel.smsLoginParamData.observe(this) { result ->
            val response = result.getOrNull()
            val body = response?.body()?.string()
            body?.apply {
                viewModel.requestHash = Jsoup.parse(this).createRequestHash()
            }
            response?.apply {
                try {
                    val cookies = response.headers().values("Set-Cookie")
                    val session = cookies[0]
                    val sessionID = session.substring(0, session.indexOf(";"))
                    SESSID = sessionID
                } catch (e: Exception) {
                    Toast.makeText(this@LoginActivity, "无法获取cookie", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                }
            }
        }*/

        /*viewModel.getSmsTokenData.observe(this) { result ->
            val response = result.getOrNull()
            response?.apply {
                viewModel.key = response.headers().values("Location").toString()
            }
        }*/

        binding.apply {
            account.filters = arrayOf(filter)
            password.filters = arrayOf(filter)
            sms.filters = arrayOf(filter)
            captchaText.filters = arrayOf(filter)
        }


        /*binding.getSMS.setOnClickListener {
            if (binding.account.text.toString() == "")
                Toast.makeText(this, "手机号不能为空", Toast.LENGTH_SHORT).show()
            else if (binding.account.text.toString().length != 11)
                Toast.makeText(this, "手机号不合规", Toast.LENGTH_SHORT).show()
            else
                getSMS()
        }*/

        binding.login.setOnClickListener {
            if (isLoginPass) {
                if (binding.account.text.toString() == "" || binding.password.text.toString() == "")
                    Toast.makeText(this, "用户名或密码为空", Toast.LENGTH_SHORT).show()
                else
                    tryLogin()
            } else {
                if (binding.account.text.toString() == "" || binding.sms.text.toString() == "")
                    Toast.makeText(this, "手机号或验证码为空", Toast.LENGTH_SHORT).show()
                else
                    tryLogin()
            }

        }

        // 长按“登录”复制最近的人机验证诊断信息，便于反馈
        binding.login.setOnLongClickListener {
            val log = com.example.c001apk.util.CaptchaInterceptor.lastAccountLog
                .ifEmpty { "暂无记录，请先点击登录一次再长按" }
            com.example.c001apk.util.ClipboardUtil.copyText(this, log, showToast = false)
            Toast.makeText(this, "已复制诊断信息（${log.length} 字）", Toast.LENGTH_SHORT).show()
            true
        }

        binding.captchaImg.setOnClickListener {
            getCaptcha()
        }

    }

    private fun initObserve() {
        viewModel.getCaptcha.observe(this) { event ->
            event.getContentIfNotHandledOrReturnNull()?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                when (it) {
                    "图形验证码不能为空" -> {
                        binding.captcha.isVisible = true
                        viewModel.onGetCaptcha()
                    }

                    "图形验证码错误" -> viewModel.onGetCaptcha()

                    "密码错误" -> {
                        if (binding.captcha.visibility == View.VISIBLE)
                            viewModel.onGetCaptcha()
                    }
                }
            }
        }

        viewModel.smsVerify.observe(this) { event ->
            event.getContentIfNotHandledOrReturnNull()?.let { c ->
                Toast.makeText(this, c.hint ?: "请输入短信验证码", Toast.LENGTH_SHORT).show()
                smsLauncher.launch(
                    Intent(this, SmsVerifyActivity::class.java)
                        .putExtra(SmsVerifyActivity.EXTRA_URL, c.url)
                        .putExtra(SmsVerifyActivity.EXTRA_HINT, c.hint)
                        .putStringArrayListExtra(SmsVerifyActivity.EXTRA_COOKIES, ArrayList(c.cookies))
                )
            }
        }

        viewModel.showCaptcha.observe(this) { event ->
            event.getContentIfNotHandledOrReturnNull()?.let {
                binding.captchaImg.setImageBitmap(it)
            }
        }

        viewModel.toastText.observe(this) { event ->
            event.getContentIfNotHandledOrReturnNull()?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.afterLogin.observe(this) { event ->
            event.getContentIfNotHandledOrReturnNull()?.let {
                afterLogin()
            }
        }
    }

    private fun tryLogin() {
        Toast.makeText(this, "正在登录...", Toast.LENGTH_SHORT).show()
        isTryLogin = true
        viewModel.loginData["submit"] = "1"
        viewModel.loginData["randomNumber"] = createRandomNumber()
        viewModel.loginData["requestHash"] = viewModel.requestHash
        viewModel.loginData["login"] = binding.account.text.toString()
        viewModel.loginData["password"] = binding.password.text.toString()
        viewModel.loginData["captcha"] = binding.captchaText.text.toString()
        viewModel.loginData["code"] = ""
        viewModel.onTryLogin()
    }

    /*override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.login_menu, menu)
        return true
    }*/

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()

            R.id.loginPass -> {
                isLoginPass = true
                binding.account.inputType = InputType.TYPE_CLASS_TEXT
                binding.account.filters = arrayOf(LengthFilter(99), filter)
                binding.passLayout.isVisible = true
                binding.smsLayout.isVisible = false
                binding.getSMS.isVisible = false
            }

            R.id.loginPhone -> {
                isLoginPass = false
                binding.account.inputType = InputType.TYPE_CLASS_NUMBER
                binding.account.filters = arrayOf(LengthFilter(11), filter)
                binding.getSMS.isVisible = true
                binding.smsLayout.isVisible = true
                binding.passLayout.isVisible = false
                isGetSmsLoginParam = true
                //viewModel.getSmsLoginParam()
            }
        }
        return true
    }

    /*private fun getSMS() {
        isGetSmsToken = true
        viewModel.getSmsData["submit"] = "1"
        viewModel.getSmsData["requestHash"] = viewModel.requestHash
        viewModel.getSmsData["country"] = "86"
        viewModel.getSmsData["mobile"] = binding.account.text.toString()
        viewModel.getSmsData["captcha"] = binding.captchaText.text.toString()
        viewModel.getSmsData["randomNumber"] = createRandomNumber()
        viewModel.getSmsToken()
    }*/

    private fun getCaptcha() {
        isGetCaptcha = true
        viewModel.onGetCaptcha()
    }

    private fun afterLogin() {
        ActivityCollector.recreateActivity(MainActivity::class.java.name)
        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
        finish()
    }

}