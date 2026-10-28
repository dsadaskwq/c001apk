package com.example.c001apk.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.example.c001apk.logic.network.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 发动态/回复的图片上传：
 * 1. 压缩（GIF 与不大的 JPEG 保持原图）
 * 2. /v6/upload/ossUploadPrepare 申请上传凭证与文件名
 * 3. 用返回的 STS 凭证对 OSS 做签名 PUT
 * 4. 返回服务端分配的文件名，作为发布接口 pic 字段（逗号分隔）
 *
 */
object ImageUploader {

    class UploadException(message: String) : Exception(message)

    private val http = OkHttpClient()

    private class Prepared(
        val name: String,
        val bytes: ByteArray,
        val mime: String,
        val width: Int,
        val height: Int,
        val md5: String
    )

    suspend fun upload(
        context: Context,
        uris: List<Uri>,
        feedType: String,   // 发动态 "feed"，回复 "comment"（官方抓包）
        onProgress: (done: Int, total: Int) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyList()
        try {
            onProgress(0, uris.size)
            val files = uris.mapIndexed { i, uri -> prepareFile(context, uri, i) }
            val keys = prepareOss(files, feedType)
            val result = ArrayList<String>()
            files.forEachIndexed { i, f ->
                val url = putToOss(f, keys[i])
                result.add(url)
                onProgress(i + 1, files.size)
            }
            result
        } catch (e: UploadException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UploadException("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------- 压缩 ----------------

    private fun prepareFile(context: Context, uri: Uri, index: Int): Prepared {
        val cr = context.contentResolver
        val original = cr.openInputStream(uri)?.use { it.readBytes() }
            ?: throw UploadException("无法读取图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UploadException("不是有效的图片")
        val mime = bounds.outMimeType ?: "image/jpeg"
        val stamp = System.currentTimeMillis()

        if (mime == "image/gif" || (mime == "image/jpeg" && original.size <= 3 * 1024 * 1024)) {
            val ext = if (mime == "image/gif") "gif" else "jpg"
            return Prepared(
                "pic_${stamp}_$index.$ext", original, mime,
                bounds.outWidth, bounds.outHeight, md5(original)
            )
        }

        // 其余格式（PNG/WebP/大 JPEG）：缩放 + 修正方向 + 转 JPEG
        val maxSide = 2560
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(
            original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw UploadException("图片解码失败")
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val degrees = runCatching {
            when (ExifInterface(original.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (scale < 1f || degrees != 0f) {
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (degrees != 0f) postRotate(degrees)
            }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        // 透明背景铺白，避免转 JPEG 后变黑
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val bytes = out.toByteArray()
        return Prepared(
            "pic_${stamp}_$index.jpg", bytes, "image/jpeg",
            flat.width, flat.height, md5(bytes)
        )
    }

    private fun md5(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    // ---------------- prepare ----------------

    private class OssTarget(
        val objectKey: String,
        val accessKeyId: String,
        val accessKeySecret: String,
        val securityToken: String?,
        val host: String,   // 形如 https://bucket.oss-cn-xxx.aliyuncs.com
        val callbackUrl: String?,
        val imagePrefix: String?
    ) {
        val bucket: String get() = Regex("https?://([^./]+)\\.").find(host)?.groupValues?.get(1).orEmpty()
    }

    private suspend fun prepareOss(files: List<Prepared>, feedType: String): List<OssTarget> {
        val list = JSONArray()
        files.forEach {
            list.put(
                JSONObject()
                    .put("name", it.name)
                    .put("resolution", "${it.width}x${it.height}")
                    .put("md5", it.md5)
                    .put("hdr", 0)
            )
        }
        val data = HashMap<String, String?>().apply {
            put("uploadBucket", "image")
            put("uploadDir", "feed")
            put("is_anonymous", "0")
            put("uploadFileList", list.toString())
            put("toUid", "")
            put("feed_type", feedType)
        }
        val raw = Network.postOssUploadPrepare(data).string()
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw UploadException("上传准备失败：响应不是 JSON")
        val msg = root.optString("message")
        val dataNode = root.optJSONObject("data")
            ?: throw UploadException("上传准备失败：${msg.ifEmpty { raw.take(120) }}")

        val infoArr = findArray(dataNode, listOf("fileInfo", "uploadFileList", "files"))
        return files.indices.map { i ->
            val item = infoArr?.optJSONObject(i)
            fun get(vararg aliases: String): String? =
                (item?.let { findString(it, aliases.toList()) }) ?: findString(dataNode, aliases.toList())

            val key = item?.let {
                findString(it, listOf("uploadFileName", "objectKey", "uploadObjectName", "ossKey"))
            } ?: findString(dataNode, listOf("uploadFileName", "objectKey"))
            val ak = get("accessKeyId", "ossAccessKeyId", "keyId")
            val sk = get("accessKeySecret", "ossAccessKeySecret", "secretAccessKey")
            val token = get("securityToken", "stsToken", "ossSecurityToken", "token")
            val bucket = get("bucket", "bucketName", "uploadBucket")
            val endpoint = get("endpoint", "endPoint", "ossEndpoint", "uploadEndpoint")
            val hostFull = get("uploadHost", "uploadUrl", "host")

            val missing = listOf(
                "文件名" to key, "accessKeyId" to ak, "accessKeySecret" to sk
            ).filter { it.second.isNullOrEmpty() }.map { it.first }
            if (missing.isNotEmpty()) {
                throw UploadException("上传准备返回缺少字段：${missing.joinToString()}（已记录日志）")
            }
            val host = when {
                !hostFull.isNullOrEmpty() && hostFull.startsWith("http") -> hostFull.trimEnd('/')
                !hostFull.isNullOrEmpty() -> "https://${hostFull.trimEnd('/')}"
                !endpoint.isNullOrEmpty() && !bucket.isNullOrEmpty() -> {
                    val ep = endpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')
                    if (ep.startsWith("$bucket.")) "https://$ep" else "https://$bucket.$ep"
                }
                else -> throw UploadException("上传准备返回缺少上传地址（已记录日志）")
            }
            OssTarget(
                key!!, ak!!, sk!!, token, host,
                get("callbackUrl", "uploadCallbackUrl"),
                get("uploadImagePrefix")
            )
        }
    }

    private fun findArray(node: Any?, keys: List<String>, depth: Int = 0): JSONArray? {
        if (depth > 4) return null
        when (node) {
            is JSONObject -> {
                for (k in node.keys()) {
                    if (keys.any { it.equals(k, true) }) node.optJSONArray(k)?.let { return it }
                }
                for (k in node.keys()) findArray(node.opt(k), keys, depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length())
                findArray(node.opt(i), keys, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findString(node: Any?, keys: List<String>, depth: Int = 0): String? {
        if (depth > 4) return null
        when (node) {
            is JSONObject -> {
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (v is String && v.isNotEmpty() && keys.any { it.equals(k, true) }) return v
                }
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (v is JSONObject) findString(v, keys, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    // ---------------- OSS PUT（V1 签名） ----------------

    // ---------------- OSS PUT（V1 签名 + 上传回调，与官方客户端抓包一致） ----------------

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    /** 上传一张图，返回可直接用于发布接口 pic 字段的完整地址 */
    private fun putToOss(f: Prepared, t: OssTarget): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

        // 官方：callbackBody 为 JSON，仅 bucket/object/hasProcess；hasProcess 来自 x:var1
        val callbackB64 = t.callbackUrl?.takeIf { it.isNotEmpty() }?.let { url ->
            b64(
                JSONObject()
                    .put("callbackBodyType", "application/json")
                    .put("callbackHost", "api.coolapk.com")
                    .put("callbackUrl", url)
                    .put(
                        "callbackBody",
                        "{\"bucket\":\${bucket},\"object\":\${object},\"hasProcess\":\${x:var1}}"
                    )
                    .toString()
            )
        }
        val callbackVarB64 = if (callbackB64 != null) b64("{\"x:var1\":\"false\"}") else null

        // CanonicalizedOSSHeaders：x-oss-* 按名称字典序
        val ossHeaders = StringBuilder().apply {
            if (callbackB64 != null) append("x-oss-callback:").append(callbackB64).append("\n")
            if (callbackVarB64 != null) append("x-oss-callback-var:").append(callbackVarB64).append("\n")
            if (!t.securityToken.isNullOrEmpty())
                append("x-oss-security-token:").append(t.securityToken).append("\n")
        }.toString()
        val resource = "/${t.bucket}/${t.objectKey}"
        val stringToSign = "PUT\n\n${f.mime}\n$date\n$ossHeaders$resource"
        val sig = Base64.encodeToString(
            Mac.getInstance("HmacSHA1").apply {
                init(SecretKeySpec(t.accessKeySecret.toByteArray(), "HmacSHA1"))
            }.doFinal(stringToSign.toByteArray()),
            Base64.NO_WRAP
        )
        val req = Request.Builder()
            .url("${t.host}/${t.objectKey}")
            .put(f.bytes.toRequestBody(f.mime.toMediaType()))
            .header("Date", date)
            .header("Authorization", "OSS ${t.accessKeyId}:$sig")
            .apply {
                if (callbackB64 != null) header("x-oss-callback", callbackB64)
                if (callbackVarB64 != null) header("x-oss-callback-var", callbackVarB64)
                if (!t.securityToken.isNullOrEmpty()) header("x-oss-security-token", t.securityToken)
            }
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty().take(600)
            if (r.code == 203) throw UploadException("图片已上传，但服务端回调失败：$body")
            if (!r.isSuccessful) throw UploadException("图片上传失败 HTTP ${r.code}：$body")
            // 回调响应：{"data":{"url":"http://image.coolapk.com/feed/....jpg"}}；失败时为 status<0
            val j = runCatching { JSONObject(body) }.getOrNull()
            if (j != null && (j.optInt("status", 1) < 0 || j.optInt("error", 0) != 0)) {
                throw UploadException("服务端回调被拒绝：${j.optString("message", body)}")
            }
            val cbUrl = j?.optJSONObject("data")?.optString("url").orEmpty()
            return cbUrl.ifEmpty {
                // 兜底：官方 pic 即 uploadImagePrefix + "/" + 文件名
                "${(t.imagePrefix ?: "http://image.coolapk.com").trimEnd('/')}/${t.objectKey}"
            }
        }
    }
}
