// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

/**
 * 空指示器：按下时什么都不画。
 *
 * ## 它解决的是「全局消灭 Ripple」这条硬性约束
 *
 * Ripple 的问题不是难看，而是**说谎**：它从触点画出一圈匀速扩散的波，而 iOS 的物理世界里
 * 没有这种东西——按下去的东西是**缩下去**的。本项目所有按压反馈都改由 [pressFeedback] 提供
 * （缩放 + 透明度 + 触觉），所以波纹必须是零。
 *
 * ## 为什么是「换成空实现」而不是「每个组件传 indication = null」
 *
 * `indication = null` 只影响那一个调用点，而 Material 组件（Button / Surface / Tab / Switch）
 * 内部默认读 [androidx.compose.foundation.LocalIndication]。在主题里换成这个空实现，
 * 一处生效、全 App 生效，新写的组件也永远不会不小心把波纹带回来。
 *
 * ## 为什么焦点框不受影响
 *
 * 键盘 / 外接手柄的可见焦点走 `LocalFocusIndicator`，与 indication 是两条独立通道。
 * 关掉波纹之后仍然保留焦点框是必须的——按下去没有任何视觉反馈的控件，
 * 对键盘导航与低视力用户是真实障碍。
 */
object NoIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode

    /**
     * [Indication] 的实现会被 Modifier 复用，官方契约要求自行实现相等性。
     *
     * 这里是一个 object，恒等比较就是它的全部语义：任何时候的「无指示器」都是同一个无指示器。
     */
    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = javaClass.name.hashCode()
}

/**
 * 只透传内容、不画任何东西的绘制节点。
 *
 * 必须调用 [ContentDrawScope.drawContent]：指示器是**包在内容外面**的一层绘制，
 * 不往下传就等于把整个组件画没了。
 */
private object NoIndicationNode : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
    }
}
