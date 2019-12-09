/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.pm;

import android.content.pm.PackageParser;
import android.content.pm.parsing.ParsedPackage;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests for {@link ParallelPackageParser}
 */
@RunWith(AndroidJUnit4.class)
public class ParallelPackageParserTest {
    private static final String TAG = ParallelPackageParserTest.class.getSimpleName();

    private ParallelPackageParser mParser;

    @Before
    public void setUp() {
        mParser = new TestParallelPackageParser();
    }

    @Test(timeout = 1000)
    public void test() {
        Set<File> submittedFiles = new HashSet<>();
        int fileCount = 15;
        for (int i = 0; i < fileCount; i++) {
            File file = new File("f" + i);
            mParser.submit(file, 0);
            submittedFiles.add(file);
            Log.d(TAG, "submitting " + file);
        }
        for (int i = 0; i < fileCount; i++) {
            ParallelPackageParser.ParseResult result = mParser.take();
            Assert.assertNotNull(result);
            File parsedFile = result.scanFile;
            Log.d(TAG, "took " + parsedFile);
            Assert.assertNotNull(parsedFile);
            boolean removeSuccessful = submittedFiles.remove(parsedFile);
            Assert.assertTrue("Unexpected file " + parsedFile + ". Expected submitted files: "
                    + submittedFiles, removeSuccessful);
        }
    }

    class TestParallelPackageParser extends ParallelPackageParser {

        TestParallelPackageParser() {
            super(null, false, null, null, null);
        }

        @Override
        protected ParsedPackage parsePackage(PackageParser packageParser, File scanFile,
                int parseFlags) throws PackageParser.PackageParserException {
            // Do not actually parse the package for testing
            return null;
        }
    }
}
