/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.util;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;

@RunWith(AndroidJUnit4.class)
public class SizedInputStreamTest {
    @Test
    public void testSimple() throws Exception {
        final ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{1, 2, 3, 4});
        final SizedInputStream sized = new SizedInputStream(in, 2);
        assertEquals(1, sized.read());
        assertEquals(2, sized.read());
        assertEquals(-1, sized.read());
    }
}
