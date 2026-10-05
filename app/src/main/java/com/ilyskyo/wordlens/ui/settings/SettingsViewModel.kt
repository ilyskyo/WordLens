// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.repository.decodeDeck
import com.ilyskyo.wordlens.data.model.RatingPalette
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.repository.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的写入口。
 *
 * 只暴露**确实有消费方**的项：用户拨完发现什么也没发生的开关，比没有这个开关更糟——
 * 它教会用户「这里的设置不可信」，而那个印象会波及所有真的生效的开关。
 * 反过来说，一旦某个字段接上了消费方（保持率 → `Fsrs`、色系 → 主题、脱敏 → 上传前剥 EXIF），
 * 它就欠着一个界面，必须补在这里。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<AppSettings> = container.settings.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AppSettings(),
        )

    init {
        // 打开设置页就探一次。「引擎装了但没下载该语言的语音包」是绝大多数无声的原因，
        // 而它只能主动问出来——等用户点喇叭听不到再看，那个页面已经关掉了。
        reprobeVoices()
    }

    fun onTargetLanguage(lang: Lang) = write { setTargetLanguage(lang) }

    fun onNativeLanguage(lang: Lang) = write { setNativeLanguage(lang) }

    fun onDirection(direction: StudyDirection) = write { setDirection(direction) }

    fun onRetention(value: Double) = write { setRetention(value) }

    fun onRatingPalette(palette: RatingPalette) = write { setRatingPalette(palette) }

    /** 装好的 TTS 引擎名。空列表意味着只有系统默认引擎。 */
    val engines: StateFlow<List<String>> = container.speaker.engines

    /** 当前引擎**念不出来**的语言。空就代表四种语言都有声音。 */
    val missingLanguages: StateFlow<List<Lang>> = container.speaker.unsupportedLanguages
        .map { missing: Set<Lang> ->
            // 按 Lang 自己的顺序输出，而不是集合的哈希顺序：否则每次进设置页，
            // 「缺哪些语音」的列表顺序都会换一遍。
            Lang.entries.filter { it in missing }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun acknowledgeNotice() {
        _notice.value = null
    }

    /**
     * 主动探一次语音支持。
     *
     * 探测会改引擎状态（setLanguage 是有副作用的），所以绝不能在复习中途做——
     * 那会让下一句念起来跟平时不一样。只在设置页打开时、以及用户按下「重新检查」时跑。
     */
    fun reprobeVoices() {
        viewModelScope.launch { container.speaker.probeSupport() }
    }

    /**
     * 从用户选中的 `deck.json` 合并词卡。
     *
     * 只合并卡片本身：照片从来不在 deck.json 里（它只存文件名），所以导入之后
     * 带贴纸的卡可能显示不出图——这句话必须写在结果里，而不是让用户自己发现。
     */
    fun onImportDeck(uri: android.net.Uri) {
        viewModelScope.launch {
            val document = withContext(Dispatchers.IO) {
                runCatching {
                    container.appContext.contentResolver.openInputStream(uri)?.use(::decodeDeck)
                }.getOrNull()
            }
            if (document == null) {
                _notice.value = container.appContext.getString(R.string.settings_import_bad)
                return@launch
            }
            val result = container.deck.mergeFrom(document)
            _notice.value = container.appContext.getString(
                R.string.settings_import_done,
                result.added,
                result.updated,
            )
        }
    }

    /**
     * 开关只写设置，不直接叫调度器干活。
     *
     * `AppContainer` 订阅了设置流并负责把 WorkManager 的排期对齐过去，所以这里多调一次
     * 就会有两个写者——那正是「改了设置但排期没变」这类 bug 的产地。
     */
    fun onReminderEnabled(enabled: Boolean) = write { setReminder(enabled) }

    fun onReminderMinuteOfDay(minute: Int) = write { setReminderMinuteOfDay(minute) }

    fun onCloudEnabled(enabled: Boolean) = write { setCloudEnabled(enabled) }

    fun onCloudModel(model: String) = write { setCloudModel(model) }

    fun onCloudApiKey(key: String) = write { setCloudApiKey(key) }

    fun onClearCloudApiKey() = write { clearCloudApiKey() }

    private fun write(block: suspend com.ilyskyo.wordlens.data.repository.SettingsRepository.() -> Unit) {
        viewModelScope.launch { container.settings.block() }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer): androidx.lifecycle.ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(container) } }
    }
}
