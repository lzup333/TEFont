package com.tefont

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
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
    Slot("death_text", "标题字体", 46, 1f),
    Slot("mouse_text", "内容字体", 22, 1f),
    Slot("combat_text", "伤害字体", 18, 1f),
    Slot("combat_crit", "暴击字体", 20, 1f),
    Slot("item_stack", "数量字体", 14, 1f)
)

private fun slotRes(pos: Int): Int = when (pos) {
    0, 1 -> R.raw.builtin_chars_full
    2, 3 -> R.raw.builtin_chars_digits
    else -> R.raw.builtin_chars_ascii
}

// ---------- 配色方案（每个方案提供 浅色 / 深色 两套） ----------

private fun sch(
    light: Map<String, String>,
    dark: Map<String, String>
): Pair<Map<String, Int>, Map<String, Int>> =
    light.mapValues { Color.parseColor(it.value) } to dark.mapValues { Color.parseColor(it.value) }

private val SCHEME_LABELS = listOf(
    "nord" to "Nord",
    "catppuccin" to "Catppuccin",
    "solarized" to "Solarized",
    "gruvbox" to "Gruvbox",
    "dracula" to "Dracula",
    "rosepine" to "Rosé Pine"
)

private val SCHEMES: Map<String, Pair<Map<String, Int>, Map<String, Int>>> = mapOf(
    "nord" to sch(
        mapOf(
            "md_background" to "#ECEFF4", "md_surface" to "#E5E9F0",
            "md_surface_variant" to "#D8DEE9", "md_on_surface" to "#2E3440",
            "md_on_surface_variant" to "#4C566A", "md_primary" to "#5E81AC",
            "md_on_primary" to "#FFFFFF", "md_primary_container" to "#DCE6F1",
            "md_on_primary_container" to "#2E3440", "md_secondary_container" to "#DCE6F1",
            "md_on_secondary_container" to "#2E3440"
        ),
        mapOf(
            "md_background" to "#2E3440", "md_surface" to "#3B4252",
            "md_surface_variant" to "#434C5E", "md_on_surface" to "#ECEFF4",
            "md_on_surface_variant" to "#D8DEE9", "md_primary" to "#88C0D0",
            "md_on_primary" to "#2E3440", "md_primary_container" to "#3B5368",
            "md_on_primary_container" to "#ECEFF4", "md_secondary_container" to "#41506B",
            "md_on_secondary_container" to "#ECEFF4"
        )
    ),
    "catppuccin" to sch(
        mapOf(
            "md_background" to "#EFF1F5", "md_surface" to "#E6E9EF",
            "md_surface_variant" to "#DCE0E8", "md_on_surface" to "#4C4F69",
            "md_on_surface_variant" to "#6C6F85", "md_primary" to "#1E66F5",
            "md_on_primary" to "#FFFFFF", "md_primary_container" to "#D3E0FA",
            "md_on_primary_container" to "#4C4F69", "md_secondary_container" to "#E4E7F0",
            "md_on_secondary_container" to "#4C4F69"
        ),
        mapOf(
            "md_background" to "#24273A", "md_surface" to "#1E2030",
            "md_surface_variant" to "#363A4F", "md_on_surface" to "#CAD3F5",
            "md_on_surface_variant" to "#A5ADCB", "md_primary" to "#8AADF4",
            "md_on_primary" to "#24273A", "md_primary_container" to "#37415F",
            "md_on_primary_container" to "#CAD3F5", "md_secondary_container" to "#414559",
            "md_on_secondary_container" to "#CAD3F5"
        )
    ),
    "solarized" to sch(
        mapOf(
            "md_background" to "#FDF6E3", "md_surface" to "#EEE8D5",
            "md_surface_variant" to "#E4DDC8", "md_on_surface" to "#073642",
            "md_on_surface_variant" to "#657B83", "md_primary" to "#268BD2",
            "md_on_primary" to "#FDF6E3", "md_primary_container" to "#D8E6F1",
            "md_on_primary_container" to "#073642", "md_secondary_container" to "#E4DDC8",
            "md_on_secondary_container" to "#073642"
        ),
        mapOf(
            "md_background" to "#002B36", "md_surface" to "#073642",
            "md_surface_variant" to "#0E4C58", "md_on_surface" to "#FDF6E3",
            "md_on_surface_variant" to "#93A1A1", "md_primary" to "#268BD2",
            "md_on_primary" to "#002B36", "md_primary_container" to "#144E6B",
            "md_on_primary_container" to "#FDF6E3", "md_secondary_container" to "#0E4C58",
            "md_on_secondary_container" to "#FDF6E3"
        )
    ),
    "gruvbox" to sch(
        mapOf(
            "md_background" to "#FBF1C7", "md_surface" to "#F2E5BC",
            "md_surface_variant" to "#EBDBB2", "md_on_surface" to "#3C3836",
            "md_on_surface_variant" to "#665C54", "md_primary" to "#076678",
            "md_on_primary" to "#FBF1C7", "md_primary_container" to "#D9E0C3",
            "md_on_primary_container" to "#3C3836", "md_secondary_container" to "#EBDBB2",
            "md_on_secondary_container" to "#3C3836"
        ),
        mapOf(
            "md_background" to "#282828", "md_surface" to "#32302F",
            "md_surface_variant" to "#504945", "md_on_surface" to "#FBF1C7",
            "md_on_surface_variant" to "#BDAE93", "md_primary" to "#83A598",
            "md_on_primary" to "#282828", "md_primary_container" to "#3F4B45",
            "md_on_primary_container" to "#FBF1C7", "md_secondary_container" to "#504945",
            "md_on_secondary_container" to "#FBF1C7"
        )
    ),
    "dracula" to sch(
        mapOf(
            "md_background" to "#F8F8F2", "md_surface" to "#EFF0EB",
            "md_surface_variant" to "#E3E5DE", "md_on_surface" to "#282A36",
            "md_on_surface_variant" to "#6272A4", "md_primary" to "#6272A4",
            "md_on_primary" to "#F8F8F2", "md_primary_container" to "#D9DEEA",
            "md_on_primary_container" to "#282A36", "md_secondary_container" to "#E3E5DE",
            "md_on_secondary_container" to "#282A36"
        ),
        mapOf(
            "md_background" to "#282A36", "md_surface" to "#21222C",
            "md_surface_variant" to "#44475A", "md_on_surface" to "#F8F8F2",
            "md_on_surface_variant" to "#BCC4DC", "md_primary" to "#BD93F9",
            "md_on_primary" to "#282A36", "md_primary_container" to "#44475A",
            "md_on_primary_container" to "#F8F8F2", "md_secondary_container" to "#3B3E52",
            "md_on_secondary_container" to "#F8F8F2"
        )
    ),
    "rosepine" to sch(
        mapOf(
            "md_background" to "#FAF4ED", "md_surface" to "#FFFAF3",
            "md_surface_variant" to "#F2E9E1", "md_on_surface" to "#575279",
            "md_on_surface_variant" to "#797593", "md_primary" to "#907AA9",
            "md_on_primary" to "#FAF4ED", "md_primary_container" to "#EBDFEF",
            "md_on_primary_container" to "#575279", "md_secondary_container" to "#F2E9E1",
            "md_on_secondary_container" to "#575279"
        ),
        mapOf(
            "md_background" to "#191724", "md_surface" to "#1F1D2E",
            "md_surface_variant" to "#26233A", "md_on_surface" to "#E0DEF4",
            "md_on_surface_variant" to "#908CAA", "md_primary" to "#C4A7E7",
            "md_on_primary" to "#191724", "md_primary_container" to "#342E4A",
            "md_on_primary_container" to "#E0DEF4", "md_secondary_container" to "#2A2740",
            "md_on_secondary_container" to "#E0DEF4"
        )
    )
)

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
    private lateinit var fallbackStatusText: TextView
    private lateinit var previewView: FontPreviewView
    private lateinit var previewInput: TextInputEditText
    private var previewText: String = DEFAULT_PREVIEW_TEXT
    private lateinit var customSection: LinearLayout

    private val slotOverrides = mutableMapOf<Int, Pair<Int?, Float?>>()
    private val slotFontFiles = mutableMapOf<Int, String>()
    private val slotFontNames = mutableMapOf<Int, String>()
    private val slotSummaries = mutableMapOf<Int, TextView>()
    private val slotChecks = mutableMapOf<Int, com.google.android.material.checkbox.MaterialCheckBox>()

    private fun checkedSlots(): List<Int> =
        SLOTS.indices.filter { slotChecks[it]?.isChecked == true }

    private lateinit var nameInput: TextInputEditText
    private lateinit var authorInput: TextInputEditText
    private lateinit var descInput: TextInputEditText

    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var progressLabel: TextView
    private lateinit var outputCard: MaterialCardView
    private lateinit var shareCard: MaterialCardView
    private lateinit var shareBar: LinearLayout
    private lateinit var outputCardArea: LinearLayout

    // 生成页向导步骤
    private val wizardScrolls = mutableListOf<ScrollView>()
    private var pageDots: List<TextView> = emptyList()
    private var btnPrevPage: MaterialButton? = null
    private var btnNextPage: MaterialButton? = null
    private var currentPage = 0

    // 设置页控件
    private lateinit var paletteDropdown: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var modeDropdown: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var authorPrefEdit: TextInputEditText

    private var fontLoaded = false
    private var fontFileName = ""
    private var fallbackFontPath: String? = null
    private var fallbackFontName: String? = null
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
        applyTheme()
        super.onCreate(savedInstanceState)
        val bg = currentPalette["md_background"] ?: 0
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        refreshCharSummary()
    }

    // ---------- 设置 ----------

    @Volatile
    private var currentPalette: Map<String, Int> = emptyMap()

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyTheme() {
        val scheme = SCHEMES[prefs.getString(KEY_PALETTE, "nord")] ?: SCHEMES.getValue("nord")
        val dark = when (prefs.getString(KEY_MODE, THEME_SYSTEM)) {
            THEME_LIGHT -> false
            THEME_DARK -> true
            else -> isSystemDark()
        }
        currentPalette = if (dark) scheme.second else scheme.first
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.getString(KEY_MODE, THEME_SYSTEM)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun defaultAuthor(): String = prefs.getString(KEY_AUTHOR, "").orEmpty()

    private fun clearCache() {
        if (isGenerating) {
            Toast.makeText(this, "正在生成中，请稍后再清理", Toast.LENGTH_SHORT).show()
            return
        }
        var freed = 0L
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("_tmp_") || f.name.startsWith("FontPack_")) {
                freed += f.length()
                f.delete()
            }
        }
        lastZipFile = null
        shareCard.visibility = View.GONE
        Toast.makeText(this, "已清理 ${freed / 1024} KB 缓存", Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // UI 构建 (Material 3 · Nord Light/Dark)
    // ============================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun colorOf(resId: Int): Int {
        val name = resources.getResourceEntryName(resId)
        return currentPalette[name] ?: ContextCompat.getColor(this, resId)
    }

    private fun buildUi() {
        window.decorView.setBackgroundColor(colorOf(R.color.md_background))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorOf(R.color.md_background))
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
        val previewColumn = wizColumn().apply { setPadding(dp(20), 0, dp(20), dp(8)) }
        newWizScroll().addView(genColumn)
        newWizScroll().addView(slotsColumn)
        newWizScroll().addView(outputColumn)
        newWizScroll().addView(previewColumn)

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
                addView(bannerButton("选择 Fallback 字体（可选）", filled = false) { chooseFallbackFont() })
                fallbackStatusText = TextView(this@MainActivity).apply {
                    text = "Fallback：系统字体（自动回退）"
                    textSize = 12f
                    setTextColor(colorOf(R.color.md_on_surface_variant))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                }
                addView(fallbackStatusText)
                addView(bannerButton("恢复系统 Fallback", filled = false) {
                    fallbackFontPath = null
                    fallbackFontName = null
                    updateFallbackStatus()
                    refreshPreview()
                    Toast.makeText(this@MainActivity, "已恢复使用系统字体作为 Fallback", Toast.LENGTH_SHORT).show()
                })
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
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                val pill = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundBg(colorOf(R.color.md_secondary_container), dp(14))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    setOnClickListener { showSlotEditor(i) }
                }
                val check = com.google.android.material.checkbox.MaterialCheckBox(this@MainActivity).apply {
                    isChecked = true
                }
                pill.addView(check)
                slotChecks[i] = check

                val lines = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                lines.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = s.key
                        textSize = 15f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(colorOf(R.color.md_on_primary_container))
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = s.label
                        textSize = 13f
                        setTextColor(colorOf(R.color.md_on_secondary_container))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                    })
                })
                val summary = TextView(this@MainActivity).apply {
                    textSize = 13f
                    setTextColor(colorOf(R.color.md_on_secondary_container))
                }
                lines.addView(summary)
                pill.addView(lines)
                row.addView(pill)
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

        outputCard = card(bgRes = R.color.md_primary_container) { add ->
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
            add.addView(outputCardArea)
        }.apply { visibility = View.GONE }
        previewColumn.addView(outputCard)

        // ---------- 预览页 ----------

        previewColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("字体预览"))
            previewInput = inputField(box, "自定义预览文本（每行一段）", maxLines = 4).apply {
                setText(DEFAULT_PREVIEW_TEXT)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        previewText = s?.toString().orEmpty()
                        refreshPreview()
                    }
                })
            }
            previewView = FontPreviewView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            box.addView(previewView)
            add.addView(box)
        })

        shareCard = card { add ->
            val box = innerColumn()
            shareBar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            shareBar.addView(bannerButton("分享字体包 ZIP", filled = false) { lastZipFile?.let(::shareZip) })
            shareBar.addView(bannerButton("导出字体包到本地", filled = false) { exportZip() })
            box.addView(shareBar)
            add.addView(box)
        }.apply { visibility = View.GONE }
        previewColumn.addView(shareCard)

        // ---------- 设置页 ----------

        setColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("配色方案"))
            paletteDropdown = dropdownField(
                box,
                SCHEME_LABELS.map { it.second },
                SCHEME_LABELS.firstOrNull { it.first == prefs.getString(KEY_PALETTE, "nord") }?.second ?: "Nord"
            ) { pos ->
                val key = SCHEME_LABELS[pos].first
                prefs.edit().putString(KEY_PALETTE, key).apply()
                applyTheme()
                recreate()
            }
            add.addView(box)
        })

        setColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("明暗模式"))
            val modeOptions = listOf(
                Triple("跟随系统", THEME_SYSTEM, null),
                Triple("浅色", THEME_LIGHT, null),
                Triple("深色", THEME_DARK, null)
            )
            val currentMode = prefs.getString(KEY_MODE, THEME_SYSTEM)
            modeDropdown = dropdownField(
                box,
                modeOptions.map { it.first },
                modeOptions.firstOrNull { it.second == currentMode }?.first ?: "跟随系统"
            ) { pos ->
                prefs.edit().putString(KEY_MODE, modeOptions[pos].second).apply()
                applyTheme()
                recreate()
            }
            add.addView(box)
        })

        setColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("生成默认值"))
            authorPrefEdit = inputField(box, "默认作者名（生成时「作者名」留空则使用此值）")
            authorPrefEdit.setText(defaultAuthor())
            authorPrefEdit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString(KEY_AUTHOR, s?.toString()?.trim().orEmpty()).apply()
                }
            })
            add.addView(box)
        })

        setColumn.addView(card { add ->
            val box = innerColumn()
            box.addView(sectionTitle("存储"))
            box.addView(TextView(this@MainActivity).apply {
                text = "生成过程中的临时字体、贴图和字体包存放在应用缓存目录，可随时清理。"
                textSize = 13f
                setTextColor(colorOf(R.color.md_on_surface_variant))
                setPadding(0, dp(4), 0, 0)
            })
            box.addView(bannerButton("清理生成缓存", filled = false) { clearCache() })
            add.addView(box)
        })

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
            menu.add(0, ID_TAB_GENERATE, 0, "生成").setIcon(R.drawable.ic_tab_generate)
            menu.add(0, ID_TAB_SETTINGS, 1, "设置").setIcon(R.drawable.ic_tab_settings)
            itemIconTintList = android.content.res.ColorStateList.valueOf(colorOf(R.color.md_primary))
            itemTextColor = android.content.res.ColorStateList.valueOf(colorOf(R.color.md_primary))
            background = GradientDrawable().apply {
                setColor(colorOf(R.color.md_surface))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnItemSelectedListener { item ->
                lastTab = item.itemId
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
        nav.selectedItemId = lastTab
    }

    private fun setPage(index: Int) {
        currentPage = index.coerceIn(0, wizardScrolls.lastIndex)
        wizardScrolls.forEachIndexed { i, sc -> sc.visibility = if (i == currentPage) View.VISIBLE else View.GONE }
        btnPrevPage?.visibility = if (currentPage == 0) View.INVISIBLE else View.VISIBLE
        if (currentPage == wizardScrolls.lastIndex) {
            btnNextPage?.text = "开始生成"
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

    private fun dropdownField(
        parent: LinearLayout,
        options: List<String>,
        current: String,
        onSelect: (Int) -> Unit
    ): com.google.android.material.textfield.MaterialAutoCompleteTextView {
        val layout = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val dropdown = com.google.android.material.textfield.MaterialAutoCompleteTextView(this).apply {
            setSimpleItems(options.toTypedArray())
            setText(current, false)
            isFocusable = false
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, pos, _ -> onSelect(pos) }
        }
        layout.addView(dropdown)
        parent.addView(layout)
        return dropdown
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

    private fun chooseFallbackFont() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_FALLBACK)
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
            when (requestCode) {
                REQ_FONT -> loadFont(data.data!!, displayName(data.data!!))
                REQ_FALLBACK -> {
                    val f = copyUriToCache(data.data!!, "_tmp_fallback.ttf")
                    if (!isValidFontFile(f)) {
                        f.delete()
                        Toast.makeText(this, "选择的字体无效！", Toast.LENGTH_LONG).show()
                        return
                    }
                    fallbackFontPath = f.absolutePath
                    fallbackFontName = displayName(data.data!!)
                    updateFallbackStatus()
                    refreshPreview()
                    Toast.makeText(this,
                        "Fallback 字体已设为 ${fallbackFontName}",
                        Toast.LENGTH_SHORT).show()
                }
                REQ_ASCII -> { copyUriTextTo(data.data!!, asciiChars); refreshCharSummary() }
                REQ_DIGITS -> { copyUriTextTo(data.data!!, digitChars); refreshCharSummary() }
                REQ_FULL -> { copyUriTextTo(data.data!!, fullChars); refreshCharSummary() }
                in REQ_SLOT_FONT_BASE..REQ_SLOT_FONT_BASE + SLOTS.size - 1 -> {
                    val idx = requestCode - REQ_SLOT_FONT_BASE
                    val f = copyUriToCache(data.data!!, "_tmp_slot$idx.ttf")
                    if (!isValidFontFile(f)) {
                        f.delete()
                        Toast.makeText(this, "选择的字体无效！", Toast.LENGTH_LONG).show()
                        return
                    }
                    slotFontFiles[idx] = f.absolutePath
                    slotFontNames[idx] = displayName(data.data!!)
                    Toast.makeText(this,
                        "槽位 ${SLOTS[idx].key} 将使用独立字体 ${slotFontNames[idx]}",
                        Toast.LENGTH_SHORT).show()
                    refreshCharSummary()
                }
                REQ_EXPORT -> {
                    if (copyZipTo(data.data!!))
                        Toast.makeText(this, "已导出到所选位置", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "文件读取失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 流式复制到缓存文件，避免大文件 OOM */
    private fun copyUriToCache(uri: Uri, fileName: String): File {
        val f = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(f).use { output -> input.copyTo(output) }
        }
        return f
    }

    private fun copyUriTextTo(uri: Uri, sb: StringBuilder) {
        contentResolver.openInputStream(uri)!!.use { input ->
            sb.clear().append(input.bufferedReader().readText())
        }
    }

    private fun isValidFontFile(f: File): Boolean {
        if (f.length() < 12) return false
        // 以系统解析结果为准：解析成功且能度量即有效（TTF/OTF/TTC/WOFF 均可）
        return runCatching {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.typeface = Typeface.createFromFile(f)
            p.measureText("Aa1中") > 0f
        }.getOrDefault(false)
    }

    private fun loadFont(uri: Uri, fileName: String?) {
        val f = copyUriToCache(uri, "_tmp_font.ttf")
        if (!isValidFontFile(f)) {
            f.delete()
            Toast.makeText(this, "选择的字体无效！", Toast.LENGTH_LONG).show()
            return
        }
        fontLoaded = true
        fontFileName = fileName ?: ""
        cachedFontPath = f.absolutePath
        fontStatusText.text = "$fontFileName · ${f.length() / 1024} KB"
        fontStatusText.setTextColor(colorOf(R.color.md_primary))
        if (nameInput.text.isNullOrBlank())
            nameInput.setText(fontFileName.substringBeforeLast('.'))
        refreshPreview()
    }

    // ---------- Fallback / 预览 ----------

    private fun updateFallbackStatus() {
        if (!this::fallbackStatusText.isInitialized) return
        val name = fallbackFontName
        fallbackStatusText.text = if (name != null) "Fallback：$name" else "Fallback：系统字体（自动回退）"
        fallbackStatusText.setTextColor(
            colorOf(if (name != null) R.color.md_primary else R.color.md_on_surface_variant)
        )
    }

    private fun refreshPreview() {
        if (this::previewView.isInitialized) previewView.invalidate()
    }

    /**
     * 通过解析字体文件的 cmap 表判断字符覆盖情况。
     * Paint.hasGlyph 会把系统回退字体算进去，无法用来判断主字体本身是否缺字。
     */
    private class FontGlyphCoverage(fontPath: String) {
        private var sub4: ByteArray? = null
        private var groups12: IntArray? = null

        init { runCatching { parse(fontPath) } }

        private fun parse(path: String) {
            java.io.RandomAccessFile(path, "r").use { raf ->
                fun u16() = raf.readUnsignedShort()
                fun i32() = raf.readInt()
                var base = 0L
                if (i32() == 0x74746366) { // 'ttcf'
                    raf.seek(12)
                    base = i32().toLong() and 0xFFFFFFFFL
                }
                raf.seek(base + 4)
                val numTables = u16()
                var cmapOff = -1L
                var cmapLen = 0
                repeat(numTables) { i ->
                    raf.seek(base + 12 + i * 16)
                    val tag = ByteArray(4); raf.readFully(tag)
                    i32(); val off = i32(); val len = i32()
                    if (String(tag, Charsets.US_ASCII) == "cmap") {
                        cmapOff = off.toLong() and 0xFFFFFFFFL
                        cmapLen = len
                    }
                }
                if (cmapOff < 0 || cmapLen <= 0) return
                val cmap = ByteArray(cmapLen)
                raf.seek(cmapOff)
                raf.readFully(cmap)
                fun bu16(o: Int) = ((cmap[o].toInt() and 0xFF) shl 8) or (cmap[o + 1].toInt() and 0xFF)
                fun bi32(o: Int) = ((cmap[o].toInt() and 0xFF) shl 24) or ((cmap[o + 1].toInt() and 0xFF) shl 16) or
                    ((cmap[o + 2].toInt() and 0xFF) shl 8) or (cmap[o + 3].toInt() and 0xFF)
                val n = bu16(2)
                var best4 = -1
                var best12 = -1
                for (i in 0 until n) {
                    val rec = 4 + i * 8
                    if (rec + 8 > cmap.size) break
                    val pid = bu16(rec); val eid = bu16(rec + 2); val off = bi32(rec + 4)
                    if (off < 0 || off + 2 > cmap.size) continue
                    when (bu16(off)) {
                        12 -> if (best12 < 0 && (pid == 0 || (pid == 3 && eid == 10))) best12 = off
                        4 -> if (best4 < 0 && (pid == 0 || (pid == 3 && eid == 1))) best4 = off
                    }
                }
                if (best12 >= 0 && best12 + 16 <= cmap.size) {
                    val ng = bi32(best12 + 12)
                    val arr = IntArray(ng * 2)
                    for (g in 0 until ng) {
                        val go = best12 + 16 + g * 12
                        if (go + 12 > cmap.size) break
                        arr[g * 2] = bi32(go)
                        arr[g * 2 + 1] = bi32(go + 4)
                    }
                    groups12 = arr
                } else if (best4 >= 0) {
                    val segCountX2 = bu16(best4 + 6)
                    val size = minOf(16 + segCountX2 * 4, cmap.size - best4)
                    if (size > 14) sub4 = cmap.copyOfRange(best4, best4 + size)
                }
            }
        }

        fun covers(ch: Char): Boolean {
            val cp = ch.code
            if (cp > 0xFFFF) {
                val g = groups12 ?: return false
                for (i in g.indices step 2) if (cp in g[i]..g[i + 1]) return true
                return false
            }
            sub4?.let { return covers4(cp, it) }
            val g = groups12 ?: return true // cmap 解析失败时保守处理：按覆盖算，走原行为
            for (i in g.indices step 2) if (cp in g[i]..g[i + 1]) return true
            return false
        }

        private fun covers4(cp: Int, b: ByteArray): Boolean {
            fun bu16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
            val segCount = bu16(6) / 2
            val endOff = 14
            val startOff = endOff + segCount * 2 + 2
            val deltaOff = startOff + segCount * 2
            val rangeOff = deltaOff + segCount * 2
            for (i in 0 until segCount) {
                val end = bu16(endOff + i * 2)
                if (cp > end) continue
                val start = bu16(startOff + i * 2)
                if (cp < start) return false
                val ro = bu16(rangeOff + i * 2)
                if (ro == 0) return ((cp + bu16(deltaOff + i * 2)) and 0xFFFF) != 0
                val idx = rangeOff + i * 2 + ro + (cp - start) * 2
                if (idx + 1 >= b.size) return false
                return bu16(idx) != 0
            }
            return false
        }
    }

    private val coverageCache = mutableMapOf<String, FontGlyphCoverage>()

    private fun coverageOf(path: String?): FontGlyphCoverage? =
        path?.let { coverageCache.getOrPut(it) { FontGlyphCoverage(it) } }

    /** 选字 paint：主字体自身缺字（cmap 不含该码位）且 fallback 字体可渲染时用 fallback */
    private fun pickPaint(
        ch: Char,
        mainPaint: Paint,
        fbPaint: Paint?,
        mainCov: FontGlyphCoverage?,
        fbCov: FontGlyphCoverage?
    ): Paint {
        if (fbPaint == null || fbCov == null) return mainPaint
        val mainHas = mainCov?.covers(ch) ?: true
        return if (!mainHas && fbCov.covers(ch)) fbPaint else mainPaint
    }

    private fun buildPaint(typeface: Typeface, fs: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = fs.toFloat()
            color = Color.WHITE
        }

    private inner class FontPreviewView(context: android.content.Context) : View(context) {
        private val mainPaint = buildPaint(Typeface.DEFAULT, 26).apply { color = colorOf(R.color.md_on_surface) }
        private var fbPaint: Paint? = null
        private var mainCov: FontGlyphCoverage? = null
        private var fbCov: FontGlyphCoverage? = null

        fun rebuildPaints() {
            val mainTf = cachedFontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
                ?: Typeface.DEFAULT
            mainPaint.typeface = mainTf
            mainCov = coverageOf(cachedFontPath)
            val fbTf = fallbackFontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            fbPaint = fbTf?.let { buildPaint(it, 26).apply { color = colorOf(R.color.md_on_surface) } }
            fbCov = if (fbPaint != null) coverageOf(fallbackFontPath) else null
        }

        override fun onDraw(canvas: Canvas) {
            rebuildPaints()
            mainPaint.textSize = 44f
            fbPaint?.textSize = 44f
            val lineH = 62
            val top = dp(12)
            val lines = previewText.lines()
            (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                val needed = top + 44 + lines.size * lineH + dp(8)
                if (lp.height != needed) {
                    lp.height = needed
                    requestLayout()
                }
            }
            var y = top + 44
            lines.forEach { line ->
                var x = dp(4).toFloat()
                line.forEach { ch ->
                    val p = pickPaint(ch, mainPaint, fbPaint, mainCov, fbCov)
                    canvas.drawText(ch.toString(), x, y.toFloat(), p)
                    x += p.measureText(ch.toString())
                }
                y += lineH
            }
        }
    }

    // ============================================================
    // 字库
    // ============================================================

    private fun resText(resId: Int): String =
        resources.openRawResource(resId).bufferedReader().readText()

    private fun cleanChars(raw: String): List<Char> =
        raw.toList().distinct().filter { it != '\uFEFF' }

    private fun customReady(): Boolean =
        !useBuiltinLibs && (asciiChars.isNotEmpty() || digitChars.isNotEmpty() || fullChars.isNotEmpty())

    /** 槽位实际使用的字符集：自定义字库按文件逐项生效，未提供的类别回退到内置字库 */
    private fun charsForSlot(pos: Int): List<Char> {
        val res = slotRes(pos)
        val custom = when (res) {
            R.raw.builtin_chars_full -> fullChars
            R.raw.builtin_chars_digits -> digitChars
            else -> asciiChars
        }
        val source = if (custom.isNotEmpty() && customReady()) custom.toString() else resText(res)
        return cleanChars(source)
    }

    private fun customUsedFor(pos: Int): Boolean {
        val custom = when (slotRes(pos)) {
            R.raw.builtin_chars_full -> fullChars
            R.raw.builtin_chars_digits -> digitChars
            else -> asciiChars
        }
        return customReady() && custom.isNotEmpty()
    }

    private fun refreshCharSummary() {
        SLOTS.indices.forEach { updateSlotRow(it) }
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

    private fun updateSlotRow(idx: Int) {
        if (!this::customSection.isInitialized) return
        val s = SLOTS[idx]
        val (fs, sp) = slotEffective(idx)
        val src = if (customUsedFor(idx)) "自定义(${charsForSlot(idx).size})" else builtinName(slotRes(idx))
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
            slotFontFiles.remove(idx)
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
                updateSlotRow(idx)
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
        val targets = checkedSlots()
        if (targets.isEmpty()) {
            Toast.makeText(this, "请至少勾选一个槽位", Toast.LENGTH_SHORT).show()
            return
        }
        isGenerating = true
        outputCard.visibility = View.VISIBLE
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
    private var cachedFontPath: String? = null

    private fun makeFontFile(): String = cachedFontPath ?: error("字体未缓存")

    private fun doGenerate(): File {
        val t0 = System.currentTimeMillis()
        val pkgName = nameInput.text?.toString()?.trim().ifNullOrEmpty {
            fontFileName.substringBeforeLast('.').ifEmpty { "font" }
        }
        val author = authorInput.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultAuthor()
        val desc = descInput.text?.toString()?.trim() ?: ""

        val results = mutableListOf<GeneratedSlot>()
        val targetIdx = checkedSlots()
        val nTargets = targetIdx.size
        val pageW = 1024
        val pageH = 1024
        val padding = 1

        targetIdx.forEachIndexed { pos, n ->
            val slot = SLOTS[n]
            val (fs, spacing) = slotEffective(n)
            val chars = charsForSlot(n)
            if (chars.isEmpty()) return@forEachIndexed

            handler.post { progressLabel.text = "${slot.key} (${pos + 1}/$nTargets)…" }

            // 字体：槽位独立字体优先，否则全局所选字体
            val fontPath = if (n in slotFontFiles) slotFontFiles[n]!!
            else makeFontFile()
            val mainPaint = buildPaint(Typeface.createFromFile(fontPath), fs)
            val mainCov = coverageOf(fontPath)
            // 自定义 Fallback：主字体缺字时优先使用，其次才是系统字体
            val fbPaint = fallbackFontPath?.let { path ->
                runCatching {
                    buildPaint(Typeface.createFromFile(path), fs)
                }.getOrNull()
            }
            val fbCov = if (fbPaint != null) coverageOf(fallbackFontPath) else null

            val renders = mutableListOf<CharRender>()
            chars.forEachIndexed { index, ch ->
                renderChar(ch, pickPaint(ch, mainPaint, fbPaint, mainCov, fbCov), fs)?.let { renders.add(it) }
                if ((index + 1) % 800 == 0 || index == chars.lastIndex) {
                    val p = ((pos * 90f / nTargets) + index * (50f / nTargets) / chars.size).toInt().coerceIn(0, 95)
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
                progressIndicator.setProgressCompat(((pos + 1) * 90f / nTargets).toInt().coerceAtMost(95), true)
            }
        }

        if (results.isEmpty()) throw IllegalStateException("没有可生成的有效字符")

        handler.post { progressLabel.text = "打包中..." }
        val zipFile = File(cacheDir, "FontPack_$pkgName.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            targetIdx.forEach {
                zos.putNextEntry(ZipEntry("${SLOTS[it].key}/"))
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

        val dur = "%.2f".format((System.currentTimeMillis() - t0) / 1000.0)
        handler.post { progressLabel.text = "完成 · $dur s · ${results.size} 槽位" }
        return zipFile
    }

    private fun onGenerated(zipFile: File) {
        isGenerating = false
        lastZipFile = zipFile
        shareCard.visibility = View.VISIBLE
        shareBar.visibility = View.VISIBLE
        progressIndicator.setProgressCompat(100, true)
        Toast.makeText(this, "字体包已生成", Toast.LENGTH_SHORT).show()
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
        @Volatile
        private var lastTab: Int = ID_TAB_GENERATE
        private const val KEY_PALETTE = "palette"
        private const val KEY_MODE = "mode"
        private const val KEY_AUTHOR = "author"
        private const val THEME_SYSTEM = "system"
        private const val THEME_LIGHT = "light"
        private const val THEME_DARK = "dark"

        private const val PACK_INFO_ENTRY = "pack_info.json"
        private const val DEFAULT_PREVIEW_TEXT = "Hello World!\n你好, 世界!"
        private const val REQ_FONT = 10
        private const val REQ_FALLBACK = 14
        private const val REQ_ASCII = 11
        private const val REQ_DIGITS = 12
        private const val REQ_FULL = 13
        private const val REQ_SLOT_FONT_BASE = 20
        private const val REQ_EXPORT = 30
        private const val ID_TAB_GENERATE = 1
        private const val ID_TAB_SETTINGS = 2
    }
}