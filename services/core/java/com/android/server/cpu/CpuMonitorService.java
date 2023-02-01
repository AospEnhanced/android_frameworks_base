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

package com.android.server.cpu;

import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;

import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_ALL;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_BACKGROUND;
import static com.android.server.cpu.CpuInfoReader.FLAG_CPUSET_CATEGORY_BACKGROUND;
import static com.android.server.cpu.CpuInfoReader.FLAG_CPUSET_CATEGORY_TOP_APP;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.utils.PriorityDump;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Service to monitor CPU availability and usage. */
public final class CpuMonitorService extends SystemService {
    static final String TAG = CpuMonitorService.class.getSimpleName();
    static final boolean DEBUG =  Log.isLoggable(TAG, Log.DEBUG);
    // TODO(b/267500110): Make these constants resource overlay properties.
    /** Default monitoring interval when no monitoring is in progress. */
    static final long DEFAULT_MONITORING_INTERVAL_MILLISECONDS = -1;
    // TODO(b/242722241): Add a constant for normal monitoring interval when callbacks are
    //  registered.
    /**
     * Monitoring interval when no registered callbacks and the build is either user-debug or eng.
     */
    private static final long DEBUG_MONITORING_INTERVAL_MILLISECONDS = TimeUnit.MINUTES.toMillis(1);
    /**
     * Size of the in-memory cache relative to the current uptime.
     *
     * On user-debug or eng builds, continuously cache stats with a bigger cache size for debugging
     * purposes.
     */
    private static final long CACHE_DURATION_MILLISECONDS = Build.IS_USERDEBUG || Build.IS_ENG
            ? TimeUnit.MINUTES.toMillis(30) : TimeUnit.MINUTES.toMillis(10);
    // TODO(b/267500110): Investigate whether this duration should change when the monitoring
    //  interval is updated. When the CPU is under heavy load, the monitoring will happen less
    //  frequently. Should this duration be increased as well when this happens?
    private static final long LATEST_AVAILABILITY_DURATION_MILLISECONDS =
            TimeUnit.SECONDS.toMillis(30);

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final CpuInfoReader mCpuInfoReader;
    private final boolean mShouldDebugMonitor;
    private final long mDebugMonitoringIntervalMillis;
    private final long mLatestAvailabilityDurationMillis;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArrayMap<CpuMonitorInternal.CpuAvailabilityCallback,
            CpuAvailabilityCallbackInfo> mAvailabilityCallbackInfosByCallbacksByCpuset;
    @GuardedBy("mLock")
    private final SparseArray<CpusetInfo> mCpusetInfosByCpuset;
    private final Runnable mMonitorCpuStats = this::monitorCpuStats;

    @GuardedBy("mLock")
    private long mCurrentMonitoringIntervalMillis = DEFAULT_MONITORING_INTERVAL_MILLISECONDS;
    private Handler mHandler;

    private final CpuMonitorInternal mLocalService = new CpuMonitorInternal() {
        @Override
        public void addCpuAvailabilityCallback(Executor executor,
                CpuAvailabilityMonitoringConfig config, CpuAvailabilityCallback callback) {
            Objects.requireNonNull(callback, "Callback must be non-null");
            Objects.requireNonNull(config, "Config must be non-null");
            synchronized (mLock) {
                // Verify all CPUSET entries before adding the callback because this will help
                // delete any previously added callback for a different CPUSET.
                for (int i = 0; i < mAvailabilityCallbackInfosByCallbacksByCpuset.numMaps(); i++) {
                    int cpuset = mAvailabilityCallbackInfosByCallbacksByCpuset.keyAt(i);
                    CpuAvailabilityCallbackInfo callbackInfo =
                            mAvailabilityCallbackInfosByCallbacksByCpuset.delete(cpuset, callback);
                    if (callbackInfo != null) {
                        Slogf.i(TAG, "Overwriting the existing %s", callbackInfo);
                    }
                }
                CpuAvailabilityCallbackInfo callbackInfo = new CpuAvailabilityCallbackInfo(config,
                        executor);
                mAvailabilityCallbackInfosByCallbacksByCpuset.add(config.cpuset, callback,
                        callbackInfo);
                if (DEBUG) {
                    Slogf.d(TAG, "Added a CPU availability callback: %s", callbackInfo);
                }
            }
            // TODO(b/242722241):
            //  * On the executor or on the handler thread, call the callback with the latest CPU
            //    availability info and monitoring interval.
            //  * Monitor the CPU stats more frequently when the first callback is added.
        }

        @Override
        public void removeCpuAvailabilityCallback(CpuAvailabilityCallback callback) {
            synchronized (mLock) {
                for (int i = 0; i < mAvailabilityCallbackInfosByCallbacksByCpuset.numMaps(); i++) {
                    int cpuset = mAvailabilityCallbackInfosByCallbacksByCpuset.keyAt(i);
                    CpuAvailabilityCallbackInfo callbackInfo =
                            mAvailabilityCallbackInfosByCallbacksByCpuset.delete(cpuset, callback);
                    if (callbackInfo != null) {
                        if (DEBUG) {
                            Slogf.d(TAG, "Successfully removed %s", callbackInfo);
                        }
                        return;
                    }
                }
                Slogf.w(TAG, "CpuAvailabilityCallback was not previously added. Ignoring the remove"
                        + " request");
            }
        }
    };

    public CpuMonitorService(Context context) {
        this(context, new CpuInfoReader(), new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, /* allowIo= */ true),
                Build.IS_USERDEBUG || Build.IS_ENG, DEBUG_MONITORING_INTERVAL_MILLISECONDS,
                LATEST_AVAILABILITY_DURATION_MILLISECONDS);
    }

    @VisibleForTesting
    CpuMonitorService(Context context, CpuInfoReader cpuInfoReader, HandlerThread handlerThread,
            boolean shouldDebugMonitor, long debugMonitoringIntervalMillis,
            long latestAvailabilityDurationMillis) {
        super(context);
        mContext = context;
        mHandlerThread = handlerThread;
        mShouldDebugMonitor = shouldDebugMonitor;
        mDebugMonitoringIntervalMillis = debugMonitoringIntervalMillis;
        mLatestAvailabilityDurationMillis = latestAvailabilityDurationMillis;
        mCpuInfoReader = cpuInfoReader;
        mCpusetInfosByCpuset = new SparseArray<>(2);
        mCpusetInfosByCpuset.append(CPUSET_ALL, new CpusetInfo(CPUSET_ALL));
        mCpusetInfosByCpuset.append(CPUSET_BACKGROUND, new CpusetInfo(CPUSET_BACKGROUND));
        mAvailabilityCallbackInfosByCallbacksByCpuset = new SparseArrayMap<>();
    }

    @Override
    public void onStart() {
        // Initialize CPU info reader and perform the first read to make sure the CPU stats are
        // readable without any issues.
        if (!mCpuInfoReader.init() || mCpuInfoReader.readCpuInfos() == null) {
            Slogf.wtf(TAG, "Failed to initialize CPU info reader. This happens when the CPU "
                    + "frequency stats are not available or the sysfs interface has changed in "
                    + "the Kernel. Cannot monitor CPU without these stats. Terminating CPU monitor "
                    + "service");
            return;
        }
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        publishLocalService(CpuMonitorInternal.class, mLocalService);
        publishBinderService("cpu_monitor", new CpuMonitorBinder(), /* allowIsolated= */ false,
                DUMP_FLAG_PRIORITY_CRITICAL);
        Watchdog.getInstance().addThread(mHandler);
        synchronized (mLock) {
            if (mShouldDebugMonitor && !mHandler.hasCallbacks(mMonitorCpuStats)) {
                mCurrentMonitoringIntervalMillis = mDebugMonitoringIntervalMillis;
                Slogf.i(TAG, "Starting debug monitoring");
                mHandler.post(mMonitorCpuStats);
            }
        }
    }

    private void doDump(IndentingPrintWriter writer) {
        writer.printf("*%s*\n", getClass().getSimpleName());
        writer.increaseIndent();
        mCpuInfoReader.dump(writer);
        synchronized (mLock) {
            writer.printf("Current CPU monitoring interval: %d ms\n",
                    mCurrentMonitoringIntervalMillis);
            if (hasClientCallbacksLocked()) {
                writer.println("CPU availability change callbacks:");
                writer.increaseIndent();
                mAvailabilityCallbackInfosByCallbacksByCpuset.forEach(
                        (callbackInfo) -> writer.printf("%s\n", callbackInfo));
                writer.decreaseIndent();
            }
            if (mCpusetInfosByCpuset.size() > 0) {
                writer.println("Cpuset infos:");
                writer.increaseIndent();
                for (int i = 0; i < mCpusetInfosByCpuset.size(); i++) {
                    writer.printf("%s\n", mCpusetInfosByCpuset.valueAt(i));
                }
                writer.decreaseIndent();
            }
        }
        writer.decreaseIndent();
    }

    private void monitorCpuStats() {
        long uptimeMillis = SystemClock.uptimeMillis();
        SparseArray<CpuInfoReader.CpuInfo> cpuInfosByCoreId = mCpuInfoReader.readCpuInfos();
        if (cpuInfosByCoreId == null) {
            // This shouldn't happen because the CPU infos are read & verified during
            // the {@link onStart} call.
            Slogf.wtf(TAG, "Failed to read CPU info from device");
            synchronized (mLock) {
                stopMonitoringCpuStatsLocked();
            }
            // Monitoring is stopped but no client callback is removed.
            // TODO(b/267500110): Identify whether the clients should be notified about this state.
            return;
        }

        synchronized (mLock) {
            // 1. Populate the {@link mCpusetInfosByCpuset} with the latest cpuInfo.
            for (int i = 0; i < cpuInfosByCoreId.size(); i++) {
                CpuInfoReader.CpuInfo cpuInfo = cpuInfosByCoreId.valueAt(i);
                for (int j = 0; j < mCpusetInfosByCpuset.size(); j++) {
                    mCpusetInfosByCpuset.valueAt(j).appendCpuInfo(uptimeMillis, cpuInfo);
                }
            }

            // 2. Verify whether any monitoring thresholds are crossed and notify the corresponding
            // clients.
            for (int i = 0; i < mCpusetInfosByCpuset.size(); i++) {
                CpusetInfo cpusetInfo = mCpusetInfosByCpuset.valueAt(i);
                cpusetInfo.populateLatestCpuAvailabilityInfo(uptimeMillis,
                        mLatestAvailabilityDurationMillis);
                // TODO(b/242722241): Check CPU availability against thresholds and notify clients.
            }

            // TODO(b/267500110): Detect heavy CPU load. On detecting heavy CPU load, increase
            //  the monitoring interval and notify the clients.

            // 3. Continue monitoring only when either there is at least one registered client
            // callback or debug monitoring is enabled.
            if (mCurrentMonitoringIntervalMillis > 0
                    && (hasClientCallbacksLocked() || mShouldDebugMonitor)) {
                mHandler.postAtTime(mMonitorCpuStats,
                        uptimeMillis + mCurrentMonitoringIntervalMillis);
            } else {
                stopMonitoringCpuStatsLocked();
            }
        }
    }

    @GuardedBy("mLock")
    private boolean hasClientCallbacksLocked() {
        for (int i = 0; i < mAvailabilityCallbackInfosByCallbacksByCpuset.numMaps(); i++) {
            if (mAvailabilityCallbackInfosByCallbacksByCpuset.numElementsForKeyAt(i) > 0) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private void stopMonitoringCpuStatsLocked() {
        mHandler.removeCallbacks(mMonitorCpuStats);
        mCurrentMonitoringIntervalMillis = DEFAULT_MONITORING_INTERVAL_MILLISECONDS;
        // When the monitoring is stopped, the latest CPU availability info and the snapshots in
        // {@code mCpusetInfosByCpuset} will become obsolete soon. So, remove them.
        for (int i = 0; i < mCpusetInfosByCpuset.size(); i++) {
            mCpusetInfosByCpuset.valueAt(i).clear();
        }
    }

    private static boolean containsCpuset(@CpuInfoReader.CpusetCategory int cpusetCategories,
            @CpuAvailabilityMonitoringConfig.Cpuset int expectedCpuset) {
        switch (expectedCpuset) {
            case CPUSET_ALL:
                return (cpusetCategories & FLAG_CPUSET_CATEGORY_TOP_APP) != 0;
            case CPUSET_BACKGROUND:
                return (cpusetCategories & FLAG_CPUSET_CATEGORY_BACKGROUND) != 0;
            default:
                Slogf.wtf(TAG, "Provided invalid expectedCpuset %d", expectedCpuset);
        }
        return false;
    }

    private static final class CpuAvailabilityCallbackInfo {
        public final CpuAvailabilityMonitoringConfig config;
        @Nullable
        public final Executor executor;

        CpuAvailabilityCallbackInfo(CpuAvailabilityMonitoringConfig config,
                @Nullable Executor executor) {
            this.config = config;
            this.executor = executor;
        }

        @Override
        public String toString() {
            return "CpuAvailabilityCallbackInfo{" + "config=" + config + ", mExecutor=" + executor
                    + '}';
        }
    }

    private final class CpuMonitorBinder extends Binder {
        private final PriorityDump.PriorityDumper mPriorityDumper =
                new PriorityDump.PriorityDumper() {
                    @Override
                    public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                            boolean asProto) {
                        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)
                                || asProto) {
                            return;
                        }
                        try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw)) {
                            doDump(ipw);
                        }
                    }
                };

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            PriorityDump.dump(mPriorityDumper, fd, pw, args);
        }
    }

    private static final class CpusetInfo {
        @CpuAvailabilityMonitoringConfig.Cpuset
        public final int cpuset;
        private final LongSparseArray<Snapshot> mSnapshotsByUptime;
        @Nullable
        private CpuAvailabilityInfo mLatestCpuAvailabilityInfo;

        CpusetInfo(int cpuset) {
            this.cpuset = cpuset;
            mSnapshotsByUptime = new LongSparseArray<>();
        }

        public void appendCpuInfo(long uptimeMillis, CpuInfoReader.CpuInfo cpuInfo) {
            if (!containsCpuset(cpuInfo.cpusetCategories, cpuset)) {
                return;
            }
            Snapshot currentSnapshot = mSnapshotsByUptime.get(uptimeMillis);
            if (currentSnapshot == null) {
                currentSnapshot = new Snapshot(uptimeMillis);
                mSnapshotsByUptime.append(uptimeMillis, currentSnapshot);
                if (mSnapshotsByUptime.size() > 0
                        && (uptimeMillis - mSnapshotsByUptime.valueAt(0).uptimeMillis)
                        > CACHE_DURATION_MILLISECONDS) {
                    mSnapshotsByUptime.removeAt(0);
                }
            }
            currentSnapshot.appendCpuInfo(cpuInfo);
        }

        public void populateLatestCpuAvailabilityInfo(long currentUptimeMillis,
                long latestAvailabilityDurationMillis) {
            int numSnapshots = mSnapshotsByUptime.size();
            if (numSnapshots == 0) {
                mLatestCpuAvailabilityInfo = null;
                return;
            }
            Snapshot latestSnapshot = mSnapshotsByUptime.valueAt(numSnapshots - 1);
            if (latestSnapshot.uptimeMillis != currentUptimeMillis) {
                // When the cpuset has no stats available for the current polling, the uptime will
                // mismatch. When this happens, return {@code null} to avoid returning stale
                // information.
                if (DEBUG) {
                    Slogf.d(TAG, "Skipping stale CPU availability information for cpuset %s",
                            CpuAvailabilityMonitoringConfig.toCpusetString(cpuset));
                }
                mLatestCpuAvailabilityInfo = null;
                return;
            }
            // Avoid constructing {@link mLatestCpuAvailabilityInfo} if the uptime hasn't changed.
            if (mLatestCpuAvailabilityInfo != null
                    && mLatestCpuAvailabilityInfo.dataTimestampUptimeMillis
                    == latestSnapshot.uptimeMillis) {
                return;
            }
            long earliestUptimeMillis = currentUptimeMillis - latestAvailabilityDurationMillis;
            mLatestCpuAvailabilityInfo = new CpuAvailabilityInfo(cpuset,
                    latestSnapshot.uptimeMillis, latestSnapshot.getAverageAvailableCpuFreqPercent(),
                    getCumulativeAvgAvailabilityPercent(earliestUptimeMillis),
                    latestAvailabilityDurationMillis);
        }

        private int getCumulativeAvgAvailabilityPercent(long earliestUptimeMillis) {
            long totalAvailableCpuFreqKHz = 0;
            long totalOnlineMaxCpuFreqKHz = 0;
            int totalAccountedSnapshots = 0;
            long earliestSeenUptimeMillis = Long.MAX_VALUE;
            for (int i = mSnapshotsByUptime.size() - 1; i >= 0; i--) {
                Snapshot snapshot = mSnapshotsByUptime.valueAt(i);
                earliestSeenUptimeMillis = snapshot.uptimeMillis;
                if (snapshot.uptimeMillis <= earliestUptimeMillis) {
                    break;
                }
                totalAccountedSnapshots++;
                totalAvailableCpuFreqKHz += snapshot.totalNormalizedAvailableCpuFreqKHz;
                totalOnlineMaxCpuFreqKHz += snapshot.totalOnlineMaxCpuFreqKHz;
            }
            // The cache must have at least 2 snapshots within the given duration and
            // the {@link earliestSeenUptimeMillis} must be earlier than (i,e., less than) the given
            // {@link earliestUptimeMillis}. Otherwise, the cache doesn't have enough data to
            // calculate the cumulative average for the given duration.
            // TODO(b/267500110): Investigate whether the cumulative average duration should be
            //  shrunk when not enough data points are available.
            if (earliestSeenUptimeMillis > earliestUptimeMillis || totalAccountedSnapshots < 2) {
                return CpuAvailabilityInfo.MISSING_CPU_AVAILABILITY_PERCENT;
            }
            return (int) ((totalAvailableCpuFreqKHz * 100.0) / totalOnlineMaxCpuFreqKHz);
        }

        public void clear() {
            mLatestCpuAvailabilityInfo = null;
            mSnapshotsByUptime.clear();
        }

        @Override
        public String toString() {
            return "CpusetInfo{cpuset = " + CpuAvailabilityMonitoringConfig.toCpusetString(cpuset)
                    + ", mSnapshotsByUptime = " + mSnapshotsByUptime
                    + ", mLatestCpuAvailabilityInfo = " + mLatestCpuAvailabilityInfo + '}';
        }

        private static final class Snapshot {
            public final long uptimeMillis;
            public int totalOnlineCpus;
            public int totalOfflineCpus;
            public long totalNormalizedAvailableCpuFreqKHz;
            public long totalOnlineMaxCpuFreqKHz;
            public long totalOfflineMaxCpuFreqKHz;

            Snapshot(long uptimeMillis) {
                this.uptimeMillis = uptimeMillis;
            }

            public void appendCpuInfo(CpuInfoReader.CpuInfo cpuInfo) {
                if (!cpuInfo.isOnline) {
                    totalOfflineCpus++;
                    totalOfflineMaxCpuFreqKHz += cpuInfo.maxCpuFreqKHz;
                    return;
                }
                ++totalOnlineCpus;
                totalNormalizedAvailableCpuFreqKHz += cpuInfo.getNormalizedAvailableCpuFreqKHz();
                totalOnlineMaxCpuFreqKHz += cpuInfo.maxCpuFreqKHz;
            }

            public int getAverageAvailableCpuFreqPercent() {
                return (int) ((totalNormalizedAvailableCpuFreqKHz * 100.0)
                        / totalOnlineMaxCpuFreqKHz);
            }

            @Override
            public String toString() {
                return "Snapshot{uptimeMillis = " + uptimeMillis + ", totalOnlineCpus = "
                        + totalOnlineCpus + ", totalOfflineCpus = " + totalOfflineCpus
                        + ", totalNormalizedAvailableCpuFreqKHz = "
                        + totalNormalizedAvailableCpuFreqKHz
                        + ", totalOnlineMaxCpuFreqKHz = " + totalOnlineMaxCpuFreqKHz
                        + ", totalOfflineMaxCpuFreqKHz = " + totalOfflineMaxCpuFreqKHz + '}';
            }
        }
    }
}
