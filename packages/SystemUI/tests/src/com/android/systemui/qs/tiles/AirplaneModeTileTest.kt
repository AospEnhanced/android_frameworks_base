/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs.tiles

import android.net.ConnectivityManager
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.GlobalSettings
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class AirplaneModeTileTest : SysuiTestCase() {
    @Mock
    private lateinit var mHost: QSHost
    @Mock
    private lateinit var mMetricsLogger: MetricsLogger
    @Mock
    private lateinit var mStatusBarStateController: StatusBarStateController
    @Mock
    private lateinit var mActivityStarter: ActivityStarter
    @Mock
    private lateinit var mQsLogger: QSLogger
    @Mock
    private lateinit var mBroadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var mConnectivityManager: Lazy<ConnectivityManager>
    @Mock
    private lateinit var mGlobalSettings: GlobalSettings
    @Mock
    private lateinit var mUserTracker: UserTracker
    private lateinit var mTestableLooper: TestableLooper
    private lateinit var mTile: AirplaneModeTile

    private val mUiEventLogger: UiEventLogger = UiEventLoggerFake()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mTestableLooper = TestableLooper.get(this)
        Mockito.`when`(mHost.context).thenReturn(mContext)
        Mockito.`when`(mHost.uiEventLogger).thenReturn(mUiEventLogger)
        Mockito.`when`(mHost.userContext).thenReturn(mContext)

        mTile = AirplaneModeTile(mHost,
            mTestableLooper.looper,
            Handler(mTestableLooper.looper),
            FalsingManagerFake(),
            mMetricsLogger,
            mStatusBarStateController,
            mActivityStarter,
            mQsLogger,
            mBroadcastDispatcher,
            mConnectivityManager,
            mGlobalSettings,
            mUserTracker)
    }

    @After
    fun tearDown() {
        mTile.destroy()
        mTestableLooper.processAllMessages()
    }

    @Test
    fun testIcon_whenDisabled_showsOffState() {
        val state = QSTile.BooleanState()

        mTile.handleUpdateState(state, 0)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_airplane_icon_off))
    }

    @Test
    fun testIcon_whenEnabled_showsOnState() {
        val state = QSTile.BooleanState()

        mTile.handleUpdateState(state, 1)

        assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_airplane_icon_on))
    }
}
