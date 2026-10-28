package com.example.c001apk.util

import android.content.Context
import android.os.Build
import android.util.Base64
import com.example.c001apk.MyApplication.Companion.context
import com.example.c001apk.constant.Constants
import com.example.c001apk.util.Utils.getBase64
import com.example.c001apk.util.Utils.getMD5
import com.example.c001apk.util.Utils.randomAndroidVersionRelease
import com.example.c001apk.util.Utils.randomBrand
import com.example.c001apk.util.Utils.randomDeviceModel
import com.example.c001apk.util.Utils.randomManufacturer
import com.example.c001apk.util.Utils.randomSdkInt
import org.mindrot.jbcrypt.BCrypt
import java.util.Random


object TokenDeviceUtils {

    fun randHexString(@Suppress("SameParameterValue") n: Int): String {
        Random().setSeed(System.currentTimeMillis())
        return (0 until n).joinToString("") {
            Random().nextInt(256).toString(16)
        }.uppercase()
    }

    /**
     * 使用当前真实设备的信息（默认设备信息），并据此重新生成 X-App-Device
     */
    fun useSystemDeviceInfo() {
        PrefManager.apply {
            MANUFACTURER = Build.MANUFACTURER
            BRAND = Build.BRAND
            MODEL = Build.MODEL
            BUILDNUMBER = Build.DISPLAY
            SDK_INT = Build.VERSION.SDK_INT.toString()
            ANDROID_VERSION = Build.VERSION.RELEASE
        }
        PrefManager.xAppDevice = getDeviceCode(false)
    }

    fun getDeviceCode(regenerate: Boolean): String {
        if (regenerate) {
            PrefManager.apply {
                MANUFACTURER = randomManufacturer()
                BRAND = randomBrand()
                MODEL = randomDeviceModel()
                BUILDNUMBER = randHexString(16)
                SDK_INT = randomSdkInt()
                ANDROID_VERSION = randomAndroidVersionRelease()
            }
        }
        val szlmId = if (PrefManager.SZLMID == "") randHexString(16) else PrefManager.SZLMID
        val mac = Utils.randomMacAddress()
        val manuFactor = PrefManager.MANUFACTURER
        val brand = PrefManager.BRAND
        val model = PrefManager.MODEL
        val buildNumber = PrefManager.BUILDNUMBER
        return DeviceCode.encode("$szlmId; ; ; $mac; $manuFactor; $brand; $model; $buildNumber; null")
    }

    // 与 coolapk-desktop 的 auth.rs 同源（TG-Twilight/c001apk 同源实现）：
    // 密料中按时间与版本号轮换取段，bcrypt 代价为 04，失败时换秒重试最多 16 次
    fun String.getTokenV3(): String {
        val appCode = Constants.VERSION_CODE.toInt()
        val now = System.currentTimeMillis() / 1000
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val blob = Base64.decode(Constants.TOKEN_V3_DATA, Base64.DEFAULT)
        for (offset in 0..15) {
            val time = now + offset
            val index = ((time + appCode) % 100).toInt() * 4 + 0x80
            val segment = String(
                Base64.decode(String(blob, index, 0x80, Charsets.US_ASCII), Base64.DEFAULT),
                Charsets.UTF_8
            )
            val plain = "${Constants.APP_ID}&$segment&${this.getMD5()}&$time&$appCode"
            val password = plain.toByteArray().let { Base64.encodeToString(it, Base64.NO_WRAP) }.getMD5()
            val source = "${time.toString(16)}/${plain.getMD5()}".getBase64(true).substring(0, 22)
            val last = alphabet.indexOf(source[21])
            if (last < 0) continue
            val salt = source.substring(0, 21) + alphabet[(last - 5 + 64) % 64]
            if (!Regex("^[./A-Za-z0-9]{22}$").matches(salt)) continue
            val hash = BCrypt.hashpw(password, "\$2a\$04\$$salt").replaceRange(0, 3, "\$2y")
            return "v3" + hash.toByteArray().let { Base64.encodeToString(it, Base64.NO_WRAP) }.replace("=", "")
        }
        throw IllegalStateException("无法生成请求签名，请检查系统时间")
    }

    fun String.getTokenV2(): String {
        val timeStamp = (System.currentTimeMillis() / 1000f).toString()

        val base64TimeStamp = timeStamp.getBase64()
        val md5TimeStamp = timeStamp.getMD5()
        val md5DeviceCode = this.getMD5()

        val token = "${Constants.APP_LABEL}?$md5TimeStamp$$md5DeviceCode&${Constants.APP_ID}"
        val base64Token = token.getBase64()
        val md5Base64Token = base64Token.getMD5()
        val md5Token = token.getMD5()

        val bcryptSalt = "${"$2a$10$$base64TimeStamp/$md5Token".substring(0, 31)}u"
        val bcryptResult = BCrypt.hashpw(md5Base64Token, bcryptSalt)

        return "v2${bcryptResult.replaceRange(0, 3, "$2y").getBase64()}"
    }

    fun getLastingDeviceCode(): String {
        // 首次使用默认采用当前真实设备信息，而不是随机设备信息
        if (PrefManager.xAppDevice == "")
            useSystemDeviceInfo()
        return PrefManager.xAppDevice
    }

    fun getLastingInstallTime(context: Context): String {
        val sp = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        return sp.getString("INSTALL_TIME", null).let {
            it ?: System.currentTimeMillis().toString().apply {
                sp.edit().putString("INSTALL_TIME", this).apply()
            }
        }
    }

    fun getLastingBrand(): String {
        val sp = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        return sp.getString("BRAND", null).let {
            it ?: randomBrand().apply {
                sp.edit().putString("BRAND", this).apply()
            }
        }
    }

    fun getLastingModel(): String {
        val sp = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        return sp.getString("MODEL", null).let {
            it ?: randomDeviceModel().apply {
                sp.edit().putString("MODEL", this).apply()
            }
        }
    }

    fun getLastingSdkInt(): String {
        val sp = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        return sp.getString("SDK_INT", null).let {
            it ?: randomSdkInt().apply {
                sp.edit().putString("SDK_INT", this).apply()
            }
        }
    }

    fun getLastingAndroidVersionRelease(): String {
        val sp = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        return sp.getString("ANDROID_VERSION_RELEASE", null).let {
            it ?: randomAndroidVersionRelease().apply {
                sp.edit().putString("ANDROID_VERSION_RELEASE", this).apply()
            }
        }
    }

    fun regenerateDeviceInfo(context: Context) {
        context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE).edit().apply {
            putString("DEVICE_CODE", getDeviceCode(false))
            putString("INSTALL_TIME", System.currentTimeMillis().toString())
            putString("BRAND", randomBrand())
            putString("MODEL", randomDeviceModel())
            putString("SDK_INT", randomSdkInt())
            putString("ANDROID_VERSION_RELEASE", randomAndroidVersionRelease())
        }.apply()
    }

}