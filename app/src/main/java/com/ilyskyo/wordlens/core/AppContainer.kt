// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core

import android.content.Context
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.repository.DeckDocument
import com.ilyskyo.wordlens.data.repository.DeckRepository
import com.ilyskyo.wordlens.data.repository.DiaryDocument
import com.ilyskyo.wordlens.data.repository.DiaryRepository
import com.ilyskyo.wordlens.data.repository.LexiconRepository
import com.ilyskyo.wordlens.data.repository.SettingsRepository
import com.ilyskyo.wordlens.data.store.JsonDocument
import com.ilyskyo.wordlens.speech.Speaker
import com.ilyskyo.wordlens.vision.VisionRepository
import com.ilyskyo.wordlens.vision.detection.EfficientDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Manual dependency container.
 *
 * Deliberately not Hilt. The graph is a dozen objects deep with no scoping subtleties, and
 * annotation processing is the single most common source of first-build pain in a project like
 * this — one less processor is one less thing to break when a dependency moves. If the graph
 * ever grows scopes that actually differ, swapping this for Hilt is a contained change because
 * nothing outside this file knows how objects are constructed.
 *
 * Everything lives in [ApplicationScope]; the app is small enough that per-screen scopes buy
 * nothing, and a leaked camera analyser is worse than a slightly long-lived repository.
 */
class AppContainer(context: Context) {

    /** 暴露给 ViewModel 读取字符串资源；仓库层需要的路径已经各自持有。 */
    val appContext: Context = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── storage ──────────────────────────────────────────────────────────────

    /** Sticker cut-outs, referenced by name from `WordCard.stickerPath`. */
    val stickerDir: File = File(appContext.filesDir, "stickers").apply { mkdirs() }

    /** Full-resolution source photos, kept separately so a sticker can be re-cut. */
    val photoDir: File = File(appContext.filesDir, "photos").apply { mkdirs() }

    /** 日记条目的照片。与 photoDir 分开：Entry.photoPath 以这里为根，删除时好级联。 */
    val entryPhotoDir: File = File(appContext.filesDir, "entries").apply { mkdirs() }

    private val deckDocument = JsonDocument(
        file = File(appContext.filesDir, "deck.json"),
        fallback = { DeckDocument() },
        serializer = DeckDocument.serializer(),
        scope = applicationScope,
        tag = "DeckDocument",
        currentSchema = DeckDocument.CURRENT_SCHEMA,
        schemaOf = DeckDocument::schemaVersion,
    )

    val deck = DeckRepository(deckDocument, applicationScope)

    private val diaryDocument = JsonDocument(
        file = File(appContext.filesDir, "diary.json"),
        fallback = { DiaryDocument() },
        serializer = DiaryDocument.serializer(),
        scope = applicationScope,
        tag = "DiaryDocument",
        currentSchema = DiaryDocument.CURRENT_SCHEMA,
        schemaOf = DiaryDocument::schemaVersion,
    )

    val diary = DiaryRepository(diaryDocument, entryPhotoDir, applicationScope)

    val lexicon = LexiconRepository(appContext, applicationScope)

    val settings = SettingsRepository(appContext)

    // ── behaviour ────────────────────────────────────────────────────────────

    val speaker = Speaker(appContext)

    val vision = VisionRepository(appContext, applicationScope, lexicon, settings)

    /**
     * COCO 物体检测器（取景页词片的来源）。
     *
     * lazy 是有意的：模型常驻内存约 4.5 MB，只有真正打开取景页才值得加载；
     * 从桌面直接进「记住」复习的用户全程不碰它。加载失败是 null——取景页降级为无词片。
     */
    val detector: EfficientDetector? by lazy { EfficientDetector.create(appContext) }

    /**
     * Cards due right now, in whichever direction the user is currently drilling.
     *
     * Suspends because the direction lives in DataStore; the widget provider runs in a coroutine
     * so it can afford to wait for the first emission.
     */
    suspend fun dueCount(): Int {
        val direction = settings.settings.first().direction
        return deck.stats(direction).due
    }

    /** Build a card from a lexicon entry without touching the repository. */
    fun cardFrom(
        entry: LexiconEntry,
        target: com.ilyskyo.wordlens.data.model.Lang,
        native: com.ilyskyo.wordlens.data.model.Lang,
        source: EntrySource = EntrySource.ON_DEVICE,
    ): WordCard? = entry.toCard(target, native, source)

    fun warmUp() {
        deck.loadAsync()
        diary.loadAsync()
        lexicon.loadAsync()
        // 词表是一份小 JSON，冷启动就该在：取景页第一次出词前它必须就绪。
        vision.loadTaxonomy()
    }
}
