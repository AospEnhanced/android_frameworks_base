/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "CanvasOpRasterizer.h"

#include <SkCanvas.h>
#include <log/log.h>

#include <vector>

#include "CanvasOpBuffer.h"
#include "CanvasOps.h"

namespace android::uirenderer {

void rasterizeCanvasBuffer(const CanvasOpBuffer& source, SkCanvas* destination) {
    // Tracks the global transform from the current display list back toward the display space
    // Push on beginning a RenderNode draw, pop on ending one
    std::vector<SkMatrix> globalMatrixStack;
    SkMatrix& currentGlobalTransform = globalMatrixStack.emplace_back(SkMatrix::I());

    source.for_each([&]<CanvasOpType T>(CanvasOpContainer<T> * op) {
        if constexpr (T == CanvasOpType::BeginZ || T == CanvasOpType::EndZ) {
            // Do beginZ or endZ
            LOG_ALWAYS_FATAL("TODO");
            return;
        } else {
            // Generic OP
            // First apply the current transformation
            destination->setMatrix(SkMatrix::Concat(currentGlobalTransform, op->transform()));
            // Now draw it
            (*op)->draw(destination);
        }
    });
}

}  // namespace android::uirenderer
