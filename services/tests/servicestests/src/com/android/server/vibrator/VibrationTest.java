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

package com.android.server.vibrator;

import static com.google.common.truth.Truth.assertThat;

import static java.util.stream.Collectors.toList;

import android.platform.test.annotations.Presubmit;

import org.junit.Test;

import java.util.Arrays;

/**
 * Tests for {@link Vibration}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibrationTest
 */
@Presubmit
public class VibrationTest {

    @Test
    public void status_hasUniqueProtoEnumValues() {
        assertThat(
                Arrays.stream(Vibration.Status.values())
                        .map(Vibration.Status::getProtoEnumValue)
                        .collect(toList()))
                .containsNoDuplicates();
    }
}
