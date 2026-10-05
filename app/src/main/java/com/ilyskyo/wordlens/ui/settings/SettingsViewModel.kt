// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.RatingPalette
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.repository.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun onTargetLanguage(lang: Lang) = write { setTargetLanguage(lang) }

    fun onNativeLanguage(lang: Lang) = write { setNativeLanguage(lang) }

    fun onDirection(direction: StudyDirection) = write { setDirection(direction) }

    fun onRetention(value: Double) = write { setRetention(value) }

    fun onRatingPalette(palette: RatingPalette) = write { setRatingPalette(palette) }

    fun onRedactBeforeUpload(enabled: Boolean) = write { setRedactBeforeUpload(enabled) }

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
