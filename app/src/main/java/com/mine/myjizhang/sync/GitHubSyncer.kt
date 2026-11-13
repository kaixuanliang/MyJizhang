package com.mine.myjizhang.sync

import android.util.Base64
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.entity.AccountBook
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 同步配置（持久化在本地 SharedPreferences） */
data class SyncConfig(
    var repo: String = "",      // owner/repo
    var branch: String = "main",
    var token: String = "",     // GitHub Personal Access Token
    var password: String = "",  // 加密密码（为空表示不加密）
    var path: String = "jizhang-backup.bin",
    var lastSyncTime: Long = 0,
    var lastSyncAction: String = ""
)

sealed class SyncResult {
    data class Success(val message: String) : SyncResult()
    data class Failure(val message: String) : SyncResult()
}

/**
 * GitHub 仓库同步器：
 * - 上传：全量数据打包为 JSON，可选 AES-256-GCM 加密，经 Contents API 提交（单文件覆盖）
 * - 恢复：下载备份文件，解密后整体替换本地数据
 */
object GitHubSyncer {

    private const val MAGIC = "MJZ1"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_BITS = 256
    private const val PBKDF2_ITER = 65536

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---------- 加解密 ----------

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + encrypted
    }

    fun isEncrypted(data: ByteArray): Boolean =
        data.size > 32 && String(data, 0, 4, Charsets.US_ASCII) == MAGIC

    fun decrypt(data: ByteArray, password: String): ByteArray {
        val salt = data.copyOfRange(4, 4 + SALT_LEN)
        val iv = data.copyOfRange(4 + SALT_LEN, 4 + SALT_LEN + IV_LEN)
        val body = data.copyOfRange(4 + SALT_LEN + IV_LEN, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITER, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // ---------- 快照序列化 ----------

    suspend fun snapshot(db: AppDatabase): ByteArray {
        val json = JSONObject()
        json.put("version", 1)
        json.put("exportedAt", System.currentTimeMillis())
        json.put("books", JSONArray().apply {
            db.bookDao().getAll().forEach {
                put(JSONObject().apply {
                    put("id", it.id); put("name", it.name)
                    put("positionWeight", it.positionWeight); put("createTime", it.createTime)
                })
            }
        })
        json.put("categories", JSONArray().apply {
            db.categoryDao().getAll().forEach {
                put(JSONObject().apply {
                    put("id", it.id); put("name", it.name); put("type", it.type)
                    put("icon", it.icon); put("positionWeight", it.positionWeight); put("hide", it.hide)
                })
            }
        })
        json.put("assets", JSONArray().apply {
            db.bookDao().getAll().forEach { book ->
                db.assetDao().getByBook(book.id).forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("bookId", it.bookId); put("name", it.name)
                        put("type", it.type); put("amount", it.amount); put("totalQuota", it.totalQuota)
                        put("chargeOffDate", it.chargeOffDate); put("repaymentDate", it.repaymentDate)
                        put("inTotalAsset", it.inTotalAsset)
                        put("subType", it.subType); put("icon", it.icon)
                        put("remark", it.remark)
                        put("positionWeight", it.positionWeight); put("createTime", it.createTime)
                        put("updateTime", it.updateTime)
                    })
                }
            }
        })
        json.put("bills", JSONArray().apply {
            db.billDao().getAll().forEach {
                put(JSONObject().apply {
                    put("id", it.id); put("bookId", it.bookId); put("type", it.type)
                    put("amount", it.amount); put("categoryId", it.categoryId)
                    put("assetId", it.assetId); put("fromAssetId", it.fromAssetId)
                    put("toAssetId", it.toAssetId); put("serviceCharge", it.serviceCharge)
                    put("discount", it.discount); put("remark", it.remark); put("time", it.time)
                    put("refundAmount", it.refundAmount); put("shouldReimburse", it.shouldReimburse)
                    put("reimburseAmount", it.reimburseAmount); put("recordType", it.recordType)
                    put("createTime", it.createTime); put("updateTime", it.updateTime)
                })
            }
        })
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun restore(db: AppDatabase, data: ByteArray): Triple<Int, Int, Int> {
        val json = JSONObject(String(data, Charsets.UTF_8))
        val books = mutableListOf<AccountBook>()
        val categories = mutableListOf<Category>()
        val assets = mutableListOf<Asset>()
        val bills = mutableListOf<Bill>()

        json.optJSONArray("books")?.forEachObj { o ->
            books.add(AccountBook(o.getString("id"), o.getString("name"),
                o.optInt("positionWeight"), o.optLong("createTime")))
        }
        json.optJSONArray("categories")?.forEachObj { o ->
            categories.add(Category(o.getString("id"), o.getString("name"), o.getInt("type"),
                o.optString("icon", "💡"), o.optInt("positionWeight"), o.optBoolean("hide")))
        }
        json.optJSONArray("assets")?.forEachObj { o ->
            assets.add(Asset(o.getString("id"), o.getString("bookId"), o.getString("name"),
                o.optInt("type"), o.optDouble("amount"), o.optDouble("totalQuota"),
                o.optInt("chargeOffDate", 1), o.optInt("repaymentDate", 10),
                o.optBoolean("inTotalAsset", true),
                o.optString("subType"), o.optString("icon"), o.optString("remark"),
                o.optInt("positionWeight"), o.optLong("createTime"), o.optLong("updateTime")))
        }
        json.optJSONArray("bills")?.forEachObj { o ->
            bills.add(Bill(o.getString("id"), o.getString("bookId"), o.getInt("type"),
                o.getDouble("amount"), o.optString("categoryId"), o.optString("assetId"),
                o.optString("fromAssetId"), o.optString("toAssetId"),
                o.optDouble("serviceCharge"), o.optDouble("discount"), o.optString("remark"),
                o.getLong("time"), o.optDouble("refundAmount"), o.optBoolean("shouldReimburse"),
                o.optDouble("reimburseAmount"), o.optInt("recordType"),
                o.optLong("createTime"), o.optLong("updateTime")))
        }

        // 清空后整批恢复
        db.billDao().deleteAll()
        db.bookDao().getAll().forEach { db.bookDao().delete(it) }
        db.categoryDao().getAll().forEach { db.categoryDao().delete(it) }
        books.forEach { book -> db.assetDao().getByBook(book.id).forEach { db.assetDao().delete(it) } }

        books.forEach { db.bookDao().insert(it) }
        categories.forEach { db.categoryDao().insert(it) }
        assets.forEach { db.assetDao().insert(it) }
        bills.forEach { db.billDao().insert(it) }
        return Triple(books.size, assets.size, bills.size)
    }

    private inline fun JSONArray.forEachObj(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) action(getJSONObject(i))
    }

    // ---------- GitHub Contents API ----------

    private fun contentUrl(cfg: SyncConfig) =
        "https://api.github.com/repos/${cfg.repo}/contents/${cfg.path}?ref=${cfg.branch}"

    private fun apiRequest(cfg: SyncConfig, url: String, method: String, body: String? = null): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.token}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: "").toRequestBody("application/json".toMediaType()))
            else -> builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        }
        return builder.build()
    }

    /** 获取远端已有文件的 sha（覆盖上传必须携带），不存在返回 null */
    private suspend fun getRemoteSha(cfg: SyncConfig): String? = withContext(Dispatchers.IO) {
        val resp = client.newCall(apiRequest(cfg, contentUrl(cfg), "GET")).execute()
        resp.use {
            if (it.code == 404) return@withContext null
            if (!it.isSuccessful) throw RuntimeException("GitHub API ${it.code}")
            JSONObject(it.body!!.string()).optString("sha").ifBlank { null }
        }
    }

    suspend fun testConnection(cfg: SyncConfig): SyncResult = try {
        require(cfg.repo.isNotBlank()) { "请填写仓库（格式 owner/repo）" }
        require(cfg.token.isNotBlank()) { "请填写 Token" }
        withContext(Dispatchers.IO) {
            val req = apiRequest(cfg, "https://api.github.com/repos/${cfg.repo}", "GET")
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) SyncResult.Success("连接成功：${JSONObject(resp.body!!.string()).optString("full_name")}")
                else SyncResult.Failure("连接失败 HTTP ${resp.code}（检查仓库名与 Token 权限）")
            }
        }
    } catch (e: Exception) {
        SyncResult.Failure(e.message ?: "连接失败")
    }

    suspend fun upload(cfg: SyncConfig, db: AppDatabase): SyncResult = try {
        require(cfg.repo.isNotBlank() && cfg.token.isNotBlank()) { "请先配置仓库与 Token" }
        val plain = snapshot(db)
        val payload = if (cfg.password.isNotBlank()) encrypt(plain, cfg.password) else plain
        val contentBase64 = Base64.encodeToString(payload, Base64.NO_WRAP)
        val sha = getRemoteSha(cfg)

        val body = JSONObject().apply {
            put("message", "我的记账本备份 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date()))
            put("content", contentBase64)
            put("branch", cfg.branch)
            if (sha != null) put("sha", sha)
        }
        val billCount = JSONObject(String(plain, Charsets.UTF_8)).optJSONArray("bills")?.length() ?: 0
        withContext(Dispatchers.IO) {
            client.newCall(apiRequest(cfg, contentUrl(cfg), "PUT", body.toString())).execute().use { resp ->
                if (resp.isSuccessful) {
                    SyncResult.Success("上传成功：$billCount 笔账单，${payload.size / 1024} KB${if (cfg.password.isNotBlank()) "（已加密）" else ""}")
                } else {
                    SyncResult.Failure("上传失败 HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                }
            }
        }
    } catch (e: Exception) {
        SyncResult.Failure(e.message ?: "上传失败")
    }

    suspend fun downloadAndRestore(cfg: SyncConfig, db: AppDatabase): SyncResult = try {
        require(cfg.repo.isNotBlank() && cfg.token.isNotBlank()) { "请先配置仓库与 Token" }
        withContext(Dispatchers.IO) {
            val resp = client.newCall(apiRequest(cfg, contentUrl(cfg), "GET")).execute()
            resp.use { it ->
                if (it.code == 404) return@use SyncResult.Failure("仓库里还没有备份文件")
                if (!it.isSuccessful) return@use SyncResult.Failure("下载失败 HTTP ${it.code}")
                val json = JSONObject(it.body!!.string())
                val encoding = json.optString("encoding")
                if (encoding != "base64") return@use SyncResult.Failure("不支持的文件编码: $encoding")
                val content = Base64.decode(json.getString("content").replace("\n", ""), Base64.NO_WRAP)
                val plain = if (GitHubSyncer.isEncrypted(content)) {
                    if (cfg.password.isBlank()) return@use SyncResult.Failure("备份已加密，请先填写加密密码")
                    try {
                        GitHubSyncer.decrypt(content, cfg.password)
                    } catch (e: Exception) {
                        return@use SyncResult.Failure("解密失败：密码错误")
                    }
                } else content

                val (nBook, nAsset, nBill) = GitHubSyncer.restore(db, plain)
                SyncResult.Success("恢复成功：$nBook 个账本、$nAsset 个账户、$nBill 笔账单")
            }
        }
    } catch (e: Exception) {
        SyncResult.Failure(e.message ?: "恢复失败")
    }
}
