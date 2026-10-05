// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.repository.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页的写入口。
 *
 * 只暴露**确实有消费方**的项。[AppSettings] 里还有目标保持率、置信度下限、复习提醒等字段，
 * 但目前没有任何代码读它们——把它们做成开关，用户拨完会发现什么也没发生，那比没有这个开关
 * 更糟。等接上消费方再补界面。
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
