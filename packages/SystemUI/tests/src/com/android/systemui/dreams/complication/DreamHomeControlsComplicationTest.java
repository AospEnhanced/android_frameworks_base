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

package com.android.systemui.dreams.complication;

import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_HOME_CONTROLS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.controls.ControlsServiceInfo;
import com.android.systemui.controls.controller.ControlsController;
import com.android.systemui.controls.controller.StructureInfo;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.management.ControlsListingController;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.complication.dagger.DreamHomeControlsComplicationComponent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamHomeControlsComplicationTest extends SysuiTestCase {
    @Mock
    private DreamHomeControlsComplication mComplication;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private Context mContext;

    @Mock
    private ControlsComponent mControlsComponent;

    @Mock
    private ControlsController mControlsController;

    @Mock
    private ControlsListingController mControlsListingController;

    @Mock
    private DreamHomeControlsComplicationComponent.Factory mComponentFactory;

    @Captor
    private ArgumentCaptor<ControlsListingController.ControlsListingCallback> mCallbackCaptor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getString(anyInt())).thenReturn("");
        when(mControlsComponent.getControlsController()).thenReturn(
                Optional.of(mControlsController));
        when(mControlsComponent.getControlsListingController()).thenReturn(
                Optional.of(mControlsListingController));
        when(mControlsComponent.getVisibility()).thenReturn(AVAILABLE);
    }

    @Test
    public void complicationType() {
        final DreamHomeControlsComplication complication =
                new DreamHomeControlsComplication(mComponentFactory);
        assertThat(complication.getRequiredTypeAvailability()).isEqualTo(
                COMPLICATION_TYPE_HOME_CONTROLS);
    }

    @Test
    public void complicationAvailability_serviceNotAvailable_noFavorites_doNotAddComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mContext, mComplication,
                        mDreamOverlayStateController, mControlsComponent);
        registrant.start();

        setHaveFavorites(false);
        setServiceAvailable(false);

        verify(mDreamOverlayStateController, never()).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_serviceAvailable_noFavorites_doNotAddComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mContext, mComplication,
                        mDreamOverlayStateController, mControlsComponent);
        registrant.start();

        setHaveFavorites(false);
        setServiceAvailable(true);

        verify(mDreamOverlayStateController, never()).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_serviceNotAvailable_haveFavorites_doNotAddComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mContext, mComplication,
                        mDreamOverlayStateController, mControlsComponent);
        registrant.start();

        setHaveFavorites(true);
        setServiceAvailable(false);

        verify(mDreamOverlayStateController, never()).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_serviceAvailable_haveFavorites_addComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mContext, mComplication,
                        mDreamOverlayStateController, mControlsComponent);
        registrant.start();

        setHaveFavorites(true);
        setServiceAvailable(true);

        verify(mDreamOverlayStateController).addComplication(mComplication);
    }

    private void setHaveFavorites(boolean value) {
        final List<StructureInfo> favorites = mock(List.class);
        when(favorites.isEmpty()).thenReturn(!value);
        when(mControlsController.getFavorites()).thenReturn(favorites);
    }

    private void setServiceAvailable(boolean value) {
        final List<ControlsServiceInfo> serviceInfos = mock(List.class);
        when(serviceInfos.isEmpty()).thenReturn(!value);
        triggerControlsListingCallback(serviceInfos);
    }

    private void triggerControlsListingCallback(List<ControlsServiceInfo> serviceInfos) {
        verify(mControlsListingController).addCallback(mCallbackCaptor.capture());
        mCallbackCaptor.getValue().onServicesUpdated(serviceInfos);
    }
}
