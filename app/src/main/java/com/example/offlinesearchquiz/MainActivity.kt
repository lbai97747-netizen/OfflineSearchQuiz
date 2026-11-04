package com.example.offlinesearchquiz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.offlinesearchquiz.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/** 只保留字母、数字、汉字，忽略标点/空白/换行，减少 OCR 噪声影响 */
private fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

/** 二元组（相邻两个字）集合，用于模糊匹配 */
private fun bigrams(s: String): Set<String> = when {
    s.isEmpty() -> emptySet()
    s.length == 1 -> setOf(s)
    else -> (0 until s.length - 1).mapTo(HashSet()) { s.substring(it, it + 2) }
}

class MainActivity : AppCompatActivity() {

    private data class Item(val question: String, val answer: String) {
        val norm: String = normalize(question)
        val grams: Set<String> = bigrams(norm)
    }

    private lateinit var binding: ActivityMainBinding
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var bank: List<Item> = emptyList()
    private var photoPath: String? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val p = photoPath
            if (ok && p != null) handleImage(Uri.fromFile(File(p)))
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handleImage(uri)
        }

    private val pickBank =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importBank(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        photoPath = savedInstanceState?.getString("photoPath")

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnImportBank.setOnClickListener { pickBank.launch("*/*") }
        binding.btnTextSearch.setOnClickListener {
            val t = binding.etSearch.text.toString().trim()
            if (t.isEmpty()) toast("请先输入题目文字") else runSearch(t)
        }

        loadSavedBank()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("photoPath", photoPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        worker.shutdown()
    }

    // ---------- 拍照 / 选图 / OCR ----------

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "capture.jpg")
            photoPath = file.absolutePath
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("无法打开相机：${e.message}")
        }
    }

    private fun handleImage(uri: Uri) {
        showResult("识别中…")
        worker.execute {
            val bmp = loadBitmap(uri)
            runOnUiThread {
                if (bmp == null) {
                    showResult("图片读取失败")
                    return@runOnUiThread
                }
                binding.ivPreview.setImageBitmap(bmp)
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { result ->
                        val text = result.text
                        if (text.isBlank()) {
                            showResult("没有识别到文字，请对准题目重拍")
                        } else {
                            binding.etSearch.setText(text.trim())
                            runSearch(text)
                        }
                    }
                    .addOnFailureListener { e -> showResult("识别失败：${e.message}") }
            }
        }
    }

    /** 缩小到最长边 2048 以内并按 EXIF 转正，避免大图 OOM 和横竖错误 */
    private fun loadBitmap(uri: Uri, maxSide: Int = 2048): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) bmp else Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(degrees) }, true
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 题库 ----------

    private fun loadSavedBank() {
        worker.execute {
            val items = try {
                val f = File(filesDir, BANK_FILE)
                val text = if (f.exists()) f.readText()
                else assets.open(BANK_FILE).bufferedReader().use { it.readText() }
                parseBank(text)
            } catch (e: Exception) {
                emptyList()
            }
            bank = items
            runOnUiThread { updateBankInfo() }
        }
    }

    private fun importBank(uri: Uri) {
        worker.execute {
            try {
                val raw = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val text = raw.toString(Charsets.UTF_8).removePrefix("﻿")
                val items = parseBank(text)
                if (items.isEmpty()) throw IllegalArgumentException("没有读到任何题目")
                File(filesDir, BANK_FILE).writeText(text)
                bank = items
                runOnUiThread {
                    updateBankInfo()
                    showResult("导入成功：共 ${items.size} 道题")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showResult("导入失败：${e.message}\n请确认是 UTF-8 编码的 JSON 数组，每项含 question 和 answer。")
                }
            }
        }
    }

    private fun parseBank(text: String): List<Item> {
        val arr = JSONArray(text)
        val list = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val q = o.optString("question", o.optString("q", "")).trim()
            val a = o.optString("answer", o.optString("a", "")).trim()
            if (q.isNotEmpty()) list.add(Item(q, a))
        }
        return list
    }

    private fun updateBankInfo() {
        binding.tvBankInfo.text = "当前题库：${bank.size} 道题（全部离线保存在手机里）"
    }

    // ---------- 匹配 ----------

    private fun runSearch(ocrText: String) {
        if (bank.isEmpty()) {
            showResult("题库是空的，请先点「上传题库」导入 questions.json。\n\n—— 识别到的文字 ——\n$ocrText")
            return
        }
        showResult("匹配中…")
        worker.execute {
            val out = buildResult(ocrText)
            runOnUiThread { showResult(out) }
        }
    }

    private fun buildResult(ocrText: String): String {
        val norm = normalize(ocrText)
        if (norm.isBlank()) return "没有识别到有效题目文字。请重新拍照或手动输入。"

        // 只从题库检索，不生成、不推理题库之外的答案。
        val scored = bank.map { item -> item to score(item, norm) }
            .sortedByDescending { it.second }
            .take(3)
            .filter { it.second >= MIN_SCORE }

        val sb = StringBuilder()
        if (scored.isEmpty()) {
            sb.append("题库中没有找到足够匹配的题目，因此不提供答案。\n")
            sb.append("可以检查识别文字，手动修正后再搜索。\n\n")
        } else {
            scored.forEachIndexed { i, (item, s) ->
                val pct = (s * 100).toInt()
                sb.append(if (i == 0) "题库匹配（匹配度 $pct%）\n" else "相似题目（$pct%）\n")
                sb.append("题目：${item.question}\n答案：${item.answer.ifBlank { "题库未填写答案" }}\n\n")
            }
        }
        sb.append("—— 识别到的文字 ——\n").append(ocrText.trim())
        return sb.toString()
    }

    /**
     * 题库检索评分：先判断完整包含，再综合题干字符覆盖率、二元组 Dice 和编辑相似度。
     * OCR 可能同时识别到题号、选项或页面其他文字，因此也在 OCR 文本中寻找近似长度窗口。
     */
    private fun score(item: Item, ocrNorm: String): Double {
        val q = item.norm
        if (q.isEmpty() || ocrNorm.isEmpty()) return 0.0
        if (ocrNorm.contains(q)) return 1.0

        val qGrams = item.grams
        val wholeCoverage = qGrams.count { it in bigrams(ocrNorm) }.toDouble() /
            qGrams.size.coerceAtLeast(1)
        val wholeDice = dice(qGrams, bigrams(ocrNorm))
        var best = 0.65 * wholeCoverage + 0.35 * wholeDice

        // 比较题干长度附近的 OCR 子串，避免选项和额外文字稀释相似度。
        val minLen = (q.length * 0.75).toInt().coerceAtLeast(1)
        val maxLen = (q.length * 1.25).toInt().coerceAtMost(ocrNorm.length)
        if (maxLen >= minLen) {
            val lengthStep = (q.length / 10).coerceAtLeast(1)
            var len = minLen
            while (len <= maxLen) {
                val positionStep = (len / 4).coerceAtLeast(1)
                var pos = 0
                while (pos + len <= ocrNorm.length) {
                    val window = ocrNorm.substring(pos, pos + len)
                    val wg = bigrams(window)
                    val coverage = qGrams.count { it in wg }.toDouble() / qGrams.size.coerceAtLeast(1)
                    val d = dice(qGrams, wg)
                    // 编辑距离较贵，只对字形/词组覆盖率已经较高的窗口计算。
                    val edit = if (coverage >= 0.65) editSimilarity(q, window) else 0.0
                    val candidate = 0.50 * coverage + 0.30 * d + 0.20 * edit
                    if (candidate > best) best = candidate
                    pos += positionStep
                }
                len += lengthStep
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return 2.0 * intersection / (a.size + b.size)
    }

    /** Levenshtein 相似度；对较长文本先由窗口限制长度，避免全题 OCR 比较过慢。 */
    private fun editSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    // ---------- 小工具 ----------

    private fun showResult(s: String) {
        binding.tvResult.text = s
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val BANK_FILE = "questions.json"
        private const val MIN_SCORE = 0.68
    }
}
