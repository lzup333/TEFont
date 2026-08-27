package com.tefont

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
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

data class Placement(val page: Int, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * 与原版 HTML 生成器对齐：所有字符在同一坐标系下度量——
 * 小画布上以 alphabetic 基线绘制（基线 y = fontSize），
 * ink 边界扫描结果可直接换算为 BMFont 的 offset。
 */
private const val BASE_MARGIN_X = 2   // 抗锯齿出血（原版 sx）
private const val BASE_MARGIN_Y = 2   // 原版 sy
private const val CELL_PAD = 4        // 原版 padX/padY 的公共部分

class CharRender(
    val ch: Char,
    val cell: Bitmap,
    val minX: Int, val minY: Int,
    val trimW: Int, val trimH: Int,
    val xo: Int,   // xoffset = minX - marginX
    val yo: Int,   // yoffset = minY - marginY
    val adv: Int   // ceil(measureText)
)

class MainActivity : AppCompatActivity() {

    private lateinit var fontStatusText: TextView
    private lateinit var customSection: LinearLayout

    private val slotOverrides = mutableMapOf<Int, Pair<Int?, Float?>>()
    private val slotFontBytes = mutableMapOf<Int, ByteArray>()
    private val slotFontNames = mutableMapOf<Int, String>()
    private val slotSummaries = mutableMapOf<Int, TextView>()

    private lateinit var nameInput: TextInputEditText
    private lateinit var authorInput: TextInputEditText
    private lateinit var descInput: TextInputEditText

    private lateinit var genButton: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var progressLabel: TextView
    private lateinit var shareBar: LinearLayout
    private lateinit var outputCardArea: LinearLayout

    // 生成页向导步骤
    private val wizardScrolls = mutableListOf<ScrollView>()
    private var pageDots: List<TextView> = emptyList()
    private var btnPrevPage: MaterialButton? = null
    private var btnNextPage: MaterialButton? = null
    private var currentPage = 0

    // 设置页控件
    private lateinit var themeGroup: RadioGroup
    private lateinit var authorPrefEdit: TextInputEditText

    private var fontLoaded = false
    private var fontFileName = ""
    private var isGenerating = false
    private var lastZipFile: File? = null

    private var useBuiltinLibs = true
    private val asciiChars = StringBuilder()
    private val digitChars = StringBuilder()
    private val fullChars = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applySavedTheme()
        super.onCreate(savedInstanceState)
        buildUi()
        refreshCharSummary()
    }

    // ---------- 设置 ----------

    private fun applySavedTheme() {
        when (prefs.getString(KEY_THEME, THEME_SYSTEM)) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun defaultAuthor(): String = prefs.getString(KEY_AUTHOR, "").orEmpty()

    private fun saveSettings(): Boolean {
        val sel = themeGroup.checkedRadioButtonId
        val rb = themeGroup.findViewById<RadioButton>(sel)
        if (rb == null || rb.tag !is String) return false
        prefs.edit()
            .putString(KEY_THEME, rb.tag as String)
            .putString(KEY_AUTHOR, authorPrefEdit.text?.toString()?.trim().orEmpty())
            .apply()
        applySavedTheme()
        return true
    }

    // ============================================================
    // UI 构建 (Material 3 · Nord Light/Dark)
    // ============================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun colorOf(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }

        // ── 标题区 ──
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "TEFont"
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorOf(R.color.md_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        column.addView(TextView(this).apply {
            text = "TEFManager 字体包生成器"
            textSize = 14f
            setTextColor(colorOf(R.color.md_on_surface_variant))
            setPadding(dp(20), 0, dp(20), dp(16))
        })

        // ── 生成页容器（向导步骤页在此切换）──
        // ── 生成页 = 三步向导 ──
        fun newWizScroll(): ScrollView = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }.also { wizardScrolls.add(it) }
        fun wizColumn(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(8))
        }
        val genColumn = wizColumn()
        val slotsColumn = wizColumn()
        val outputColumn = wizColumn().apply { setPadding(dp(20), 0, dp(20), dp(8)) }
        newWizScroll().addView(genColumn)
        newWizScroll().addView(slotsColumn)
        newWizScroll().addView(outputColumn)

        // ── 设置页容器 ──
        val settingsPage = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val setColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(24))
        }
        settingsPage.addView(setColumn)

        // ---------- 生成页 ----------

        genColumn.addView(card { add ->
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

        genColumn.addView(card { add ->
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
            listOf(
                "ASCII 字符文件" to REQ_ASCII,
                "数字字符文件" to REQ_DIGITS,
                "大字库文本文件" to REQ_FULL
            ).forEach { (label, code) ->
                customSection.addView(bannerButton(label, filled = false) { chooseTextFile(code) })
            }
            box.addView(customSection)
            add.addView(box)
        })

        slotsColumn.addView(card { add ->
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

        outputColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("字体包信息"))
            nameInput = inputField(box, "包名称（留空 = 跟随字体文件名）")
            authorInput = inputField(box, "作者名（留空 = 使用设置中的默认值）")
            descInput = inputField(box, "描述 / 说明（可选）", maxLines = 3)
            add.addView(box)
        })

        outputColumn.addView(card(bgRes = R.color.md_primary_container) { add ->
            outputCardArea = innerColumn()
            progressIndicator = LinearProgressIndicator(this@MainActivity).apply {
                max = 100
                visibility = View.GONE
            }
            outputCardArea.addView(progressIndicator)

            progressLabel = TextView(this@MainActivity).apply {
                textSize = 12f
                gravity = Gravity.END
                visibility = View.GONE
                setTextColor(colorOf(R.color.md_on_primary_container))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            outputCardArea.addView(progressLabel)

            // 唯一的生成入口（进度条下方）
            genButton = bannerButton("🚀 开始生成（5 个槽位）", filled = true) { generatePack() }.apply {
                isEnabled = false
                setTextSize(17f)
                setTypeface(typeface, Typeface.BOLD)
            }
            outputCardArea.addView(genButton)

            shareBar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            shareBar.addView(bannerButton("📦 分享字体包 ZIP", filled = false) { lastZipFile?.let(::shareZip) })
            shareBar.addView(bannerButton("💾 导出字体包到本地…", filled = false) { exportZip() })
            outputCardArea.addView(shareBar)
            add.addView(outputCardArea)
        })

        // ---------- 设置页 ----------

        setColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("主题"))

            themeGroup = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            val currentTheme = prefs.getString(KEY_THEME, THEME_SYSTEM)
            listOf(
                Triple("跟随系统", THEME_SYSTEM, null),
                Triple("Nord 浅色", THEME_LIGHT, null),
                Triple("Nord 深色", THEME_DARK, null)
            ).forEach { (label, tag, _) ->
                themeGroup.addView(RadioButton(this@MainActivity).apply {
                    this.text = label
                    this.tag = tag
                    isChecked = currentTheme == tag
                    setTextColor(colorOf(R.color.md_on_surface))
                    textSize = 15f
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                })
            }
            box.addView(themeGroup)
            add.addView(box)
        })

        setColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("生成默认值"))
            authorPrefEdit = inputField(box, "默认作者名（生成时「作者名」留空则使用此值）")
            authorPrefEdit.setText(defaultAuthor())
            add.addView(box)
        })

        setColumn.addView(bannerButton("💾 保存设置", filled = true) {
            if (saveSettings()) {
                Toast.makeText(this, "✔ 设置已保存并生效", Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                Toast.makeText(this, "保存失败：请选择一个主题选项", Toast.LENGTH_SHORT).show()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ) })

        // ---------- 向导底部步骤栏 ----------
        val wizardFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        btnPrevPage = bannerButton("← 上一步", filled = false) { setPage(currentPage - 1) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6); topMargin = 0 }
        }
        btnNextPage = bannerButton("下一步 →", filled = true) { setPage(currentPage + 1) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { topMargin = 0 }
        }
        navRow.addView(btnPrevPage)
        navRow.addView(btnNextPage)

        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        pageDots = wizardScrolls.map {
            TextView(this).apply {
                text = "●"
                textSize = 11f
                setTextColor(colorOf(R.color.md_surface_variant))
                setPadding(dp(4), 0, dp(4), 0)
            }.also(dotsRow::addView)
        }
        wizardFooter.addView(navRow)
        wizardFooter.addView(dotsRow)

        // ---------- 底部导航 ----------

        val nav = BottomNavigationView(this).apply {
            menu.add(0, ID_TAB_GENERATE, 0, "生成").setIcon(android.R.drawable.ic_menu_manage)
            menu.add(0, ID_TAB_SETTINGS, 1, "设置").setIcon(android.R.drawable.ic_menu_preferences)
            itemIconTintList = android.content.res.ColorStateList.valueOf(colorOf(R.color.md_primary))
            itemTextColor = android.content.res.ColorStateList.valueOf(colorOf(R.color.md_primary))
            background = GradientDrawable().apply {
                setColor(colorOf(R.color.md_surface))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    ID_TAB_GENERATE -> {
                        wizardScrolls.forEachIndexed { i, sc -> sc.visibility = if (i == currentPage) View.VISIBLE else View.GONE }
                        wizardFooter.visibility = View.VISIBLE
                        settingsPage.visibility = View.GONE
                        true
                    }
                    else -> {
                        wizardScrolls.forEach { it.visibility = View.GONE }
                        wizardFooter.visibility = View.GONE
                        settingsPage.visibility = View.VISIBLE
                        true
                    }
                }
            }
        }

        wizardScrolls.forEach { column.addView(it) }
        column.addView(wizardFooter)
        column.addView(settingsPage)
        column.addView(nav)

        setContentView(column)
        setPage(0)
    }

    private fun setPage(index: Int) {
        currentPage = index.coerceIn(0, wizardScrolls.lastIndex)
        wizardScrolls.forEachIndexed { i, sc -> sc.visibility = if (i == currentPage) View.VISIBLE else View.GONE }
        btnPrevPage?.visibility = if (currentPage == 0) View.INVISIBLE else View.VISIBLE
        if (currentPage == wizardScrolls.lastIndex) {
            btnNextPage?.text = "🚀 开始生成"
            btnNextPage?.setOnClickListener { generatePack() }
        } else {
            btnNextPage?.text = "下一步 →"
            btnNextPage?.setOnClickListener { setPage(currentPage + 1) }
        }
        pageDots.forEachIndexed { i, dot ->
            dot.setTextColor(colorOf(if (i == currentPage) R.color.md_primary else R.color.md_surface_variant))
        }
        wizardScrolls.getOrNull(currentPage)?.fullScroll(View.FOCUS_UP)
    }

    // ---------- M3 构件工厂 ----------

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

    private inline fun innerColumn(configure: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            configure()
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

    /** SAF 的 lastPathSegment 形如 "primary:Download/a.ttf"，提取纯文件名 */
    private fun displayName(uri: Uri): String =
        (uri.lastPathSegment ?: "unknown")
            .substringAfterLast(':')
            .substringAfterLast('/')

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
                        loadFont(buf.toByteArray(), displayName(data.data!!))
                    }
                    REQ_ASCII -> { asciiChars.clear().append(stream.bufferedReader().readText()); refreshCharSummary() }
                    REQ_DIGITS -> { digitChars.clear().append(stream.bufferedReader().readText()); refreshCharSummary() }
                    REQ_FULL -> { fullChars.clear().append(stream.bufferedReader().readText()); refreshCharSummary() }
                    in REQ_SLOT_FONT_BASE..REQ_SLOT_FONT_BASE + SLOTS.size - 1 -> {
                        val buf = ByteArrayOutputStream()
                        stream.copyTo(buf)
                        val idx = requestCode - REQ_SLOT_FONT_BASE
                        slotFontBytes[idx] = buf.toByteArray()
                        slotFontNames[idx] = displayName(data.data!!)
                        Toast.makeText(this,
                            "✔ 槽位 ${SLOTS[idx].key} 将使用独立字体 ${slotFontNames[idx]}",
                            Toast.LENGTH_SHORT).show()
                        refreshCharSummary()
                    }
                    REQ_EXPORT -> {
                        if (copyZipTo(data.data!!))
                            Toast.makeText(this, "✔ 已导出到所选位置", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                    }
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
        val fontTag = if (idx in slotFontNames) " · 字体:${slotFontNames[idx]}" else ""
        slotSummaries[idx]?.text = "$src ｜ ${fs}px · $sp$mark$fontTag"
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

        var dialogRef: androidx.appcompat.app.AlertDialog? = null

        box.addView(TextView(this).apply {
            text = if (idx in slotFontNames) "当前独立字体：${slotFontNames[idx]}"
            else "当前：使用全局所选字体"
            textSize = 12f
            setTextColor(colorOf(if (idx in slotFontNames) R.color.md_primary else R.color.md_on_surface_variant))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        box.addView(bannerButton("为本槽位选择独立字体…", filled = false) {
            dialogRef?.dismiss()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_SLOT_FONT_BASE + idx)
        })
        box.addView(bannerButton("恢复使用全局字体", filled = false) {
            slotFontBytes.remove(idx)
            slotFontNames.remove(idx)
            refreshCharSummary()
            Toast.makeText(this, "槽位 ${s.key} 已恢复全局字体", Toast.LENGTH_SHORT).show()
        })

        dialogRef = androidx.appcompat.app.AlertDialog.Builder(this)
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

    /** 原版货架式装箱 packShelves：按高度降序，先填已有 shelf 再开新 shelf/page */
    private class PackedPlacement(val page: Int, val x: Int, val y: Int, val w: Int, val h: Int)
    private class Shelf(var x: Int, val y: Int, val h: Int)
    private class PackPage(val shelves: MutableList<Shelf>, var maxY: Int)

    private class GeneratedSlot(
        val key: String,
        val fntTmp: File,
        val pageFiles: List<File>,
        val pagesRaw: List<Bitmap>
    )

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

    /** 对齐原版 measureChar：小画布基线=fontSize 绘制后 alpha 裁剪 */
    private fun renderChar(ch: Char, paint: Paint, fs: Int): CharRender? {
        val adv = paint.measureText(ch.toString())
        if (adv <= 0f) return null
        val w = ceil(adv.toDouble()).toInt().coerceAtLeast(1)
        val h = ceil(fs * 1.4f).toInt()

        val cw = w + CELL_PAD
        val chh = h + CELL_PAD

        val cell = Bitmap.createBitmap(cw, chh, Bitmap.Config.ARGB_8888)
        Canvas(cell).drawText(ch.toString(), BASE_MARGIN_X.toFloat(), (fs + BASE_MARGIN_Y).toFloat(), paint)

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
            // 无墨迹但有进距（如空格）→ 1x1 空字形，只贡献 xadvance
            cell.recycle()
            val placeholder = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return CharRender(ch, placeholder, 0, 0, 1, 1, 0, 0, ceil(adv.toDouble()).toInt())
        }

        return CharRender(
            ch, cell,
            minX, minY,
            maxX - minX + 1, maxY - minY + 1,
            minX - BASE_MARGIN_X,
            minY - BASE_MARGIN_Y,
            ceil(adv.toDouble()).toInt()
        )
    }

    @Volatile
    private var cachedFontBytes: ByteArray? = null
    @Volatile
    private var cachedFontPath: String? = null
    private val materializedSlots = mutableMapOf<Int, String>()

    private fun makeFontFile(): String {
        val bytes = cachedFontBytes ?: error("字体未缓存")
        cachedFontPath?.let { p -> if (File(p).length() == bytes.size.toLong()) return p }
        return materializeFont(bytes, "_tmp_font.ttf").also { cachedFontPath = it }
    }

    private fun materializeFont(bytes: ByteArray, fileName: String): String {
        val f = File(cacheDir, fileName)
        FileOutputStream(f).use { it.write(bytes) }
        return f.absolutePath
    }

    private fun doGenerate(): File {
        val t0 = System.currentTimeMillis()
        val pkgName = nameInput.text?.toString()?.trim().ifNullOrEmpty {
            fontFileName.substringBeforeLast('.').ifEmpty { "font" }
        }
        val author = authorInput.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultAuthor()
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

            // 字体：槽位独立字体优先，否则全局所选字体
            val fontPath = if (n in slotFontBytes) materializeFont(slotFontBytes[n]!!, "_tmp_slot$n.ttf")
            else makeFontFile()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.createFromFile(fontPath)
                textSize = fs.toFloat()
                color = Color.WHITE
            }

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

            val items = renders.map { it.trimW to it.trimH }
            val (placements, numPages) = packShelves(items, pageW, pageH, padding)

            val pagesRaw = Array(numPages) { Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888) }
            renders.forEachIndexed { i, r ->
                val pl = placements[i]
                Canvas(pagesRaw[pl.page]).drawBitmap(
                    r.cell,
                    Rect(r.minX, r.minY, r.minX + r.trimW, r.minY + r.trimH),
                    RectF(pl.x.toFloat(), pl.y.toFloat(), (pl.x + pl.w).toFloat(), (pl.y + pl.h).toFloat()),
                    null
                )
                r.cell.recycle()
            }

            val fntContent = buildFNTAlignedWithOriginal(
                outName = slot.key,
                fontSize = fs,
                spacingExtra = spacing,
                padding = padding,
                pageW = pageW,
                pageH = pageH,
                numPages = numPages,
                chars = renders.map { it.ch },
                placements = placements,
                xOffsets = renders.map { it.xo },
                yOffsets = renders.map { it.yo },
                advances = renders.map { it.adv }
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
        materializedSlots.values.forEach { File(it).delete() }
        materializedSlots.clear()

        val dur = "%.2f".format((System.currentTimeMillis() - t0) / 1000.0)
        handler.post { progressLabel.text = "完成 · $dur s · ${results.size} 槽位" }
        return zipFile
    }

    private fun onGenerated(zipFile: File) {
        isGenerating = false
        lastZipFile = zipFile
        shareBar.visibility = View.VISIBLE
        progressIndicator.setProgressCompat(100, true)
        genButton.isEnabled = true
        genButton.text = "✔ 已完成，可分享、导出或再次生成"
        Toast.makeText(this, "✔ 字体包已生成", Toast.LENGTH_SHORT).show()
    }

    // ---------- 输出 ----------

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

    private fun exportZip() {
        val zip = lastZipFile ?: run {
            Toast.makeText(this, "请先生成字体包", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, zip.name)
        }, REQ_EXPORT)
    }

    private fun copyZipTo(uri: Uri): Boolean = try {
        contentResolver.openOutputStream(uri)?.use { out ->
            lastZipFile!!.inputStream().use { it.copyTo(out) }
        } != null
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    // ---------- 数据格式 ----------

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

    private fun buildFNTAlignedWithOriginal(
        outName: String,
        fontSize: Int,
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

    // ---------- 工具 ----------

    private fun String?.ifNullOrEmpty(f: () -> String): String = if (isNullOrEmpty()) f() else this

    companion object {
        private const val PREFS_NAME = "tefont"
        private const val KEY_THEME = "theme"
        private const val KEY_AUTHOR = "author"
        private const val THEME_SYSTEM = "system"
        private const val THEME_LIGHT = "light"
        private const val THEME_DARK = "dark"

        private const val PACK_INFO_ENTRY = "pack_info.json"
        private const val REQ_FONT = 10
        private const val REQ_ASCII = 11
        private const val REQ_DIGITS = 12
        private const val REQ_FULL = 13
        private const val REQ_SLOT_FONT_BASE = 20
        private const val REQ_EXPORT = 30
        private const val ID_TAB_GENERATE = 1
        private const val ID_TAB_SETTINGS = 2
    }
}