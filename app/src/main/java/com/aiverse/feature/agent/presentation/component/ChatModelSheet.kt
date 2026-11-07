package com.aiverse.feature.agent.presentation.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aiverse.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aiverse.R
import com.aiverse.core.theme.Spacing
import com.aiverse.core.ui.AppSwitch
import com.aiverse.feature.agent.domain.model.ReasoningCapability
import com.aiverse.feature.agent.domain.model.ReasoningEffort
import com.aiverse.feature.agent.domain.model.ReasoningPlan
import com.aiverse.feature.agent.domain.provider.ReasoningProtocol
import com.aiverse.feature.agent.domain.provider.ReasoningResolver
import com.aiverse.feature.agent.domain.provider.preferredOnLevel
import com.aiverse.feature.agent.domain.provider.showsThinkingToggle
import com.aiverse.feature.onboarding.domain.OnboardingStep
import com.aiverse.feature.settings.data.remote.ModelTestResult
import com.aiverse.feature.settings.domain.model.AIProviderConfig
import com.aiverse.feature.settings.domain.model.ModelMetadata
import com.aiverse.feature.settings.domain.model.modelMetadataKey
import com.aiverse.feature.settings.presentation.component.ModelLogoIcon
import com.aiverse.feature.settings.presentation.component.ModelSelectionSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Zap
import kotlinx.coroutines.launch

/**
 * 输入区下行的模型切换图标按钮
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModelIconButton(
    provider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    forceOpenSheet: Boolean = false,
    onSelectSuccessInOnboarding: (() -> Unit)? = null,
    onSheetDismiss: (() -> Unit)? = null,
    /** 长按模型图标：直接跳到 AI 供应商设置页，方便添加供应商。 */
    onOpenProviders: (() -> Unit)? = null,
    runModelTest: (suspend (AIProviderConfig, String) -> ModelTestResult)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = { showSheet = true },
                onLongClick = onOpenProviders
            ),
        contentAlignment = Alignment.Center
    ) {
        ModelLogoIcon(modelName = provider?.effectiveModel.orEmpty(), size = 20.dp)
    }

    if (effectiveShowSheet) {
        ModelSheet(
            providers = providers,
            currentProviderId = provider?.id ?: "",
            currentModel = provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            onSelect = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            },
            runModelTest = runModelTest
        )
    }
}

/**
 * 思考强度选择器：图标按钮 → 底部弹层，「思考」总开关 + 只列模型真实支持的档位。
 *
 * 档位集合与「实际生效」全部来自 [ReasoningResolver]，与请求侧完全同源：
 * 最高只到「高」的模型不会再列出「最高」，思考恒开 / 端点不认「关闭」时也会明确提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortSelector(
    effort: ReasoningEffort,
    capability: ReasoningCapability,
    protocol: ReasoningProtocol,
    model: String,
    onChange: (ReasoningEffort) -> Unit,
    enabled: Boolean
) {
    var showSheet by remember { mutableStateOf(false) }
    // 与实际请求同源：这里算出的「实际生效档位」就是适配器接下来会发出的档位。
    val plan = remember(effort, capability, protocol, model) {
        ReasoningResolver.plan(protocol, model, effort.apiValue, capability)
    }
    // rikkahub 固定 7 档：关闭/自动/低/中/高/超高/最大（自动=开思考但不指定强度，服务端用模型默认）。
    val sliderLevels = listOf(
        ReasoningEffort.NONE,
        ReasoningEffort.AUTO,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX
    )
    val sliderLabels = reasoningLevelLabels(sliderLevels)
    val sliderCurrentIndex = remember(effort, capability, sliderLevels) {
        sliderLevels.indexOf(effort).takeIf { it >= 0 }
            ?: sliderLevels.indexOfFirst { it == capability.defaultLevel }.takeIf { it >= 0 }
            ?: sliderLevels.indexOf(ReasoningEffort.AUTO).coerceAtLeast(0)
    }
    // 模型思考恒开时无「关闭」档（滑块不把关闭当粒子关闭判定）。
    val sliderClosedIndex = if (capability.canDisable) sliderLevels.indexOf(ReasoningEffort.NONE) else -1
    val thinkingOn = effort != ReasoningEffort.NONE
    // 闪电颜色：开启=蓝色，关闭=可见灰（onSurfaceVariant，像其他图标一样能看见，不是纯白填充看不见）；禁用半透明。
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        thinkingOn -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 闪电入口：整块可点，点开同一个弹层。
    IconButton(
        onClick = { showSheet = true },
        enabled = enabled,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            FeatherIcons.Zap,
            contentDescription = stringResource(effort.labelRes()),
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(com.aiverse.R.string.chat_reasoning_effort),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (capability.showsThinkingToggle()) {
                        Text(
                            text = stringResource(R.string.chat_reasoning_toggle),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        AppSwitch(
                            checked = thinkingOn,
                            enabled = enabled,
                            onCheckedChange = { on ->
                                onChange(
                                    if (on) {
                                        capability.preferredOnLevel() ?: ReasoningEffort.MEDIUM
                                    } else {
                                        ReasoningEffort.NONE
                                    }
                                )
                            }
                        )
                    }
                }

                ReasoningHintLines(plan = plan, capability = capability)

                // rikkahub 7 档：关闭/自动/低/中/高/超高/最大。左右拖动选档，松手吸附并生效。
                ReasoningSlider(
                    levels = sliderLabels,
                    currentIndex = sliderCurrentIndex,
                    closedIndex = sliderClosedIndex,
                    enabled = enabled,
                    onChange = { idx ->
                        // 选档后不自动关闭：用户手动点击空白处关闭。
                        onChange(sliderLevels[idx])
                    }
                )

                // 免责说明：档位定义参考模型官方文档，实际行为以官方文档为准。
                Text(
                    text = stringResource(R.string.chat_reasoning_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}

/** 弹层顶部的说明行：未识别 / 关不掉 / 被降级 / 端点可能忽略，各自只出现一条。 */
@Composable
private fun ReasoningHintLines(plan: ReasoningPlan, capability: ReasoningCapability) {
    if (!capability.isKnown) {
        ReasoningHint(stringResource(R.string.chat_reasoning_unknown_hint))
    }
    when (plan.reason) {
        ReasoningPlan.Reason.CANNOT_DISABLE ->
            ReasoningHint(stringResource(R.string.chat_reasoning_cannot_disable))
        ReasoningPlan.Reason.ENDPOINT_MAY_IGNORE ->
            ReasoningHint(stringResource(R.string.chat_reasoning_endpoint_weak))
        ReasoningPlan.Reason.CLAMPED_DOWN, ReasoningPlan.Reason.CLAMPED_UP ->
            plan.effectiveLevel?.let {
                ReasoningHint(stringResource(R.string.chat_reasoning_effective, it.withApiLabel()))
            }
        else -> Unit
    }
}

@Composable
private fun ReasoningHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm)
    )
}

/** 档位显示名：中文 + 对应接口值，便于对照模型文档与报错文案（如「最高（max）」）。 */
@Composable
private fun ReasoningEffort.withApiLabel(): String =
    stringResource(R.string.common_label_with_api, stringResource(labelRes()), apiValue)

private fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.NONE -> com.aiverse.R.string.chat_reasoning_effort_none
    ReasoningEffort.AUTO -> com.aiverse.R.string.chat_reasoning_effort_auto
    ReasoningEffort.MINIMAL -> com.aiverse.R.string.chat_reasoning_effort_minimal
    ReasoningEffort.LOW -> com.aiverse.R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> com.aiverse.R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> com.aiverse.R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> com.aiverse.R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> com.aiverse.R.string.chat_reasoning_effort_max
}

/** 滑块档位名称（纯中文，不含接口值）：Composable 包装以便逐个 stringResource。 */
@Composable
private fun reasoningLevelLabels(levels: List<ReasoningEffort>): List<String> {
    val out = ArrayList<String>(levels.size)
    for (l in levels) out.add(stringResource(l.labelRes()))
    return out
}

/**
 * 模型选择弹窗：复用设置页默认模型的 [ModelSelectionSheet]（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 传入 [runModelTest] 时每个模型行提供「测试」按钮（不显示选中小勾）。测试态与结果按 provider+模型
 * 在弹层内持有：跨 provider 的同名模型不会串结果，弹层关闭随作用域取消在途请求。
 */
@Composable
internal fun ModelSheet(
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
    runModelTest: (suspend (AIProviderConfig, String) -> ModelTestResult)? = null
) {
    val testScope = rememberCoroutineScope()
    var testingModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var testResults by remember { mutableStateOf<Map<String, ModelTestResult>>(emptyMap()) }

    ModelSelectionSheet(
        title = stringResource(R.string.common_model),
        noModelsText = stringResource(R.string.chat_no_models_hint),
        providers = providers,
        currentProviderId = currentProviderId,
        currentModel = currentModel,
        modelMetadata = modelMetadata,
        onSelect = onSelect,
        onClear = null,
        onDismiss = onDismiss,
        onTestModel = runModelTest?.let { runner ->
            { provider, model ->
                val key = modelMetadataKey(provider.id, model)
                testingModels = testingModels + key
                testResults = testResults - key
                testScope.launch {
                    val result = runner(provider, model)
                    testResults = testResults + (key to result)
                    testingModels = testingModels - key
                }
            }
        },
        testingModels = testingModels,
        testResults = testResults
    )
}
