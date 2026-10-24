package com.example.c001apk.constant

import com.example.c001apk.MyApplication.Companion.context
import rikka.core.util.ResourceUtils

object Constants {
    const val REQUEST_WITH = "XMLHttpRequest"
    const val LOCALE = "zh-CN"
    const val APP_ID = "com.coolapk.market"
    var DARK_MODE =
        if (ResourceUtils.isNightMode(context.resources.configuration)) "1"
        else "0"
    const val CHANNEL = "coolapk"
    const val MODE = "universal"
    const val APP_LABEL = "token://com.coolapk.market/dcf01e569c1e3db93a3d0fcf191a622c"
    const val VERSION_NAME = "16.6.4"
    const val API_VERSION = "16"
    const val VERSION_CODE = "2609291"
    // 服务端按版本白名单放行 v6 接口；新版酷安此头固定为该基线值，不随自身版本号变化
    const val SUPPORTED_CODE = "2310232"
    const val SDK_VERSION_CODE = "33"
    const val SZLM_ID = "数字联盟ID不能为空"
    const val LOADING_FAILED = "加载失败"
    const val LOADING_EMPTY = "什么也没有"
    const val LOADING_END = "没有更多了"
}