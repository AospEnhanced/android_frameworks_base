/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.compatui;

import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_DISMISSED;
import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_HIDDEN;
import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED;
import static android.app.TaskInfo.CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.SurfaceControlViewHost;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CompatUILayout}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:CompatUILayoutTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class CompatUILayoutTest extends ShellTestCase {

    private static final int TASK_ID = 1;

    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private CompatUIController.CompatUICallback mCallback;
    @Mock private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock private SurfaceControlViewHost mViewHost;

    private CompatUIWindowManager mWindowManager;
    private CompatUILayout mCompatUILayout;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mWindowManager = new CompatUIWindowManager(mContext, new Configuration(),
                mSyncTransactionQueue, mCallback, TASK_ID, mTaskListener, new DisplayLayout(),
                false /* hasShownSizeCompatHint */, false /* hasShownCameraCompatHint */);

        mCompatUILayout = (CompatUILayout)
                LayoutInflater.from(mContext).inflate(R.layout.compat_ui_layout, null);
        mCompatUILayout.inject(mWindowManager);

        spyOn(mWindowManager);
        spyOn(mCompatUILayout);
        doReturn(mViewHost).when(mWindowManager).createSurfaceViewHost();
        doReturn(mCompatUILayout).when(mWindowManager).inflateCompatUILayout();
    }

    @Test
    public void testOnClickForRestartButton() {
        final ImageButton button = mCompatUILayout.findViewById(R.id.size_compat_restart_button);
        button.performClick();

        verify(mWindowManager).onRestartButtonClicked();
        verify(mCallback).onSizeCompatRestartButtonClicked(TASK_ID);
    }

    @Test
    public void testOnLongClickForRestartButton() {
        doNothing().when(mWindowManager).onRestartButtonLongClicked();

        final ImageButton button = mCompatUILayout.findViewById(R.id.size_compat_restart_button);
        button.performLongClick();

        verify(mWindowManager).onRestartButtonLongClicked();
    }

    @Test
    public void testOnClickForSizeCompatHint() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_HIDDEN);
        final LinearLayout sizeCompatHint = mCompatUILayout.findViewById(R.id.size_compat_hint);
        sizeCompatHint.performClick();

        verify(mCompatUILayout).setSizeCompatHintVisibility(/* show= */ false);
    }

    @Test
    public void testUpdateCameraTreatmentButton_treatmentAppliedByDefault() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);
        final ImageButton button =
                mCompatUILayout.findViewById(R.id.camera_compat_treatment_button);
        button.performClick();

        verify(mWindowManager).onCameraTreatmentButtonClicked();
        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);

        button.performClick();

        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);
    }

    @Test
    public void testUpdateCameraTreatmentButton_treatmentSuggestedByDefault() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
        final ImageButton button =
                mCompatUILayout.findViewById(R.id.camera_compat_treatment_button);
        button.performClick();

        verify(mWindowManager).onCameraTreatmentButtonClicked();
        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED);

        button.performClick();

        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
    }

    @Test
    public void testOnCameraDismissButtonClicked() {
        mWindowManager.createLayout(true /* show */, true /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
        final ImageButton button =
                mCompatUILayout.findViewById(R.id.camera_compat_dismiss_button);
        button.performClick();

        verify(mWindowManager).onCameraDismissButtonClicked();
        verify(mCallback).onCameraControlStateUpdated(
                TASK_ID, CAMERA_COMPAT_CONTROL_DISMISSED);
        verify(mCompatUILayout).setCameraControlVisibility(/* show */ false);
    }

    @Test
    public void testOnLongClickForCameraTreatementButton() {
        doNothing().when(mWindowManager).onCameraButtonLongClicked();

        final ImageButton button =
                mCompatUILayout.findViewById(R.id.camera_compat_treatment_button);
        button.performLongClick();

        verify(mWindowManager).onCameraButtonLongClicked();
    }

    @Test
    public void testOnLongClickForCameraDismissButton() {
        doNothing().when(mWindowManager).onCameraButtonLongClicked();

        final ImageButton button = mCompatUILayout.findViewById(R.id.camera_compat_dismiss_button);
        button.performLongClick();

        verify(mWindowManager).onCameraButtonLongClicked();
    }

    @Test
    public void testOnClickForCameraCompatHint() {
        mWindowManager.createLayout(true /* show */, false /* hasSizeCompat */,
                CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED);
        final LinearLayout hint = mCompatUILayout.findViewById(R.id.camera_compat_hint);
        hint.performClick();

        verify(mCompatUILayout).setCameraCompatHintVisibility(/* show= */ false);
    }
}
