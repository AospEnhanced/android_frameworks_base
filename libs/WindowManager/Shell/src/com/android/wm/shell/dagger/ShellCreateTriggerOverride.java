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

package com.android.wm.shell.dagger;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Qualifier;

/**
 * An annotation for non-base modules to specifically mark the provider that is triggering the
 * creation of independent shell components that are not created as a part of the dependencies for
 * interfaces passed to SysUI.
 *
 * TODO: This will be removed once we have a more explicit method for specifying components to start
 *       with SysUI
 */
@Documented
@Inherited
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface ShellCreateTriggerOverride {}
