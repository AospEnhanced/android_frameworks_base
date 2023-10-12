/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel as DomainLayerAuthenticationMethodModel
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.ui.viewmodel.KeyguardLongPressViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.model.SysUiState
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.SceneTestUtils.Companion.toDataLayer
import com.android.systemui.scene.domain.startable.SceneContainerStartable
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeSceneViewModel
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test cases for the Scene Framework.
 *
 * **Principles**
 * * All test cases here should be done from the perspective of the view-models of the system.
 * * Focus on happy paths, let smaller unit tests focus on failure cases.
 * * These are _integration_ tests and, as such, are larger and harder to maintain than unit tests.
 *   Therefore, when adding or modifying test cases, consider whether what you're testing is better
 *   covered by a more granular unit test.
 * * Please reuse the helper methods in this class (for example, [putDeviceToSleep] or
 *   [emulateUserDrivenTransition]).
 * * All tests start with the device locked and with a PIN auth method. The class offers useful
 *   methods like [setAuthMethod], [unlockDevice], [lockDevice], etc. to help you set up a starting
 *   state that makes more sense for your test case.
 * * All helper methods in this class make assertions that are meant to make sure that they're only
 *   being used when the state is as required (e.g. cannot unlock an already unlocked device, cannot
 *   put to sleep a device that's already asleep, etc.).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class SceneFrameworkIntegrationTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneContainerConfig = utils.fakeSceneContainerConfig()
    private val sceneRepository =
        utils.fakeSceneContainerRepository(
            containerConfig = sceneContainerConfig,
        )
    private val sceneInteractor =
        utils.sceneInteractor(
            repository = sceneRepository,
        )
    private val authenticationInteractor = utils.authenticationInteractor()
    private val deviceEntryInteractor =
        utils.deviceEntryInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val communalInteractor = utils.communalInteractor()

    private val transitionState =
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(sceneContainerConfig.initialSceneKey)
        )
    private val sceneContainerViewModel =
        SceneContainerViewModel(
                sceneInteractor = sceneInteractor,
                falsingInteractor = utils.falsingInteractor(),
            )
            .apply { setTransitionState(transitionState) }

    private val bouncerInteractor =
        utils.bouncerInteractor(
            deviceEntryInteractor = deviceEntryInteractor,
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val bouncerViewModel =
        utils.bouncerViewModel(
            bouncerInteractor = bouncerInteractor,
            authenticationInteractor = authenticationInteractor,
        )

    private val lockscreenSceneViewModel =
        LockscreenSceneViewModel(
            applicationScope = testScope.backgroundScope,
            deviceEntryInteractor = deviceEntryInteractor,
            communalInteractor = communalInteractor,
            longPress =
                KeyguardLongPressViewModel(
                    interactor = mock(),
                ),
        )

    private val mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())

    private var mobileIconsViewModel: MobileIconsViewModel =
        MobileIconsViewModel(
            logger = mock(),
            verboseLogger = mock(),
            interactor = mobileIconsInteractor,
            airplaneModeInteractor =
                AirplaneModeInteractor(
                    FakeAirplaneModeRepository(),
                    FakeConnectivityRepository(),
                ),
            constants = mock(),
            scope = testScope.backgroundScope,
        )

    private lateinit var shadeHeaderViewModel: ShadeHeaderViewModel
    private lateinit var shadeSceneViewModel: ShadeSceneViewModel

    private val keyguardRepository = utils.keyguardRepository
    private val keyguardInteractor =
        utils.keyguardInteractor(
            repository = keyguardRepository,
        )
    private val powerInteractor = PowerInteractorFactory.create().powerInteractor

    private var bouncerSceneJob: Job? = null

    @Before
    fun setUp() {
        shadeHeaderViewModel =
            ShadeHeaderViewModel(
                applicationScope = testScope.backgroundScope,
                context = context,
                sceneInteractor = sceneInteractor,
                mobileIconsInteractor = mobileIconsInteractor,
                mobileIconsViewModel = mobileIconsViewModel,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )

        shadeSceneViewModel =
            ShadeSceneViewModel(
                applicationScope = testScope.backgroundScope,
                deviceEntryInteractor = deviceEntryInteractor,
                bouncerInteractor = bouncerInteractor,
                shadeHeaderViewModel = shadeHeaderViewModel,
            )

        utils.deviceEntryRepository.setUnlocked(false)

        val displayTracker = FakeDisplayTracker(context)
        val sysUiState = SysUiState(displayTracker)
        val startable =
            SceneContainerStartable(
                applicationScope = testScope.backgroundScope,
                sceneInteractor = sceneInteractor,
                deviceEntryInteractor = deviceEntryInteractor,
                authenticationInteractor = authenticationInteractor,
                keyguardInteractor = keyguardInteractor,
                flags = utils.sceneContainerFlags,
                sysUiState = sysUiState,
                displayId = displayTracker.defaultDisplayId,
                sceneLogger = mock(),
                falsingCollector = utils.falsingCollector(),
                powerInteractor = powerInteractor,
            )
        startable.start()

        assertWithMessage("Initial scene key mismatch!")
            .that(sceneContainerViewModel.currentScene.value.key)
            .isEqualTo(sceneContainerConfig.initialSceneKey)
        assertWithMessage("Initial scene container visibility mismatch!")
            .that(sceneContainerViewModel.isVisible.value)
            .isTrue()
    }

    @Test
    fun clickLockButtonAndEnterCorrectPin_unlocksDevice() =
        testScope.runTest {
            emulateUserDrivenTransition(SceneKey.Bouncer)

            enterPin()
            assertCurrentScene(SceneKey.Gone)
            emulateUiSceneTransition(
                expectedVisible = false,
            )
        }

    @Test
    fun swipeUpOnLockscreen_enterCorrectPin_unlocksDevice() =
        testScope.runTest {
            val upDestinationSceneKey by
                collectLastValue(lockscreenSceneViewModel.upDestinationSceneKey)
            assertThat(upDestinationSceneKey).isEqualTo(SceneKey.Bouncer)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )

            enterPin()
            assertCurrentScene(SceneKey.Gone)
            emulateUiSceneTransition(
                expectedVisible = false,
            )
        }

    @Test
    fun swipeUpOnLockscreen_withAuthMethodSwipe_dismissesLockscreen() =
        testScope.runTest {
            setAuthMethod(DomainLayerAuthenticationMethodModel.Swipe)

            val upDestinationSceneKey by
                collectLastValue(lockscreenSceneViewModel.upDestinationSceneKey)
            assertThat(upDestinationSceneKey).isEqualTo(SceneKey.Gone)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )
        }

    @Test
    fun swipeUpOnShadeScene_withAuthMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val upDestinationSceneKey by collectLastValue(shadeSceneViewModel.upDestinationSceneKey)
            setAuthMethod(DomainLayerAuthenticationMethodModel.Swipe)
            assertCurrentScene(SceneKey.Lockscreen)

            // Emulate a user swipe to the shade scene.
            emulateUserDrivenTransition(to = SceneKey.Shade)
            assertCurrentScene(SceneKey.Shade)

            assertThat(upDestinationSceneKey).isEqualTo(SceneKey.Lockscreen)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )
        }

    @Test
    fun swipeUpOnShadeScene_withAuthMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val upDestinationSceneKey by collectLastValue(shadeSceneViewModel.upDestinationSceneKey)
            setAuthMethod(DomainLayerAuthenticationMethodModel.Swipe)
            assertCurrentScene(SceneKey.Lockscreen)

            // Emulate a user swipe to dismiss the lockscreen.
            emulateUserDrivenTransition(to = SceneKey.Gone)
            assertCurrentScene(SceneKey.Gone)

            // Emulate a user swipe to the shade scene.
            emulateUserDrivenTransition(to = SceneKey.Shade)
            assertCurrentScene(SceneKey.Shade)

            assertThat(upDestinationSceneKey).isEqualTo(SceneKey.Gone)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )
        }

    @Test
    fun withAuthMethodNone_deviceWakeUp_skipsLockscreen() =
        testScope.runTest {
            setAuthMethod(DomainLayerAuthenticationMethodModel.None)
            putDeviceToSleep(instantlyLockDevice = false)
            assertCurrentScene(SceneKey.Lockscreen)

            wakeUpDevice()
            assertCurrentScene(SceneKey.Gone)
        }

    @Test
    fun withAuthMethodSwipe_deviceWakeUp_doesNotSkipLockscreen() =
        testScope.runTest {
            setAuthMethod(DomainLayerAuthenticationMethodModel.Swipe)
            putDeviceToSleep(instantlyLockDevice = false)
            assertCurrentScene(SceneKey.Lockscreen)

            wakeUpDevice()
            assertCurrentScene(SceneKey.Lockscreen)
        }

    @Test
    fun deviceGoesToSleep_switchesToLockscreen() =
        testScope.runTest {
            unlockDevice()
            assertCurrentScene(SceneKey.Gone)

            putDeviceToSleep()
            assertCurrentScene(SceneKey.Lockscreen)
        }

    @Test
    fun deviceGoesToSleep_wakeUp_unlock() =
        testScope.runTest {
            unlockDevice()
            assertCurrentScene(SceneKey.Gone)
            putDeviceToSleep()
            assertCurrentScene(SceneKey.Lockscreen)
            wakeUpDevice()
            assertCurrentScene(SceneKey.Lockscreen)

            unlockDevice()
            assertCurrentScene(SceneKey.Gone)
        }

    @Test
    fun deviceWakesUpWhileUnlocked_dismissesLockscreen() =
        testScope.runTest {
            unlockDevice()
            assertCurrentScene(SceneKey.Gone)
            putDeviceToSleep(instantlyLockDevice = false)
            assertCurrentScene(SceneKey.Lockscreen)
            wakeUpDevice()
            assertCurrentScene(SceneKey.Gone)
        }

    @Test
    fun swipeUpOnLockscreenWhileUnlocked_dismissesLockscreen() =
        testScope.runTest {
            unlockDevice()
            val upDestinationSceneKey by
                collectLastValue(lockscreenSceneViewModel.upDestinationSceneKey)
            assertThat(upDestinationSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun deviceGoesToSleep_withLockTimeout_staysOnLockscreen() =
        testScope.runTest {
            unlockDevice()
            assertCurrentScene(SceneKey.Gone)
            putDeviceToSleep(instantlyLockDevice = false)
            assertCurrentScene(SceneKey.Lockscreen)

            // Pretend like the timeout elapsed and now lock the device.
            lockDevice()
            assertCurrentScene(SceneKey.Lockscreen)
        }

    @Test
    fun dismissingIme_whileOnPasswordBouncer_navigatesToLockscreen() =
        testScope.runTest {
            setAuthMethod(DomainLayerAuthenticationMethodModel.Password)
            val upDestinationSceneKey by
                collectLastValue(lockscreenSceneViewModel.upDestinationSceneKey)
            assertThat(upDestinationSceneKey).isEqualTo(SceneKey.Bouncer)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )

            dismissIme()

            assertCurrentScene(SceneKey.Lockscreen)
            emulateUiSceneTransition()
        }

    /**
     * Asserts that the current scene in the view-model matches what's expected.
     *
     * Note that this doesn't assert what the current scene is in the UI.
     */
    private fun TestScope.assertCurrentScene(expected: SceneKey) {
        runCurrent()
        assertWithMessage("Current scene mismatch!")
            .that(sceneContainerViewModel.currentScene.value.key)
            .isEqualTo(expected)
    }

    /**
     * Returns the [SceneKey] of the current scene as displayed in the UI.
     *
     * This can be different than the value in [SceneContainerViewModel.currentScene], by design, as
     * the UI must gradually transition between scenes.
     */
    private fun getCurrentSceneInUi(): SceneKey {
        return when (val state = transitionState.value) {
            is ObservableTransitionState.Idle -> state.scene
            is ObservableTransitionState.Transition -> state.fromScene
        }
    }

    /** Updates the current authentication method and related states in the data layer. */
    private fun TestScope.setAuthMethod(
        authMethod: DomainLayerAuthenticationMethodModel,
    ) {
        // Set the lockscreen enabled bit _before_ set the auth method as the code picks up on the
        // lockscreen enabled bit _after_ the auth method is changed and the lockscreen enabled bit
        // is not an observable that can trigger a new evaluation.
        utils.deviceEntryRepository.setInsecureLockscreenEnabled(
            authMethod !is DomainLayerAuthenticationMethodModel.None
        )
        utils.authenticationRepository.setAuthenticationMethod(authMethod.toDataLayer())
        if (!authMethod.isSecure) {
            // When the auth method is not secure, the device is never considered locked.
            utils.deviceEntryRepository.setUnlocked(true)
        }
        runCurrent()
    }

    /**
     * Emulates a complete transition in the UI from whatever the current scene is in the UI to
     * whatever the current scene should be, based on the value in
     * [SceneContainerViewModel.onSceneChanged].
     *
     * This should post a series of values into [transitionState] to emulate a gradual scene
     * transition and culminate with a call to [SceneContainerViewModel.onSceneChanged].
     *
     * The method asserts that a transition is actually required. E.g. it will fail if the current
     * scene in [transitionState] is already caught up with the scene in
     * [SceneContainerViewModel.currentScene].
     *
     * @param expectedVisible Whether [SceneContainerViewModel.isVisible] should be set at the end
     *   of the UI transition.
     */
    private fun TestScope.emulateUiSceneTransition(
        expectedVisible: Boolean = true,
    ) {
        val to = sceneContainerViewModel.currentScene.value
        val from = getCurrentSceneInUi()
        assertWithMessage("Cannot transition to ${to.key} as the UI is already on that scene!")
            .that(to.key)
            .isNotEqualTo(from)

        // Begin to transition.
        val progressFlow = MutableStateFlow(0f)
        transitionState.value =
            ObservableTransitionState.Transition(
                fromScene = getCurrentSceneInUi(),
                toScene = to.key,
                progress = progressFlow,
                isUserInputDriven = false,
            )
        runCurrent()

        // Report progress of transition.
        while (progressFlow.value < 1f) {
            progressFlow.value += 0.2f
            runCurrent()
        }

        // End the transition and report the change.
        transitionState.value = ObservableTransitionState.Idle(to.key)

        sceneContainerViewModel.onSceneChanged(to)
        runCurrent()

        assertWithMessage("Visibility mismatch after scene transition from $from to ${to.key}!")
            .that(sceneContainerViewModel.isVisible.value)
            .isEqualTo(expectedVisible)

        bouncerSceneJob =
            if (to.key == SceneKey.Bouncer) {
                testScope.backgroundScope.launch {
                    bouncerViewModel.authMethodViewModel.collect {
                        // Do nothing. Need this to turn this otherwise cold flow, hot.
                    }
                }
            } else {
                bouncerSceneJob?.cancel()
                null
            }
        runCurrent()
    }

    /**
     * Emulates a fire-and-forget user action (a fling or back, not a pointer-tracking swipe) that
     * causes a scene change to the [to] scene.
     *
     * This also includes the emulation of the resulting UI transition that culminates with the UI
     * catching up with the requested scene change (see [emulateUiSceneTransition]).
     *
     * @param to The scene to transition to.
     */
    private fun TestScope.emulateUserDrivenTransition(
        to: SceneKey?,
    ) {
        checkNotNull(to)

        sceneInteractor.changeScene(SceneModel(to), "reason")
        assertThat(sceneContainerViewModel.currentScene.value.key).isEqualTo(to)

        emulateUiSceneTransition(
            expectedVisible = to != SceneKey.Gone,
        )
    }

    /**
     * Locks the device immediately (without delay).
     *
     * Asserts the device to be lockable (e.g. that the current authentication is secure).
     *
     * Not to be confused with [putDeviceToSleep], which may also instantly lock the device.
     */
    private suspend fun TestScope.lockDevice() {
        val authMethod = authenticationInteractor.getAuthenticationMethod()
        assertWithMessage("The authentication method of $authMethod is not secure, cannot lock!")
            .that(authMethod.isSecure)
            .isTrue()

        utils.deviceEntryRepository.setUnlocked(false)
        runCurrent()
    }

    /** Unlocks the device by entering the correct PIN. Ends up in the Gone scene. */
    private fun TestScope.unlockDevice() {
        assertWithMessage("Cannot unlock a device that's already unlocked!")
            .that(deviceEntryInteractor.isUnlocked.value)
            .isFalse()

        emulateUserDrivenTransition(SceneKey.Bouncer)
        enterPin()
        emulateUiSceneTransition(
            expectedVisible = false,
        )
    }

    /**
     * Enters the correct PIN in the bouncer UI.
     *
     * Asserts that the current scene is [SceneKey.Bouncer] and that the current bouncer UI is a PIN
     * before proceeding.
     *
     * Does not assert that the device is locked or unlocked.
     */
    private fun TestScope.enterPin() {
        assertWithMessage("Cannot enter PIN when not on the Bouncer scene!")
            .that(getCurrentSceneInUi())
            .isEqualTo(SceneKey.Bouncer)
        val authMethodViewModel by collectLastValue(bouncerViewModel.authMethodViewModel)
        assertWithMessage("Cannot enter PIN when not using a PIN authentication method!")
            .that(authMethodViewModel)
            .isInstanceOf(PinBouncerViewModel::class.java)

        val pinBouncerViewModel = authMethodViewModel as PinBouncerViewModel
        FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
            pinBouncerViewModel.onPinButtonClicked(digit)
        }
        pinBouncerViewModel.onAuthenticateButtonClicked()
        runCurrent()
    }

    /** Changes device wakefulness state from asleep to awake, going through intermediary states. */
    private fun TestScope.wakeUpDevice() {
        val wakefulnessModel = powerInteractor.detailedWakefulness.value
        assertWithMessage("Cannot wake up device as it's already awake!")
            .that(wakefulnessModel.isAwake())
            .isFalse()

        powerInteractor.setAwakeForTest()
        runCurrent()
    }

    /** Changes device wakefulness state from awake to asleep, going through intermediary states. */
    private suspend fun TestScope.putDeviceToSleep(
        instantlyLockDevice: Boolean = true,
    ) {
        val wakefulnessModel = powerInteractor.detailedWakefulness.value
        assertWithMessage("Cannot put device to sleep as it's already asleep!")
            .that(wakefulnessModel.isAwake())
            .isTrue()

        powerInteractor.setAsleepForTest()
        runCurrent()

        if (instantlyLockDevice) {
            lockDevice()
        }
    }

    /** Emulates the dismissal of the IME (soft keyboard). */
    private fun TestScope.dismissIme(
        showImeBeforeDismissing: Boolean = true,
    ) {
        bouncerViewModel.authMethodViewModel.value?.apply {
            if (showImeBeforeDismissing) {
                onImeVisibilityChanged(true)
            }
            onImeVisibilityChanged(false)
            runCurrent()
        }
    }
}
