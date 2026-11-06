package com.aiverse.feature.onboarding.domain

import com.aiverse.feature.onboarding.data.OnboardingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateHolderTest {

    private fun holder(): OnboardingStateHolder = OnboardingStateHolder()

    @Test
    fun `start activates from OPEN_TERMINAL`() {
        val h = holder()
        h.start()
        assertTrue(h.isActive)
        assertEquals(OnboardingStep.OPEN_TERMINAL, h.state.value.step)
        assertEquals(OnboardingStatus.IN_PROGRESS, h.state.value.status)
    }

    @Test
    fun `nextStep progresses through full flow`() {
        val h = holder()
        h.start()
        // 从第一步（OPEN_TERMINAL）开始，依次推进所有步骤
        for (step in OnboardingStep.all) {
            assertEquals(step, h.state.value.step)
            h.nextStep()
        }
        // 最后一步 nextStep 后完成
        assertFalse(h.isActive)
        assertEquals(OnboardingStatus.COMPLETED, h.state.value.status)
    }

    @Test
    fun `goToStep changes current step when active`() {
        val h = holder()
        h.start()
        h.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
        assertEquals(OnboardingStep.PROVIDER_FETCH_MODELS, h.state.value.step)

        h.goToStep(OnboardingStep.SIMULATE_FETCH_DIALOG)
        assertEquals(OnboardingStep.SIMULATE_FETCH_DIALOG, h.state.value.step)

        // 模拟关闭弹窗回退
        h.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
        assertEquals(OnboardingStep.PROVIDER_FETCH_MODELS, h.state.value.step)
        assertTrue(h.isActive)
    }

    @Test
    fun `skip deactivates and marks skipped`() {
        val h = holder()
        h.start()
        h.skip()
        assertFalse(h.isActive)
        assertEquals(OnboardingStatus.SKIPPED, h.state.value.status)
    }

    @Test
    fun `completeNow marks completed immediately`() {
        val h = holder()
        h.start()
        h.completeNow()
        assertFalse(h.isActive)
        assertEquals(OnboardingStatus.COMPLETED, h.state.value.status)
    }

    @Test
    fun `nextStep does nothing when inactive`() {
        val h = holder()
        h.nextStep()
        assertFalse(h.isActive)
    }
}
