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

#ifndef ANDROIDFW_ERRORS_H_
#define ANDROIDFW_ERRORS_H_

#include <optional>
#include <variant>

#include <android-base/result.h>

namespace android {

enum class IOError {
  // Used when reading a file residing on an IncFs file-system times out.
  PAGES_MISSING = -1,
};

// Represents an absent result or an I/O error.
using NullOrIOError = std::variant<std::nullopt_t, IOError>;

// Checks whether the result holds an unexpected I/O error.
template <typename T>
static inline bool IsIOError(const base::expected<T, NullOrIOError>& result) {
  return !result.has_value() && std::holds_alternative<IOError>(result.error());
}

static inline IOError GetIOError(const NullOrIOError& error) {
  return std::get<IOError>(error);
}

} // namespace android

#endif //ANDROIDFW_ERRORS_H_
