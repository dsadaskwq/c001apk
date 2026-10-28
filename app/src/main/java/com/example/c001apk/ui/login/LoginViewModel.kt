package com.example.c001apk.ui.login

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.c001apk.adapter.FooterState
import com.example.c001apk.logic.model.LoginResponse
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.util.CookieUtil
import com.example.c001apk.util.Event
import com.example.c001apk.util.LoginUtils.createRequestHash
import com.example.c001apk.util.PrefManager
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val networkRepo: NetworkRepo
): ViewModel() {

    var uid: String? = null
    var url: String? = null
    var listSize: Int = -1
    var type: String? = null
    var isInit: Boolean = true
    var page = 1
    var lastItem: String? = null
    var isRefreshing: Boolean = false
    var isLoadMore: Boolean = false
    var isEnd: Boolean = false
    var lastVisibleItemPosition: Int = 0

    var requestHash: String? = null
    val footerState = MutableLiveData<FooterState>()
    var loginData = HashMap<String, String?>()
    val toastText = MutableLiveData<Event<String>>()
    val showCaptcha = MutableLiveData<Event<Bitmap>>()
    val getCaptcha = MutableLiveData<Event<String>>()
    val afterLogin = MutableLiveData<Event<Boolean>>()
    val smsVerify = MutableLiveData<Event<SmsChallenge>>()

    /** 陌生设备登录需要短信验证：url 为官方 validateLogin 页面，cookies 为需带入页面的会话 cookie */
    data class SmsChallenge(val url: String, val hint: String?, val cookies: List<String>)

    fun onPreGetLoginParam() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.preGetLoginParam()
                .collect { result ->
                    val response = result.getOrNull()
                    val body = response?.body()?.string()

                    body?.apply {
                        requestHash = Jsoup.parse(this).createRequestHash()
                    }
                    response?.apply {
                        try {
                            val cookies = response.headers().values("Set-Cookie")
                            val session = cookies[0]
                            val sessionID = session.substring(0, session.indexOf(";"))
                            CookieUtil.SESSID = sessionID
                        } catch (e: Exception) {
                            toastText.postValue(Event("无法获取cookie"))
                            e.printStackTrace()
                            return@collect
                        }
                        CookieUtil.isGetLoginParam = true
                        onGetLoginParam()
                    }
                }
        }
    }

    private fun onGetLoginParam() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getLoginParam()
                .collect { result ->
                    val response = result.getOrNull()
                    val body = response?.body()?.string()
                    body?.apply {
                        requestHash = Jsoup.parse(this).createRequestHash()
                    }
                    response?.apply {
                        try {
                            val cookies = response.headers().values("Set-Cookie")
                            val session = cookies[0]
                            val sessionID = session.substring(0, session.indexOf(";"))
                            CookieUtil.SESSID = sessionID
                        } catch (e: Exception) {
                            toastText.postValue(Event("无法获取cookie"))
                            e.printStackTrace()
                            return@collect
                        }
                    }
                }
        }
    }

    fun onGetCaptcha() {
        val timeStamp = System.currentTimeMillis().toString()
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getCaptcha("/auth/showCaptchaImage?$timeStamp")
                .collect { result ->
                    val response = result.getOrNull()
                    response?.let {
                        val responseBody = response.body()
                        val bitmap = BitmapFactory.decodeStream(responseBody?.byteStream())
                        showCaptcha.postValue(Event(bitmap))
                    }
                }
        }
    }

    fun onTryLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.tryLogin(loginData)
                .collect { result ->
                    val response = result.getOrNull()
                    val raw = response?.body()?.string()
                    if (response == null || raw.isNullOrEmpty()) {
                        toastText.postValue(
                            Event(result.exceptionOrNull()?.message ?: "登录请求失败，请重试")
                        )
                        return@collect
                    }
                    parseSmsChallenge(raw, response.headers().values("Set-Cookie"))?.let {
                        smsVerify.postValue(Event(it))
                        return@collect
                    }
                    val login: LoginResponse? = try {
                        Gson().fromJson(raw, LoginResponse::class.java)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                    if (login == null) {
                        toastText.postValue(Event("登录响应解析失败，请重试"))
                        return@collect
                    }
                    if (login.status == 1) {
                        try {
                            val headers = response.headers()
                            val cookies = headers.values("Set-Cookie")
                            val uid =
                                cookies[cookies.size - 3].substring(
                                    4,
                                    cookies[cookies.size - 3].indexOf(";")
                                )
                            val name =
                                cookies[cookies.size - 2].substring(
                                    9,
                                    cookies[cookies.size - 2].indexOf(";")
                                )
                            val token =
                                cookies[cookies.size - 1].substring(
                                    6,
                                    cookies[cookies.size - 1].indexOf(";")
                                )
                            PrefManager.isLogin = true
                            PrefManager.uid = uid
                            PrefManager.username = name
                            PrefManager.token = token
                            this@LoginViewModel.uid = uid
                        } catch (e: Exception) {
                            e.printStackTrace()
                            toastText.postValue(Event("无法获取登录凭证"))
                            return@collect
                        }
                        onGetProfile()
                    } else if (login.messageStatus?.startsWith("err_request_captcha") == true
                        && login.message.isNullOrEmpty()
                    ) {
                        // 人机验证被取消或超时：拦截器已原样返回挑战响应
                        toastText.postValue(Event("需要完成人机验证后才能登录，请重试"))
                    } else {
                        login.message?.let {
                            getCaptcha.postValue(Event(it))
                        }
                    }
                }
        }
    }

    /** 短信验证页登录成功后，由 WebView 里读到的 cookie 完成登录 */
    fun onSmsLoginSuccess(uid: String, name: String, token: String) {
        PrefManager.isLogin = true
        PrefManager.uid = uid
        PrefManager.username = name
        PrefManager.token = token
        this.uid = uid
        onGetProfile()
    }

    private fun parseSmsChallenge(raw: String, setCookies: List<String>): SmsChallenge? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        // 登录成功响应不会带验证页地址
        if (json.optInt("status") == 1 && json.has("uid")) return null
        val target = findValidateUrl(json) ?: return null
        // 登录阶段的会话 cookie（SESSID）+ 本次响应新下发的 cookie（后者覆盖同名项）
        val merged = LinkedHashMap<String, String>()
        (listOf(CookieUtil.SESSID, "forward=https://www.coolapk.com") +
            setCookies.map { it.substringBefore(";") })
            .map { it.trim() }
            .filter { it.contains("=") && !it.startsWith("=") }
            .forEach { merged[it.substringBefore("=")] = it }
        val cookies = merged.values.toList()
        return SmsChallenge(
            target,
            findString(json, "otherMessage") ?: json.optString("message").ifEmpty { null },
            cookies
        )
    }

    private fun findValidateUrl(node: Any?, depth: Int = 0): String? {
        if (depth > 4) return null
        when (node) {
            is JSONObject -> {
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (v is String) {
                        if (v.contains("validateLogin")) return absolute(v)
                        if ((k == "key" || k == "validateKey" || k == "loginKey") && v.length > 16)
                            return "https://account.coolapk.com/auth/validateLogin?key=$v"
                    }
                }
                for (k in node.keys()) findValidateUrl(node.opt(k), depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length())
                findValidateUrl(node.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun absolute(v: String): String = when {
        v.startsWith("http") -> v
        v.startsWith("//") -> "https:$v"
        v.startsWith("/") -> "https://account.coolapk.com$v"
        else -> "https://account.coolapk.com/auth/$v"
    }

    private fun findString(node: JSONObject, key: String): String? =
        if (node.has(key)) node.optString(key).ifEmpty { null } else null

    private fun onGetProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getProfile(uid.toString())
                .collect { result ->
                    val data = result.getOrNull()
                    data?.data.let {
                        PrefManager.userAvatar = data?.data?.userAvatar.toString()
                        PrefManager.level = data?.data?.level.toString()
                        PrefManager.experience = data?.data?.experience.toString()
                        PrefManager.nextLevelExperience = data?.data?.nextLevelExperience.toString()
                        afterLogin.postValue(Event(true))
                    }
                }
        }


    }

}