package com.tefont

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 槽位: 文件夹名 / 显示名 / 推荐字号 / 推荐字间距 */
data class Slot(val key: String, val label: String, val size: Int, val spacing: Float)

private val SLOTS = listOf(
    Slot("death_text", "标题字体", 46, 0.5f),
    Slot("mouse_text", "内容字体", 22, 0.7f),
    Slot("combat_text", "伤害字体", 18, 0.8f),
    Slot("combat_crit", "暴击字体", 20, 0.8f),
    Slot("item_stack", "数量字体", 14, 0.8f)
)

private fun slotRes(pos: Int): Int = when (pos) {
    0, 1 -> R.raw.builtin_chars_full
    2, 3 -> R.raw.builtin_chars_digits
    else -> R.raw.builtin_chars_ascii
}

/**
 * 与原版 HTML 生成器对齐的字符信息。
 * 关键点：所有字符在同一坐标系下度量——小画布上以 alphabetic 基线绘制，
 * 基线 y 固定为 fontSize，于是 ink 的边界扫描结果可直接换算成 BMFont 的 offset。
 */
private const val BASE_MARGIN_X = 2   // 原版 sx：预留抗锯齿出血
private const val BASE_MARGIN_Y = 2   // 原版 sy
private const val CELL_PAD = 4        // 原版 padX/padY 中不含描边投影的部分

data class CharRender(
    val ch: Char,
    val cell: Bitmap,      // cw × ch 的小画布（用完回收）
    val minX: Int, val minY: Int,
    val trimW: Int, val trimH: Int,
    val xo: Int,           // = minX - marginX，写入 xoffset
    val yo: Int,           // = minY - marginY，写入 yoffset
    val adv: Int           // = ceil(measureText)，写入 xadvance 的基础
)

class MainActivity : AppCompatActivity() {

    private lateinit var fontStatusText: TextView
    private lateinit var customSection: LinearLayout

    private val slotOverrides = mutableMapOf<Int, Pair<Int?, Float?>>()
    private val slotSummaries = mutableMapOf<Int, TextView>()

    private lateinit var nameInput: TextInputEditText
    private lateinit var authorInput: TextInputEditText
    private lateinit var descInput: TextInputEditText

    private lateinit var genButton: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var progressLabel: TextView
    private lateinit var shareBar: LinearLayout
    private lateinit var shareBtn: MaterialButton

    private var fontLoaded = false
    private var fontFileName = ""
    private var isGenerating = false
    private var lastZipFile: File? = null

    private var useBuiltinLibs = true
    private val asciiChars = StringBuilder()
    private val digitChars = StringBuilder()
    private val fullChars = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshCharSummary()
    }

    // ============================================================
    // UI 构建 (Material 3 · Nord Light)
    // ============================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun colorOf(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun buildUi() {
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        column.addView(TextView(this).apply {
            text = "TEFont"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorOf(R.color.md_primary))
        })
        column.addView(TextView(this).apply {
            text = "泰拉瑞亚位图字体包生成器"
            textSize = 14f
            setTextColor(colorOf(R.color.md_on_surface_variant))
            setPadding(0, dp(2), 0, dp(24))
        })

        column.addView(card { add ->
            add.addView(innerColumn {
                fontStatusText = TextView(this@MainActivity).apply {
                    text = "尚未选择字体文件"
                    textSize = 15f
                    setTextColor(colorOf(R.color.md_on_surface_variant))
                }
                addView(fontStatusText)
                addView(bannerButton("选择字体文件 (.ttf / .otf)", filled = true) { chooseFont() })
            })
        })

        column.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("字库"))

            val toggle = MaterialButtonToggleGroup(this@MainActivity).apply {
                isSingleSelection = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
            val builtinBtn = segmentedButton("内置字库")
            val fileBtn = segmentedButton("自定义文件")
            toggle.setSelectionRequired(true)
            toggle.addView(builtinBtn)
            toggle.addView(fileBtn)
            toggle.check(builtinBtn.id)
            toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    useBuiltinLibs = (checkedId == builtinBtn.id)
                    customSection.visibility = if (useBuiltinLibs) View.GONE else View.VISIBLE
                    refreshCharSummary()
                }
            }
            box.addView(toggle)

            customSection = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            listOf("ASCII 字符文件" to REQ_ASCII, "数字字符文件" to REQ_DIGITS, "大字库文本文件" to REQ_FULL).forEach { (label, code) ->
                customSection.addView(bannerButton(label, filled = false) { chooseTextFile(code) })
            }
            box.addView(customSection)
            add.addView(box)
        })

        column.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("生成配置"))
            SLOTS.forEachIndexed { i, s ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundBg(colorOf(R.color.md_secondary_container), dp(14))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    setOnClickListener { showSlotEditor(i) }
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = s.key
                    textSize = 15f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(colorOf(R.color.md_on_primary_container))
                })
                val summary = TextView(this@MainActivity).apply {
                    textSize = 13f
                    setTextColor(colorOf(R.color.md_on_secondary_container))
                }
                row.addView(summary, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(10) })
                row.addView(TextView(this@MainActivity).apply {
                    text = "✎"
                    textSize = 16f
                    setTextColor(colorOf(R.color.md_on_secondary_container))
                })
                box.addView(row)
                slotSummaries[i] = summary
            }
            add.addView(box)
        })

        column.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("字体包信息"))
            nameInput = inputField(box, "包名称（留空 = 跟随字体文件名）")
            authorInput = inputField(box, "作者名（可选）")
            descInput = inputField(box, "描述 / 说明（可选）", maxLines = 3)
            add.addView(box)
        })

        column.addView(card(bgRes = R.color.md_primary_container) { add ->
            val box = innerColumn()
            genButton = bannerButton("开始生成（5 个槽位）", filled = true) { generatePack() }.apply {
                isEnabled = false
                setTextSize(17f)
                setTypeface(typeface, Typeface.BOLD)
            }
            box.addView(genButton)

            progressIndicator = LinearProgressIndicator(this@MainActivity).apply {
                max = 100
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
            box.addView(progressIndicator)

            progressLabel = TextView(this@MainActivity).apply {
                textSize = 12f
                gravity = Gravity.END
                visibility = View.GONE
                setTextColor(colorOf(R.color.md_on_primary_container))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            box.addView(progressLabel)

            shareBar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            shareBtn = bannerButton("📦 分享字体包 ZIP", filled = false) { lastZipFile?.let(::shareZip) }
            shareBar.addView(shareBtn)
            box.addView(shareBar)
            add.addView(box)
        })

        scroll.addView(column)
        setContentView(scroll)
    }

    // ---------- M3 构件工厂 ----------

    private inline fun innerColumn(configure: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            configure()
        }

    private fun card(
        bgRes: Int = R.color.md_surface,
        content: (MaterialCardView) -> Unit
    ): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(24).toFloat()
        strokeWidth = 0
        cardElevation = 0f
        setCardBackgroundColor(colorOf(bgRes))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
        content(this)
    }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorOf(R.color.md_on_surface))
        }

    private fun bannerButton(text: String, filled: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null,
            if (filled) com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(if (filled) colorOf(R.color.md_on_primary) else colorOf(R.color.md_primary))
            strokeWidth = if (filled) 0 else dp(1)
            cornerRadius = dp(100)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun segmentedButton(text: String): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            this.text = text
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun roundBg(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private fun inputField(parent: LinearLayout, hint: String, maxLines: Int = 1): TextInputEditText {
        val layout = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val edit = TextInputEditText(this).apply {
            this.maxLines = maxLines
            setSingleLine(maxLines == 1)
        }
        layout.addView(edit)
        parent.addView(layout)
        return edit
    }

    // ============================================================
    // 文件选择
    // ============================================================

    private fun chooseFont() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_FONT)
    }

    private fun chooseTextFile(code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }, code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        try {
            contentResolver.openInputStream(data.data!!)?.use { stream ->
                when (requestCode) {
                    REQ_FONT -> {
                        val buf = ByteArrayOutputStream()
                        stream.copyTo(buf)
                        loadFont(buf.toByteArray(), data.data!!.lastPathSegment ?: "unknown")
                    }
                    REQ_ASCII -> { asciiChars.clear().append(stream.bufferedReader().readText()); refreshCharSummary() }
                    REQ_DIGITS -> { digitChars.clear().append(stream.bufferedReader().readText()); refreshCharSummary() }
                    REQ_FULL -> { fullChars.clear().append(stream.bufferedReader().readText()); refreshCharSummary() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "文件读取失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadFont(bytes: ByteArray, fileName: String?) {
        fontLoaded = true
        fontFileName = fileName ?: ""
        cachedFontBytes = bytes
        fontStatusText.text = "✔ $fontFileName · ${bytes.size / 1024} KB"
        fontStatusText.setTextColor(colorOf(R.color.md_primary))
        if (nameInput.text.isNullOrBlank())
            nameInput.setText(fontFileName.substringBeforeLast('.'))
        genButton.isEnabled = !isGenerating
    }

    // ============================================================
    // 字库
    // ============================================================

    private fun resText(resId: Int): String =
        resources.openRawResource(resId).bufferedReader().readText()

    private fun cleanChars(raw: String): List<Char> =
        raw.toList().distinct().filter { it != '\uFEFF' }

    private fun customReady(): Boolean =
        asciiChars.isNotEmpty() && digitChars.isNotEmpty() && fullChars.isNotEmpty()

    private fun customCombined(): List<Char> =
        cleanChars(asciiChars.toString() + digitChars.toString() + fullChars.toString())

    private fun refreshCharSummary() {
        val cc = if (!useBuiltinLibs && customReady()) customCombined().size else -1
        SLOTS.indices.forEach { updateSlotRow(it, cc) }
    }

    private fun builtinName(resId: Int): String = when (resId) {
        R.raw.builtin_chars_full -> "14000字库"
        R.raw.builtin_chars_digits -> "数字"
        else -> "ASCII"
    }

    // ---------- 槽位编辑 ----------

    private fun slotEffective(idx: Int): Pair<Int, Float> {
        val s = SLOTS[idx]
        val o = slotOverrides[idx]
        return (o?.first ?: s.size) to (o?.second ?: s.spacing)
    }

    private fun updateSlotRow(idx: Int, customCharCount: Int) {
        if (!this::customSection.isInitialized) return
        val s = SLOTS[idx]
        val (fs, sp) = slotEffective(idx)
        val src = if (!useBuiltinLibs && customReady()) "自定义($customCharCount)" else builtinName(slotRes(idx))
        val mark = if (idx in slotOverrides) " ✎" else ""
        slotSummaries[idx]?.text = "$src ｜ ${fs}px · $sp$mark"
    }

    private fun showSlotEditor(idx: Int) {
        val s = SLOTS[idx]
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val sizeEdit = inputField(box, "字号（留空 = 推荐 ${s.size}）").apply {
            slotOverrides[idx]?.first?.let { setText(it.toString()) }
        }
        val spaceEdit = inputField(box, "字间距（留空 = 推荐 ${s.spacing}）").apply {
            slotOverrides[idx]?.second?.let { setText(it.toString()) }
        }
        box.addView(bannerButton("恢复该槽位的推荐值", filled = false) {
            sizeEdit.setText("")
            spaceEdit.setText("")
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("${s.key} · ${s.label}")
            .setView(box)
            .setPositiveButton("保存") { d, _ ->
                val fs = sizeEdit.text?.toString()?.toIntOrNull()?.takeIf { it in 6..200 }
                val sp = spaceEdit.text?.toString()?.toFloatOrNull()
                if (fs == null && sp == null) slotOverrides.remove(idx)
                else slotOverrides[idx] = fs to sp
                val cc = if (!useBuiltinLibs && customReady()) customCombined().size else -1
                updateSlotRow(idx, cc)
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================================================
    // 生成 —— 算法与原版 HTML 对齐
    // ============================================================

    private fun generatePack() {
        if (isGenerating || !fontLoaded) return
        isGenerating = true
        genButton.isEnabled = false
        progressIndicator.visibility = View.VISIBLE
        progressIndicator.setProgressCompat(0, true)
        progressLabel.visibility = View.VISIBLE
        progressLabel.text = "准备中..."
        shareBar.visibility = View.GONE

        Thread {
            runCatching { doGenerate() }
                .onFailure { e ->
                    e.printStackTrace()
                    handler.post { Toast.makeText(this@MainActivity, "生成失败：${e.message}", Toast.LENGTH_LONG).show() }
                }
                .onSuccess { zip -> handler.post { onGenerated(zip) } }
        }.start()
    }

    /**
     * 原版货架式装箱（packShelves）：按高度降序，先填已有 shelf 再开新 shelf/page。
     */
    private class PackedPlacement(val page: Int, val x: Int, val y: Int, val w: Int, val h: Int)

    private class Shelf(var x: Int, val y: Int, val h: Int)

    private class PackPage(val shelves: MutableList<Shelf>, var maxY: Int)

    private fun packShelves(items: List<Pair<Int, Int>>, pageW: Int, pageH: Int, pad: Int): Pair<List<PackedPlacement>, Int> {
        val sorted = items.withIndex().sortedByDescending { it.value.second }
        val placements = arrayOfNulls<PackedPlacement>(items.size)
        val pages = mutableListOf<PackPage>()

        for ((idx, item) in sorted) {
            val (w, h) = item
            var placed = false
            for (p in pages.indices) {
                if (placed) break
                val page = pages[p]
                for (shelf in page.shelves) {
                    if (shelf.h >= h && shelf.x + w + pad <= pageW) {
                        placements[idx] = PackedPlacement(p, shelf.x, shelf.y, w, h)
                        shelf.x += w + pad
                        placed = true
                        break
                    }
                }
                if (!placed) {
                    val nextY = page.maxY + pad
                    if (nextY + h + pad <= pageH) {
                        placements[idx] = PackedPlacement(p, pad, nextY, w, h)
                        page.shelves.add(Shelf(pad + w + pad, nextY, h))
                        page.maxY = nextY + h
                        placed = true
                    }
                }
            }
            if (!placed) {
                pages.add(PackPage(mutableListOf(Shelf(pad + w + pad, pad, h)), pad + h))
                placements[idx] = PackedPlacement(pages.size - 1, pad, pad, w, h)
            }
        }
        return placements.map { it!! } to pages.size
    }

    /** 对齐原版 measureChar + 单字符渲染：小画布上以基线=fontSize 绘制后做 alpha 裁剪。 */
    private fun renderChar(ch: Char, paint: Paint, fs: Int): CharRender? {
        val adv = paint.measureText(ch.toString())
        if (adv <= 0f) return null
        val w = ceil(adv.toDouble()).toInt().coerceAtLeast(1)
        val h = ceil(fs * 1.4f).toInt()

        val cw = w + CELL_PAD
        val chh = h + CELL_PAD

        val cell = Bitmap.createBitmap(cw, chh, Bitmap.Config.ARGB_8888)
        val cx = Canvas(cell)
        cx.drawText(ch.toString(), BASE_MARGIN_X.toFloat(), (fs + BASE_MARGIN_Y).toFloat(), paint)

        val pixels = IntArray(cw * chh)
        cell.getPixels(pixels, 0, cw, 0, 0, cw, chh)

        var minX = cw; var minY = chh; var maxX = -1; var maxY = -1
        for (y in 0 until chh) {
            val rowBase = y * cw
            for (x in 0 until cw) {
                if ((pixels[rowBase + x] ushr 24) > 10) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) {
            // 原版行为：无墨迹但 measureText 有进距（如空格）→ 记录为 1x1 空字形，
            // 只贡献 xadvance，不影响贴图
            cell.recycle()
            val placeholder = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return CharRender(ch, placeholder, 0, 0, 1, 1, 0, 0, ceil(adv.toDouble()).toInt())
        }

        val tw = maxX - minX + 1
        val th = maxY - minY + 1
        return CharRender(
            ch, cell,
            minX, minY, tw, th,
            minX - BASE_MARGIN_X,
            minY - BASE_MARGIN_Y,
            ceil(adv.toDouble()).toInt()
        )
    }

    private class GeneratedSlot(
        val key: String,
        val fntTmp: File,
        val pageFiles: List<File>,
        val pagesRaw: List<Bitmap>
    )

    private fun doGenerate(): File {
        val t0 = System.currentTimeMillis()
        val pkgName = nameInput.text?.toString()?.trim().ifNullOrEmpty {
            fontFileName.substringBeforeLast('.').ifEmpty { "font" }
        }
        val author = authorInput.text?.toString()?.trim() ?: ""
        val desc = descInput.text?.toString()?.trim() ?: ""
        val custom = !useBuiltinLibs && customReady()
        val customChars = if (custom) customCombined() else emptyList()

        val results = mutableListOf<GeneratedSlot>()
        val nTargets = SLOTS.size
        val pageW = 1024
        val pageH = 1024
        val padding = 1

        SLOTS.forEachIndexed { n, slot ->
            val (fs, spacing) = slotEffective(n)
            val chars = if (custom) customChars else cleanChars(resText(slotRes(n)))
            if (chars.isEmpty()) return@forEachIndexed

            handler.post { progressLabel.text = "${slot.key} (${n + 1}/$nTargets)…" }

            // 字库来源
            val loadFont = Typeface.createFromFile(makeFontFile())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = loadFont
                textSize = fs.toFloat()
                color = Color.WHITE
            }

            // ① 逐字符渲染到小画布并裁剪（对齐原版 measureChar / 渲染阶段）
            val renders = mutableListOf<CharRender>()
            chars.forEachIndexed { index, ch ->
                renderChar(ch, paint, fs)?.let { renders.add(it) }
                if ((index + 1) % 800 == 0 || index == chars.lastIndex) {
                    val p = ((n * 90f / nTargets) + index * (50f / nTargets) / chars.size).toInt().coerceIn(0, 95)
                    handler.post {
                        progressIndicator.setProgressCompat(p, true)
                        progressLabel.text = "${slot.key} 测量 $p%"
                    }
                }
            }
            if (renders.isEmpty()) return@forEachIndexed

            // ② 货架装箱
            val items = renders.map { it.trimW to it.trimH }
            val (placements, numPages) = packShelves(items, pageW, pageH, padding)

            // ③ 从小画布拷贝裁剪区域到大页（imageSmoothing off，等像素搬运）
            val pagesRaw = Array(numPages) { Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888) }
            renders.forEachIndexed { i, r ->
                val pl = placements[i]
                val pc = Canvas(pagesRaw[pl.page])
                pc.drawBitmap(
                    r.cell,
                    Rect(r.minX, r.minY, r.minX + r.trimW, r.minY + r.trimH),
                    RectF(pl.x.toFloat(), pl.y.toFloat(), (pl.x + pl.w).toFloat(), (pl.y + pl.h).toFloat()),
                    null
                )
                r.cell.recycle()
            }

            // ④ 写出文件
            val fntContent = buildFNTAlignedWithOriginal(
                outName = slot.key,
                fontSize = fs,
                spacing = spacing,
                padding = padding,
                pageW = pageW,
                pageH = pageH,
                numPages = numPages,
                chars = renders.map { it.ch },
                placements = placements,
                xOffsets = renders.map { it.xo },
                yOffsets = renders.map { it.yo },
                advances = renders.map { it.adv },
                spacingExtra = spacing
            )

            val fntTmp = File(cacheDir, "_tmp_${slot.key}.fnt").apply { writeText(fntContent) }
            val pageFiles = pagesRaw.mapIndexed { i, bmp ->
                File(cacheDir, "_tmp_${slot.key}_$i.png").also {
                    FileOutputStream(it).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                }
            }
            results.add(GeneratedSlot(slot.key, fntTmp, pageFiles, pagesRaw.toList()))

            handler.post {
                progressIndicator.setProgressCompat(((n + 1) * 90f / nTargets).toInt().coerceAtMost(95), true)
            }
        }

        if (results.isEmpty()) throw IllegalStateException("没有可生成的有效字符")

        // 打包
        handler.post { progressLabel.text = "打包中..." }
        val zipFile = File(cacheDir, "FontPack_$pkgName.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            SLOTS.forEach {
                zos.putNextEntry(ZipEntry("${it.key}/"))
                zos.closeEntry()
            }

            zos.putNextEntry(ZipEntry(PACK_INFO_ENTRY))
            zos.write(buildPackInfoJson(pkgName, author, desc).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            results.forEach { r ->
                zos.putNextEntry(ZipEntry("${r.key}/${r.key}.fnt"))
                FileInputStream(r.fntTmp).copyTo(zos)
                zos.closeEntry()

                r.pageFiles.forEachIndexed { i, f ->
                    // 原版规则：单页 → outName.png；多页 → outName_0.png 起（无补零）
                    val name = if (r.pageFiles.size == 1) "${r.key}.png" else "${r.key}_$i.png"
                    zos.putNextEntry(ZipEntry("${r.key}/$name"))
                    FileInputStream(f).copyTo(zos)
                    zos.closeEntry()
                }
            }
        }

        results.forEach { r ->
            r.pagesRaw.forEach(Bitmap::recycle)
            r.fntTmp.delete()
            r.pageFiles.forEach(File::delete)
        }

        val dur = "%.2f".format((System.currentTimeMillis() - t0) / 1000.0)
        handler.post { progressLabel.text = "完成 · $dur s · ${results.size} 槽位" }
        return zipFile
    }

    /** 为后台线程提供已加载的字体文件：从选择时缓存的字节重建临时 ttf */
    @Volatile
    private var cachedFontBytes: ByteArray? = null
    @Volatile
    private var cachedFontPath: String? = null

    private fun makeFontFile(): String {
        val bytes = cachedFontBytes ?: error("字体未缓存")
        cachedFontPath?.let { p -> if (File(p).length() == bytes.size.toLong()) return p }
        val f = File(cacheDir, "_tmp_font.ttf")
        FileOutputStream(f).use { it.write(bytes) }
        cachedFontPath = f.absolutePath
        return f.absolutePath
    }

    private fun onGenerated(zipFile: File) {
        isGenerating = false
        lastZipFile = zipFile
        shareBar.visibility = View.VISIBLE
        progressIndicator.setProgressCompat(100, true)
        genButton.isEnabled = true
        genButton.text = "✔ 已完成，可分享或再次生成"
        Toast.makeText(this, "✔ 字体包已生成", Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // 数据格式
    // ============================================================

    private fun String?.ifNullOrEmpty(f: () -> String): String = if (isNullOrEmpty()) f() else this

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""

    private fun buildPackInfoJson(name: String, author: String, description: String): String = buildString {
        append("{\n")
        append("  \"type\": \"FontPack\",\n")
        append("  \"name\": ").append(jsonStr(name)).append(",\n")
        append("  \"author\": ").append(jsonStr(author.ifEmpty { "unknown" })).append(",\n")
        append("  \"description\": ").append(jsonStr(description)).append(",\n")
        append("  \"version\": \"1.1.0\"\n")
        append("}")
    }

    /**
     * 与原版 HTML 的 fnt 输出完全一致：
     *  - lineHeight = round(fontSize*1.5)，base = floor(fontSize*0.8)
     *  - xoffset/x/y 等：贴图内坐标 + 记录 ink 相对基线的 xo/yo
     *  - xadvance = measureText 宽度 + spacing（关键：不使用裁剪宽度）
     *  - 贴图引用名：单页 outName.png；多页 outName_i.png（无补零）
     */
    private fun buildFNTAlignedWithOriginal(
        outName: String,
        fontSize: Int,
        spacing: Float,
        padding: Int,
        pageW: Int,
        pageH: Int,
        numPages: Int,
        chars: List<Char>,
        placements: List<PackedPlacement>,
        xOffsets: List<Int>,
        yOffsets: List<Int>,
        advances: List<Int>,
        spacingExtra: Float
    ): String = buildString {
        val lineHeight = (fontSize * 1.5f).roundToInt()
        val base = (fontSize * 0.8f).toInt()
        append("<?xml version=\"1.0\"?>\n<font>\n")
        append("  <info face=\"$outName\" size=\"$fontSize\" bold=\"0\" italic=\"0\" charset=\"\" unicode=\"1\" stretchH=\"100\" smooth=\"1\" aa=\"1\" padding=\"$padding,$padding,$padding,$padding\" spacing=\"$spacingExtra,$spacingExtra\" outline=\"0\"/>\n")
        append("  <common lineHeight=\"$lineHeight\" base=\"$base\" scaleW=\"$pageW\" scaleH=\"$pageH\" pages=\"$numPages\" packed=\"0\" alphaChnl=\"0\" redChnl=\"4\" greenChnl=\"4\" blueChnl=\"4\"/>\n")
        append("  <pages>\n")
        (0 until numPages).forEach { p ->
            val fname = if (numPages == 1) "$outName.png" else "${outName}_$p.png"
            append("    <page id=\"$p\" file=\"$fname\"/>\n")
        }
        append("  </pages>\n")
        append("  <chars count=\"${chars.size}\">\n")
        chars.forEachIndexed { i, ch ->
            val pl = placements[i]
            val adv = advances[i] + Math.round(spacingExtra)
            append("    <char id=\"${ch.code}\" x=\"${pl.x}\" y=\"${pl.y}\" width=\"${pl.w}\" height=\"${pl.h}\" xoffset=\"${xOffsets[i]}\" yoffset=\"${yOffsets[i]}\" xadvance=\"$adv\" page=\"${pl.page}\" chnl=\"15\"/>\n")
        }
        append("  </chars>\n</font>")
    }

    private fun shareZip(zipFile: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", zipFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享字体包"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "分享失败：${zipFile.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val PACK_INFO_ENTRY = "pack_info.json"
        private const val REQ_FONT = 10
        private const val REQ_ASCII = 11
        private const val REQ_DIGITS = 12
        private const val REQ_FULL = 13
    }
}
