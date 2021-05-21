/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Locale;

/**
 * This class records statistics about PackageManagerService snapshots.  It maintains two sets of
 * statistics: a periodic set which represents the last 10 minutes, and a cumulative set since
 * process boot.  The key metrics that are recorded are:
 * <ul>
 * <li> The time to create a snapshot - this is the performance cost of a snapshot
 * <li> The lifetime of the snapshot - creation time over lifetime is the amortized cost
 * <li> The number of times a snapshot is reused - this is the number of times lock
 *      contention was avoided.
 * </ul>

 * The time conversions in this class are designed to keep arithmetic using ints, rather
 * than longs.  Raw times are supplied as longs in units of us.  These are left long.
 * Rebuild durations however, are converted to ints.  An int can express a duration of
 * approximately 35 minutes.  This is longer than any expected snapshot rebuild time, so
 * an int is satisfactory.  The exception is the cumulative rebuild time over the course
 * of a monitoring cycle: this value is kept long since the cycle time is one week and in
 * a badly behaved system, the rebuild time might exceed 35 minutes.

 * @hide
 */
public class SnapshotStatistics {
    /**
     * The interval at which statistics should be ticked.  It is 60s.  The interval is in
     * units of milliseconds because that is what's required by Handler.sendMessageDelayed().
     */
    public static final int SNAPSHOT_TICK_INTERVAL_MS = 60 * 1000;

    /**
     * The number of ticks for long statistics.  This is one week.
     */
    public static final int SNAPSHOT_LONG_TICKS = 7 * 24 * 60;

    /**
     * The number snapshot event logs that can be generated in a single logging interval.
     * A small number limits the logging generated by this class.  A snapshot event log is
     * generated for every big snapshot build time, up to the limit, or whenever the
     * maximum build time is exceeded in the logging interval.
     */
    public static final int SNAPSHOT_BUILD_REPORT_LIMIT = 10;

    /**
     * The number of microseconds in a millisecond.
     */
    private static final int US_IN_MS = 1000;

    /**
     * A snapshot build time is "big" if it takes longer than 10ms.
     */
    public static final int SNAPSHOT_BIG_BUILD_TIME_US = 10 * US_IN_MS;

    /**
     * A snapshot build time is reportable if it takes longer than 30ms.  Testing shows
     * that this is very rare.
     */
    public static final int SNAPSHOT_REPORTABLE_BUILD_TIME_US = 30 * US_IN_MS;

    /**
     * A snapshot is short-lived it used fewer than 5 times.
     */
    public static final int SNAPSHOT_SHORT_LIFETIME = 5;

    /**
     * The lock to control access to this object.
     */
    private final Object mLock = new Object();

    /**
     * The bins for the build time histogram.  Values are in us.
     */
    private final BinMap mTimeBins;

    /**
     * The bins for the snapshot use histogram.
     */
    private final BinMap mUseBins;

    /**
     * The number of events reported in the current tick.
     */
    private int mEventsReported = 0;

    /**
     * The tick counter.  At the default tick interval, this wraps every 4000 years or so.
     */
    private int mTicks = 0;

    /**
     * The handler used for the periodic ticks.
     */
    private Handler mHandler = null;

    /**
     * Convert ns to an int ms.  The maximum range of this method is about 24 days.  There
     * is no expectation that an event will take longer than that.
     */
    private int usToMs(int us) {
        return us / US_IN_MS;
    }

    /**
     * This class exists to provide a fast bin lookup for histograms.  An instance has an
     * integer array that maps incoming values to bins.  Values larger than the array are
     * mapped to the top-most bin.
     */
    private static class BinMap {

        // The number of bins
        private int mCount;
        // The mapping of low integers to bins
        private int[] mBinMap;
        // The maximum mapped value.  Values at or above this are mapped to the
        // top bin.
        private int mMaxBin;
        // A copy of the original key
        private int[] mUserKey;

        /**
         * Create a bin map.  The input is an array of integers, which must be
         * monotonically increasing (this is not checked).  The result is an integer array
         * as long as the largest value in the input.
         */
        BinMap(int[] userKey) {
            mUserKey = Arrays.copyOf(userKey, userKey.length);
            // The number of bins is the length of the keys, plus 1 (for the max).
            mCount = mUserKey.length + 1;
            // The maximum value is one more than the last one in the map.
            mMaxBin = mUserKey[mUserKey.length - 1] + 1;
            mBinMap = new int[mMaxBin + 1];

            int j = 0;
            for (int i = 0; i < mUserKey.length; i++) {
                while (j <= mUserKey[i]) {
                    mBinMap[j] = i;
                    j++;
                }
            }
            mBinMap[mMaxBin] = mUserKey.length;
        }

        /**
         * Map a value to a bin.
         */
        public int getBin(int x) {
            if (x >= 0 && x < mMaxBin) {
                return mBinMap[x];
            } else if (x >= mMaxBin) {
                return mBinMap[mMaxBin];
            } else {
                // x is negative.  The bin will not be used.
                return 0;
            }
        }

        /**
         * The number of bins in this map
         */
        public int count() {
            return mCount;
        }

        /**
         * For convenience, return the user key.
         */
        public int[] userKeys() {
            return mUserKey;
        }
    }

    /**
     * A complete set of statistics.  These are public, making it simpler for a client to
     * fetch the individual fields.
     */
    public class Stats {

        /**
         * The start time for this set of statistics, in us.
         */
        public long mStartTimeUs = 0;

        /**
         * The completion time for this set of statistics, in ns.  A value of zero means
         * the statistics are still active.
         */
        public long mStopTimeUs = 0;

        /**
         * The build-time histogram.  The total number of rebuilds is the sum over the
         * histogram entries.
         */
        public int[] mTimes;

        /**
         * The reuse histogram.  The total number of snapshot uses is the sum over the
         * histogram entries.
         */
        public int[] mUsed;

        /**
         * The total number of rebuilds.  This could be computed by summing over the use
         * bins, but is maintained separately for convenience.
         */
        public int mTotalBuilds = 0;

        /**
         * The total number of times any snapshot was used.
         */
        public int mTotalUsed = 0;

        /**
         * The total number of times a snapshot was bypassed because corking was in effect.
         */
        public int mTotalCorked = 0;

        /**
         * The total number of builds that count as big, which means they took longer than
         * SNAPSHOT_BIG_BUILD_TIME_NS.
         */
        public int mBigBuilds = 0;

        /**
         * The total number of short-lived snapshots
         */
        public int mShortLived = 0;

        /**
         * The time taken to build snapshots.  This is cumulative over the rebuilds
         * recorded in mRebuilds, so the average time to build a snapshot is given by
         * mBuildTimeNs/mRebuilds.  Note that this cannot be computed from the histogram.
         */
        public long mTotalTimeUs = 0;

        /**
         * The maximum build time since the last log.
         */
        public int mMaxBuildTimeUs = 0;

        /**
         * Record the rebuild.  The parameters are the length of time it took to build the
         * latest snapshot, and the number of times the _previous_ snapshot was used.  A
         * negative value for used signals an invalid value, which is the case the first
         * time a snapshot is every built.
         */
        private void rebuild(int duration, int used,
                int buildBin, int useBin, boolean big, boolean quick) {
            mTotalBuilds++;
            mTimes[buildBin]++;

            if (used >= 0) {
                mTotalUsed += used;
                mUsed[useBin]++;
            }

            mTotalTimeUs += duration;
            boolean reportIt = false;

            if (big) {
                mBigBuilds++;
            }
            if (quick) {
                mShortLived++;
            }
            if (mMaxBuildTimeUs < duration) {
                mMaxBuildTimeUs = duration;
            }
        }

        /**
         * Record a cork.
         */
        private void corked() {
            mTotalCorked++;
        }

        private Stats(long now) {
            mStartTimeUs = now;
            mTimes = new int[mTimeBins.count()];
            mUsed = new int[mUseBins.count()];
        }

        /**
         * Create a copy of the argument.  The copy is made under lock but can then be
         * used without holding the lock.
         */
        private Stats(Stats orig) {
            mStartTimeUs = orig.mStartTimeUs;
            mStopTimeUs = orig.mStopTimeUs;
            mTimes = Arrays.copyOf(orig.mTimes, orig.mTimes.length);
            mUsed = Arrays.copyOf(orig.mUsed, orig.mUsed.length);
            mTotalBuilds = orig.mTotalBuilds;
            mTotalUsed = orig.mTotalUsed;
            mTotalCorked = orig.mTotalCorked;
            mBigBuilds = orig.mBigBuilds;
            mShortLived = orig.mShortLived;
            mTotalTimeUs = orig.mTotalTimeUs;
            mMaxBuildTimeUs = orig.mMaxBuildTimeUs;
        }

        /**
         * Set the end time for the statistics.  The end time is used only for reporting
         * in the dump() method.
         */
        private void complete(long stop) {
            mStopTimeUs = stop;
        }

        /**
         * Format a time span into ddd:HH:MM:SS.  The input is in us.
         */
        private String durationToString(long us) {
            // s has a range of several years
            int s = (int) (us / (1000 * 1000));
            int m = s / 60;
            s %= 60;
            int h = m / 60;
            m %= 60;
            int d = h / 24;
            h %= 24;
            if (d != 0) {
                return TextUtils.formatSimple("%2d:%02d:%02d:%02d", d, h, m, s);
            } else if (h != 0) {
                return TextUtils.formatSimple("%2s %02d:%02d:%02d", "", h, m, s);
            } else {
                return TextUtils.formatSimple("%2s %2s %2d:%02d", "", "", m, s);
            }
        }

        /**
         * Print the prefix for dumping.  This does not generate a line to the output.
         */
        private void dumpPrefix(PrintWriter pw, String indent, long now, boolean header,
                                String title) {
            pw.print(indent + " ");
            if (header) {
                pw.format(Locale.US, "%-23s", title);
            } else {
                pw.format(Locale.US, "%11s", durationToString(now - mStartTimeUs));
                if (mStopTimeUs != 0) {
                    pw.format(Locale.US, " %11s", durationToString(now - mStopTimeUs));
                } else {
                    pw.format(Locale.US, " %11s", "now");
                }
            }
        }

        /**
         * Dump the summary statistics record.  Choose the header or the data.
         *    number of builds
         *    number of uses
         *    number of corks
         *    number of big builds
         *    number of short lifetimes
         *    cumulative build time, in seconds
         *    maximum build time, in ms
         */
        private void dumpStats(PrintWriter pw, String indent, long now, boolean header) {
            dumpPrefix(pw, indent, now, header, "Summary stats");
            if (header) {
                pw.format(Locale.US, "  %10s  %10s  %10s  %10s  %10s  %10s  %10s",
                          "TotBlds", "TotUsed", "TotCork", "BigBlds", "ShortLvd",
                          "TotTime", "MaxTime");
            } else {
                pw.format(Locale.US,
                        "  %10d  %10d  %10d  %10d  %10d  %10d  %10d",
                        mTotalBuilds, mTotalUsed, mTotalCorked, mBigBuilds, mShortLived,
                        mTotalTimeUs / 1000, mMaxBuildTimeUs / 1000);
            }
            pw.println();
        }

        /**
         * Dump the build time histogram.  Choose the header or the data.
         */
        private void dumpTimes(PrintWriter pw, String indent, long now, boolean header) {
            dumpPrefix(pw, indent, now, header, "Build times");
            if (header) {
                int[] keys = mTimeBins.userKeys();
                for (int i = 0; i < keys.length; i++) {
                    pw.format(Locale.US, "  %10s",
                            TextUtils.formatSimple("<= %dms", keys[i]));
                }
                pw.format(Locale.US, "  %10s",
                        TextUtils.formatSimple("> %dms", keys[keys.length - 1]));
            } else {
                for (int i = 0; i < mTimes.length; i++) {
                    pw.format(Locale.US, "  %10d", mTimes[i]);
                }
            }
            pw.println();
        }

        /**
         * Dump the usage histogram.  Choose the header or the data.
         */
        private void dumpUsage(PrintWriter pw, String indent, long now, boolean header) {
            dumpPrefix(pw, indent, now, header, "Use counters");
            if (header) {
                int[] keys = mUseBins.userKeys();
                for (int i = 0; i < keys.length; i++) {
                    pw.format(Locale.US, "  %10s", TextUtils.formatSimple("<= %d", keys[i]));
                }
                pw.format(Locale.US, "  %10s",
                        TextUtils.formatSimple("> %d", keys[keys.length - 1]));
            } else {
                for (int i = 0; i < mUsed.length; i++) {
                    pw.format(Locale.US, "  %10d", mUsed[i]);
                }
            }
            pw.println();
        }

        /**
         * Dump something, based on the "what" parameter.
         */
        private void dump(PrintWriter pw, String indent, long now, boolean header, String what) {
            if (what.equals("stats")) {
                dumpStats(pw, indent, now, header);
            } else if (what.equals("times")) {
                dumpTimes(pw, indent, now, header);
            } else if (what.equals("usage")) {
                dumpUsage(pw, indent, now, header);
            } else {
                throw new IllegalArgumentException("unrecognized choice: " + what);
            }
        }

        /**
         * Report the object via an event.  Presumably the record indicates an anomalous
         * incident.
         */
        private void report() {
            EventLogTags.writePmSnapshotStats(
                    mTotalBuilds, mTotalUsed, mBigBuilds, mShortLived,
                    mMaxBuildTimeUs / US_IN_MS, mTotalTimeUs / US_IN_MS);
        }
    }

    /**
     * Long statistics.  These roll over approximately every week.
     */
    private Stats[] mLong;

    /**
     * Short statistics.  These roll over approximately every minute;
     */
    private Stats[] mShort;

    /**
     * The time of the last build.  This can be used to compute the length of time a
     * snapshot existed before being replaced.
     */
    private long mLastBuildTime = 0;

    /**
     * Create a snapshot object.  Initialize the bin levels.  The last bin catches
     * everything that is not caught earlier, so its value is not really important.
     */
    public SnapshotStatistics() {
        // Create the bin thresholds.  The time bins are in units of us.
        mTimeBins = new BinMap(new int[] { 1, 2, 5, 10, 20, 50, 100 });
        mUseBins = new BinMap(new int[] { 1, 2, 5, 10, 20, 50, 100 });

        // Create the raw statistics
        final long now = SystemClock.currentTimeMicro();
        mLong = new Stats[2];
        mLong[0] = new Stats(now);
        mShort = new Stats[10];
        mShort[0] = new Stats(now);

        // Create the message handler for ticks and start the ticker.
        mHandler = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    SnapshotStatistics.this.handleMessage(msg);
                }
            };
        scheduleTick();
    }

    /**
     * Handle a message.  The only messages are ticks, so the message parameter is ignored.
     */
    private void handleMessage(@Nullable Message msg) {
        tick();
        scheduleTick();
    }

    /**
     * Schedule one tick, a tick interval in the future.
     */
    private void scheduleTick() {
        mHandler.sendEmptyMessageDelayed(0, SNAPSHOT_TICK_INTERVAL_MS);
    }

    /**
     * Record a rebuild.  Cumulative and current statistics are updated.  Events may be
     * generated.
     * @param now The time at which the snapshot rebuild began, in ns.
     * @param done The time at which the snapshot rebuild completed, in ns.
     * @param hits The number of times the previous snapshot was used.
     */
    public final void rebuild(long now, long done, int hits) {
        // The duration has a span of about 2000s
        final int duration = (int) (done - now);
        boolean reportEvent = false;
        synchronized (mLock) {
            mLastBuildTime = now;

            final int timeBin = mTimeBins.getBin(duration / 1000);
            final int useBin = mUseBins.getBin(hits);
            final boolean big = duration >= SNAPSHOT_BIG_BUILD_TIME_US;
            final boolean quick = hits <= SNAPSHOT_SHORT_LIFETIME;

            mShort[0].rebuild(duration, hits, timeBin, useBin, big, quick);
            mLong[0].rebuild(duration, hits, timeBin, useBin, big, quick);
            if (duration >= SNAPSHOT_REPORTABLE_BUILD_TIME_US) {
                if (mEventsReported++ < SNAPSHOT_BUILD_REPORT_LIMIT) {
                    reportEvent = true;
                }
            }
        }
        // The IO to the logger is done outside the lock.
        if (reportEvent) {
            // Report the first N big builds, and every new maximum after that.
            EventLogTags.writePmSnapshotRebuild(duration / US_IN_MS, hits);
        }
    }

    /**
     * Record a corked snapshot request.
     */
    public final void corked() {
        synchronized (mLock) {
            mShort[0].corked();
            mLong[0].corked();
        }
    }

    /**
     * Roll a stats array.  Shift the elements up an index and create a new element at
     * index zero.  The old element zero is completed with the specified time.
     */
    @GuardedBy("mLock")
    private void shift(Stats[] s, long now) {
        s[0].complete(now);
        for (int i = s.length - 1; i > 0; i--) {
            s[i] = s[i - 1];
        }
        s[0] = new Stats(now);
    }

    /**
     * Roll the statistics.
     * <ul>
     * <li> Roll the quick statistics immediately.
     * <li> Roll the long statistics every SNAPSHOT_LONG_TICKER ticks.  The long
     * statistics hold a week's worth of data.
     * <li> Roll the logging statistics every SNAPSHOT_LOGGING_TICKER ticks.  The logging
     * statistics hold 10 minutes worth of data.
     * </ul>
     */
    private void tick() {
        synchronized (mLock) {
            long now = SystemClock.currentTimeMicro();
            mTicks++;
            if (mTicks % SNAPSHOT_LONG_TICKS == 0) {
                shift(mLong, now);
            }
            shift(mShort, now);
            mEventsReported = 0;
        }
    }

    /**
     * Dump the statistics.  The header is dumped from l[0], so that must not be null.
     */
    private void dump(PrintWriter pw, String indent, long now, Stats[] l, Stats[] s, String what) {
        l[0].dump(pw, indent, now, true, what);
        for (int i = 0; i < s.length; i++) {
            if (s[i] != null) {
                s[i].dump(pw, indent, now, false, what);
            }
        }
        for (int i = 0; i < l.length; i++) {
            if (l[i] != null) {
                l[i].dump(pw, indent, now, false, what);
            }
        }
    }

    /**
     * Dump the statistics.  The format is compatible with the PackageManager dumpsys
     * output.
     */
    public void dump(PrintWriter pw, String indent, long now, int unrecorded,
                     int corkLevel, boolean brief) {
        // Grab the raw statistics under lock, but print them outside of the lock.
        Stats[] l;
        Stats[] s;
        synchronized (mLock) {
            l = Arrays.copyOf(mLong, mLong.length);
            l[0] = new Stats(l[0]);
            s = Arrays.copyOf(mShort, mShort.length);
            s[0] = new Stats(s[0]);
        }
        pw.format(Locale.US, "%s Unrecorded-hits: %d  Cork-level: %d", indent,
                  unrecorded, corkLevel);
        pw.println();
        dump(pw, indent, now, l, s, "stats");
        if (brief) {
            return;
        }
        pw.println();
        dump(pw, indent, now, l, s, "times");
        pw.println();
        dump(pw, indent, now, l, s, "usage");
    }
}
