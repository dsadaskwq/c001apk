package com.example.c001apk.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.util.CookieUtil
import com.example.c001apk.util.Event
import com.example.c001apk.util.PrefManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val networkRepo: NetworkRepo
) : ViewModel() {

    var badge: Int = 0
    var isInit: Boolean = true
    val setBadge = MutableLiveData<Event<Boolean>>()

    fun getCheckLoginInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.checkLoginInfo()
                .collect { result ->
                    val response = result.getOrNull()
                    response?.let {
                        response.body()?.let {
                            if (response.body()?.data?.token != null) {
                                response.body()?.data?.let { login ->
                                    badge = login.notifyCount.badge
                                    CookieUtil.atme = login.notifyCount.atme
                                    CookieUtil.atcommentme = login.notifyCount.atcommentme
                                    CookieUtil.feedlike = login.notifyCount.feedlike
                                    CookieUtil.contacts_follow = login.notifyCount.contactsFollow
                                    PrefManager.isLogin = true
                                    PrefManager.uid = login.uid
                                    PrefManager.username =
                                        withContext(Dispatchers.IO) {
                                            URLEncoder.encode(login.username, "UTF-8")
                                        }
                                    PrefManager.token = login.token
                                    PrefManager.userAvatar = login.userAvatar
                                }
                            } else if (response.body()?.message == "登录信息有误") {
                                PrefManager.isLogin = false
                                PrefManager.uid = ""
                                PrefManager.username = ""
                                PrefManager.token = ""
                                PrefManager.userAvatar = ""
                            }

                            try {
                                val headers = response.headers()
                                val cookies = headers.values("Set-Cookie")
                                val session = cookies[0]
                                val sessionID = session.substring(0, session.indexOf(";"))
                                CookieUtil.SESSID = sessionID
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            if (badge != 0)
                                setBadge.postValue(Event(true))

                        }
                    }
                }
        }
    }

}