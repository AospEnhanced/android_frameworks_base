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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

final class UserData {

    private static SparseArray<UserData> sUserData;

    @GuardedBy("ImfLock.class")
    private static InputMethodBindingController.Creator sBindingControllerCreator;

    @UserIdInt
    final int mUserId;

    @GuardedBy("ImfLock.class")
    final Sequence mSequence = new Sequence();

    @NonNull
    final InputMethodBindingController mBindingController;

    /**
     * Not intended to be instantiated.
     */
    private UserData(int userId,
            InputMethodBindingController bindingController) {
        mUserId = userId;
        mBindingController = bindingController;
    }

    @GuardedBy("ImfLock.class")
    static UserData getOrCreate(@UserIdInt int userId) {
        UserData userData = sUserData.get(userId);
        if (userData == null) {
            userData = new UserData(userId, sBindingControllerCreator.create());
            sUserData.put(userId, userData);
        }
        return userData;
    }

    @GuardedBy("ImfLock.class")
    static void initialize(Handler handler,
            InputMethodBindingController.Creator bindingControllerCreator) {
        sUserData = new SparseArray<>();
        sBindingControllerCreator = bindingControllerCreator;
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        userManagerInternal.addUserLifecycleListener(
                new UserManagerInternal.UserLifecycleListener() {
                    @Override
                    public void onUserRemoved(UserInfo user) {
                        final int userId = user.id;
                        handler.post(() -> {
                            synchronized (ImfLock.class) {
                                sUserData.remove(userId);
                            }
                        });
                    }

                    @Override
                    public void onUserCreated(UserInfo user, Object unusedToken) {
                        final int userId = user.id;
                        handler.post(() -> {
                            synchronized (ImfLock.class) {
                                getOrCreate(userId);
                            }
                        });
                    }
                });
        synchronized (ImfLock.class) {
            for (int userId : userManagerInternal.getUserIds()) {
                getOrCreate(userId);
            }
        }
    }
}
