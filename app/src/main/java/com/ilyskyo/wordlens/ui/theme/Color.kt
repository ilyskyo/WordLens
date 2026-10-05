// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「温暖手账 × 现代数字贴纸」色板。
 *
 * 设计意图：暖珊瑚橙做主色，模拟贴纸边缘那一点 Marker 橙；蓝绿做辅色，压住整体的暖燥；
 * 奶油白打底，让透明背景的贴纸浮起来时不会显得脏。
 *
 * 这套色板是为本项目单独推导的，不来自任何现有应用的品牌色。
 */

// ── 品牌主色：暖珊瑚橙 ───────────────────────────────────────────
val Coral = Color(0xFFFF8A65)
val CoralDeep = Color(0xFFC74E2E)   // 浅色主题下的按下态与深色主题主色
val CoralSoft = Color(0xFFFFE0D6)   // PrimaryContainer
val CoralInk = Color(0xFF3E1A0F)    // OnPrimaryContainer

// ── 辅色：柔和蓝绿 ─────────────────────────────────────────────
val Seafoam = Color(0xFF4DB6AC)
val SeafoamDeep = Color(0xFF1F6E66)
val SeafoamSoft = Color(0xFFC8F0EB)
val SeafoamInk = Color(0xFF003731)

// ── 第三色：温暖黄，仅用于「今天/待复习」这类提醒语义 ──────────
val Amber = Color(0xFFFFD54F)
val AmberInk = Color(0xFF3D2E00)

// ── 中性色：奶油白 + 深棕灰 ────────────────────────────────────
val Cream = Color(0xFFFFF8F3)
val Paper = Color(0xFFFFFFFF)
val PaperDim = Color(0xFFF5EDE6)   // SurfaceVariant
val Cocoa = Color(0xFF3E2C23)      // OnSurface
val CocoaSoft = Color(0xFF5C4A40)  // OnSurfaceVariant
val Sandalwood = Color(0xFFD7C4B8) // Outline
val Petal = Color(0xFFE57373)      // Error

// ── 深色主题 ───────────────────────────────────────────────────
val NightBase = Color(0xFF1A1512)
val NightPaper = Color(0xFF241E1A)
val NightPaperHigh = Color(0xFF322A25)
val NightInk = Color(0xFFF2E7DF)
val NightInkSoft = Color(0xFFD3C2B8)
val NightOutline = Color(0xFF574A42)
