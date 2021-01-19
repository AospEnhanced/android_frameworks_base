/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.appsearch.testing;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.GlobalSearchSessionShim;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This test class adapts the AppSearch Framework API to ListenableFuture, so it can be tested via
 * a consistent interface.
 * @hide
 */
public class GlobalSearchSessionShimImpl implements GlobalSearchSessionShim {
    private final GlobalSearchSession mGlobalSearchSession;
    private final ExecutorService mExecutor;

    @NonNull
    public static ListenableFuture<GlobalSearchSessionShim> createGlobalSearchSession() {
        Context context = ApplicationProvider.getApplicationContext();
        AppSearchManager appSearchManager = context.getSystemService(AppSearchManager.class);
        SettableFuture<AppSearchResult<GlobalSearchSession>> future = SettableFuture.create();
        ExecutorService executor = Executors.newCachedThreadPool();
        appSearchManager.createGlobalSearchSession(executor, future::set);
        return Futures.transform(
                future,
                instance -> new GlobalSearchSessionShimImpl(instance.getResultValue(), executor),
                executor);
    }

    private GlobalSearchSessionShimImpl(
            @NonNull GlobalSearchSession session, @NonNull ExecutorService executor) {
        mGlobalSearchSession = Preconditions.checkNotNull(session);
        mExecutor = Preconditions.checkNotNull(executor);

    }

    @NonNull
    @Override
    public SearchResultsShim query(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        SearchResults searchResults =
                mGlobalSearchSession.query(queryExpression, searchSpec, mExecutor);
        return new SearchResultsShimImpl(searchResults, mExecutor);
    }

    @Override
    public void close() {
        mGlobalSearchSession.close();
    }
}
