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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

public class MatrixTranslate extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    float mTranslateX, mTranslateY;

    public MatrixTranslate(float translateX, float translateY) {
        mTranslateX = translateX;
        mTranslateY = translateY;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTranslateX, mTranslateY);
    }

    @Override
    public String toString() {
        return "DrawArc " + mTranslateY + ", " + mTranslateY + ";";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float translateX = buffer.readFloat();
            float translateY = buffer.readFloat();
            MatrixTranslate op = new MatrixTranslate(translateX, translateY);
            operations.add(op);
        }

        @Override
        public String name() {
            return "Matrix";
        }

        @Override
        public int id() {
            return Operations.MATRIX_TRANSLATE;
        }

        public void apply(WireBuffer buffer, float translateX, float translateY) {
            buffer.start(Operations.MATRIX_TRANSLATE);
            buffer.writeFloat(translateX);
            buffer.writeFloat(translateY);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.matrixTranslate(mTranslateX, mTranslateY);
    }
}
