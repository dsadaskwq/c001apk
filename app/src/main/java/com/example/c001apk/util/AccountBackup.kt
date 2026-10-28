package com.example.c001apk.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号数据备份/恢复。
 *
 * 备份内容：登录账号（uid/username/token/头像/等级）+ 设备标识（X-App-Device 等）。
 * 酷安会把登录态与设备绑定，只恢复 token 而设备标识变了，仍可能被判为“陌生设备”，所以一并备份。
 */
object AccountBackup {

    const val DIR_NAME = "c001apk"
    const val FILE_NAME = "c001apk_account_backup.json"

    /** 给用户看的路径说明 */
    const val DISPLAY_DIR = "下载/c001apk/"
    const val FULL_DIR = "/storage/emulated/0/Download/c001apk/"

    private const val FORMAT_VERSION = 1

    class Backup(val json: JSONObject) {
        val uid: String get() = json.optJSONObject("account")?.optString("uid").orEmpty()
        val username: String get() = json.optJSONObject("account")?.optString("username").orEmpty()
        val token: String get() = json.optJSONObject("account")?.optString("token").orEmpty()
    }

    fun hasAccount(): Boolean =
        PrefManager.isLogin && PrefManager.uid.isNotEmpty() && PrefManager.token.isNotEmpty()

    fun build(): String {
        val account = JSONObject()
            .put("uid", PrefManager.uid)
            .put("username", PrefManager.username)
            .put("token", PrefManager.token)
            .put("userAvatar", PrefManager.userAvatar)
            .put("level", PrefManager.level)
            .put("experience", PrefManager.experience)
            .put("nextLevelExperience", PrefManager.nextLevelExperience)
        val device = JSONObject()
            .put("xAppDevice", PrefManager.xAppDevice)
            .put("xAppToken", PrefManager.xAppToken)
            .put("customToken", PrefManager.customToken)
            .put("SZLMID", PrefManager.SZLMID)
            .put("MANUFACTURER", PrefManager.MANUFACTURER)
            .put("BRAND", PrefManager.BRAND)
            .put("MODEL", PrefManager.MODEL)
            .put("BUILDNUMBER", PrefManager.BUILDNUMBER)
            .put("SDK_INT", PrefManager.SDK_INT)
            .put("ANDROID_VERSION", PrefManager.ANDROID_VERSION)
        return JSONObject()
            .put("app", "c001apk")
            .put("version", FORMAT_VERSION)
            .put(
                "time",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )
            .put("account", account)
            .put("device", device)
            // 方便其它支持 Cookie 登录的客户端直接使用
            .put(
                "cookie",
                "uid=${PrefManager.uid}; username=${PrefManager.username}; token=${PrefManager.token}"
            )
            .toString(2)
    }

    /** 解析并校验；不合法返回 null */
    fun parse(text: String): Backup? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("app") != "c001apk") return null
        val b = Backup(json)
        if (b.uid.isEmpty() || b.token.isEmpty()) return null
        return b
    }

    fun restore(b: Backup) {
        val acc = b.json.optJSONObject("account")
        val dev = b.json.optJSONObject("device")

        // 先恢复设备标识，再写登录信息
        dev?.let { d ->
            fun str(k: String): String? = if (d.has(k)) d.optString(k) else null
            str("xAppDevice")?.takeIf { it.isNotEmpty() }?.let { PrefManager.xAppDevice = it }
            str("xAppToken")?.let { PrefManager.xAppToken = it }
            if (d.has("customToken")) PrefManager.customToken = d.optBoolean("customToken")
            str("SZLMID")?.let { PrefManager.SZLMID = it }
            str("MANUFACTURER")?.let { PrefManager.MANUFACTURER = it }
            str("BRAND")?.let { PrefManager.BRAND = it }
            str("MODEL")?.let { PrefManager.MODEL = it }
            str("BUILDNUMBER")?.let { PrefManager.BUILDNUMBER = it }
            str("SDK_INT")?.takeIf { it.isNotEmpty() }?.let { PrefManager.SDK_INT = it }
            str("ANDROID_VERSION")?.let { PrefManager.ANDROID_VERSION = it }
        }
        acc?.let { a ->
            PrefManager.uid = b.uid
            PrefManager.username = b.username
            PrefManager.token = b.token
            PrefManager.userAvatar = a.optString("userAvatar")
            PrefManager.level = a.optString("level")
            PrefManager.experience = a.optString("experience")
            PrefManager.nextLevelExperience = a.optString("nextLevelExperience")
        }
        PrefManager.isLogin = true
    }

    /**
     * Android 10+：写入公共“下载/c001apk/”目录（无需存储权限）。
     * 返回是否成功；低版本由调用方改用系统“另存为”。
     */
    fun saveToDownloads(context: Context, text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val resolver = context.contentResolver
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            // 覆盖本应用之前写入的同名备份（别的安装写入的文件无权删除，会生成带序号的新文件）
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(FILE_NAME, relative),
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null)
                    }
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: return false
            true
        }.getOrDefault(false)
    }

    fun writeTo(context: Context, uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: return false
        true
    }.getOrDefault(false)

    fun readFrom(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
