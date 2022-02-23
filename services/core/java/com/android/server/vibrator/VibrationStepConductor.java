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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Creates and manages a queue of steps for performing a VibrationEffect, as well as coordinating
 * dispatch of callbacks.
 *
 * <p>In general, methods in this class are intended to be called only by a single instance of
 * VibrationThread. The only thread-safe methods for calling from other threads are the "notify"
 * methods (which should never be used from the VibrationThread thread).
 */
final class VibrationStepConductor {
    private static final boolean DEBUG = VibrationThread.DEBUG;
    private static final String TAG = VibrationThread.TAG;

    /**
     * Extra timeout added to the end of each vibration step to ensure it finishes even when
     * vibrator callbacks are lost.
     */
    static final long CALLBACKS_EXTRA_TIMEOUT = 1_000;
    /** Threshold to prevent the ramp off steps from trying to set extremely low amplitudes. */
    static final float RAMP_OFF_AMPLITUDE_MIN = 1e-3f;
    static final List<Step> EMPTY_STEP_LIST = new ArrayList<>();

    private final Object mLock = new Object();

    // Used within steps.
    public final VibrationSettings vibrationSettings;
    public final DeviceVibrationEffectAdapter deviceEffectAdapter;
    public final VibrationThread.VibratorManagerHooks vibratorManagerHooks;

    private final Vibration mVibration;
    private final SparseArray<VibratorController> mVibrators = new SparseArray<>();

    private final PriorityQueue<Step> mNextSteps = new PriorityQueue<>();
    private final Queue<Step> mPendingOnVibratorCompleteSteps = new LinkedList<>();

    // Signalling fields.
    @GuardedBy("mLock")
    private final IntArray mSignalVibratorsComplete;
    @GuardedBy("mLock")
    private boolean mSignalCancel = false;
    @GuardedBy("mLock")
    private boolean mSignalCancelImmediate = false;

    private boolean mCancelled = false;
    private boolean mCancelledImmediately = false;  // hard stop
    private int mPendingVibrateSteps;
    private int mRemainingStartSequentialEffectSteps;
    private int mSuccessfulVibratorOnSteps;

    VibrationStepConductor(Vibration vib, VibrationSettings vibrationSettings,
            DeviceVibrationEffectAdapter effectAdapter,
            SparseArray<VibratorController> availableVibrators,
            VibrationThread.VibratorManagerHooks vibratorManagerHooks) {
        this.mVibration = vib;
        this.vibrationSettings = vibrationSettings;
        this.deviceEffectAdapter = effectAdapter;
        this.vibratorManagerHooks = vibratorManagerHooks;

        CombinedVibration effect = vib.getEffect();
        for (int i = 0; i < availableVibrators.size(); i++) {
            if (effect.hasVibrator(availableVibrators.keyAt(i))) {
                mVibrators.put(availableVibrators.keyAt(i), availableVibrators.valueAt(i));
            }
        }
        this.mSignalVibratorsComplete = new IntArray(mVibrators.size());
    }

    @Nullable
    AbstractVibratorStep nextVibrateStep(long startTime, VibratorController controller,
            VibrationEffect.Composed effect, int segmentIndex,
            long previousStepVibratorOffTimeout) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }
        if (segmentIndex >= effect.getSegments().size()) {
            segmentIndex = effect.getRepeatIndex();
        }
        if (segmentIndex < 0) {
            // No more segments to play, last step is to complete the vibration on this vibrator.
            return new CompleteEffectVibratorStep(this, startTime, /* cancelled= */ false,
                    controller, previousStepVibratorOffTimeout);
        }

        VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
        if (segment instanceof PrebakedSegment) {
            return new PerformPrebakedVibratorStep(this, startTime, controller, effect,
                    segmentIndex, previousStepVibratorOffTimeout);
        }
        if (segment instanceof PrimitiveSegment) {
            return new ComposePrimitivesVibratorStep(this, startTime, controller, effect,
                    segmentIndex, previousStepVibratorOffTimeout);
        }
        if (segment instanceof RampSegment) {
            return new ComposePwleVibratorStep(this, startTime, controller, effect, segmentIndex,
                    previousStepVibratorOffTimeout);
        }
        return new SetAmplitudeVibratorStep(this, startTime, controller, effect, segmentIndex,
                previousStepVibratorOffTimeout);
    }

    /** Called when this conductor is going to be started running by the VibrationThread. */
    public void prepareToStart() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }
        CombinedVibration.Sequential sequentialEffect = toSequential(mVibration.getEffect());
        mPendingVibrateSteps++;
        // This count is decremented at the completion of the step, so we don't subtract one.
        mRemainingStartSequentialEffectSteps = sequentialEffect.getEffects().size();
        mNextSteps.offer(new StartSequentialEffectStep(this, sequentialEffect));
    }

    public Vibration getVibration() {
        // No thread assertion: immutable
        return mVibration;
    }

    SparseArray<VibratorController> getVibrators() {
        // No thread assertion: immutable
        return mVibrators;
    }

    public boolean isFinished() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }
        if (mCancelledImmediately) {
            return true;  // Terminate.
        }

        // No need to check for vibration complete callbacks - if there were any, they would
        // have no steps to notify anyway.
        return mPendingOnVibratorCompleteSteps.isEmpty() && mNextSteps.isEmpty();
    }

    /**
     * Calculate the {@link Vibration.Status} based on the current queue state and the expected
     * number of {@link StartSequentialEffectStep} to be played.
     */
    public Vibration.Status calculateVibrationStatus() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        if (mCancelled) {
            return Vibration.Status.CANCELLED;
        }
        if (mPendingVibrateSteps > 0
                || mRemainingStartSequentialEffectSteps > 0) {
            return Vibration.Status.RUNNING;
        }
        // No pending steps, and something happened.
        if (mSuccessfulVibratorOnSteps > 0) {
            return Vibration.Status.FINISHED;
        }
        // If no step was able to turn the vibrator ON successfully.
        return Vibration.Status.IGNORED_UNSUPPORTED;
    }

    /**
     * Blocks until the next step is due to run. The wait here may be interrupted by calling
     * one of the "notify" methods.
     *
     * <p>This method returns true if the next step is ready to run now. If the method returns
     * false, then some waiting was done, but may have been interrupted by a wakeUp, and the
     * status and isFinished of the vibration should be re-checked before calling this method again.
     *
     * @return true if the next step can be run now or the vibration is finished, or false if this
     *   method waited and the conductor state may have changed asynchronously, in which case this
     *   method needs to be run again.
     */
    public boolean waitUntilNextStepIsDue() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        processAllNotifySignals();
        if (mCancelledImmediately) {
            // Don't try to run a step for immediate cancel, although there should be none left.
            // Non-immediate cancellation may have cleanup steps, so it continues processing.
            return false;
        }
        if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
            return true;  // Resumed step ready.
        }
        Step nextStep = mNextSteps.peek();
        if (nextStep == null) {
            return true;  // Finished
        }
        long waitMillis = nextStep.calculateWaitTime();
        if (waitMillis <= 0) {
            return true;  // Regular step ready
        }
        synchronized (mLock) {
            // Double check for signals before sleeping, as their notify wouldn't interrupt a fresh
            // wait.
            if (hasPendingNotifySignalLocked()) {
                // Don't run the next step, it will loop back to this method and process them.
                return false;
            }
            try {
                mLock.wait(waitMillis);
            } catch (InterruptedException e) {
            }
            return false;  // Caller needs to check isFinished and maybe wait again.
        }
    }

    @Nullable
    private Step pollNext() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        // Prioritize the steps resumed by a vibrator complete callback, irrespective of their
        // "next run time".
        if (!mPendingOnVibratorCompleteSteps.isEmpty()) {
            return mPendingOnVibratorCompleteSteps.poll();
        }
        return mNextSteps.poll();
    }

    /**
     * Play and remove the step at the top of this queue, and also adds the next steps generated
     * to be played next.
     */
    public void runNextStep() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }
        // In theory a completion callback could have come in between the wait finishing and
        // this method starting, but that only means the step is due now anyway, so it's reasonable
        // to run it before processing callbacks as the window is tiny.
        Step nextStep = pollNext();
        if (nextStep != null) {
            List<Step> nextSteps = nextStep.play();
            if (nextStep.getVibratorOnDuration() > 0) {
                mSuccessfulVibratorOnSteps++;
            }
            if (nextStep instanceof StartSequentialEffectStep) {
                mRemainingStartSequentialEffectSteps--;
            }
            if (!nextStep.isCleanUp()) {
                mPendingVibrateSteps--;
            }
            for (int i = 0; i < nextSteps.size(); i++) {
                mPendingVibrateSteps += nextSteps.get(i).isCleanUp() ? 0 : 1;
            }
            mNextSteps.addAll(nextSteps);
        }
    }

    /**
     * Notify the execution that cancellation is requested. This will be acted upon
     * asynchronously in the VibrationThread.
     *
     * @param immediate indicates whether cancellation should abort urgently and skip cleanup steps.
     */
    public void notifyCancelled(boolean immediate) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(false);
        }
        synchronized (mLock) {
            if (immediate && mSignalCancelImmediate || mSignalCancel) {
                // Nothing to update: already cancelled previously.
                return;
            }
            mSignalCancelImmediate |= immediate;
            mSignalCancel = true;
            mLock.notify();
        }
        if (DEBUG) {
            Slog.d(TAG, "Vibration cancel requested, immediate=" + immediate);
        }
    }

    /**
     * Notify the conductor that a vibrator has completed its work.
     *
     * <p>This is a lightweight method intended to be called directly via native callbacks.
     * The state update is recorded for processing on the main execution thread (VibrationThread).
     */
    public void notifyVibratorComplete(int vibratorId) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(false);
        }

        if (DEBUG) {
            Slog.d(TAG, "Vibration complete reported by vibrator " + vibratorId);
        }

        synchronized (mLock) {
            mSignalVibratorsComplete.add(vibratorId);
            mLock.notify();
        }
    }

    /**
     * Notify that a VibratorManager sync operation has completed.
     *
     * <p>This is a lightweight method intended to be called directly via native callbacks.
     * The state update is recorded for processing on the main execution thread
     * (VibrationThread).
     */
    public void notifySyncedVibrationComplete() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(false);
        }

        if (DEBUG) {
            Slog.d(TAG, "Synced vibration complete reported by vibrator manager");
        }

        synchronized (mLock) {
            for (int i = 0; i < mVibrators.size(); i++) {
                mSignalVibratorsComplete.add(mVibrators.keyAt(i));
            }
            mLock.notify();
        }
    }

    @GuardedBy("mLock")
    private boolean hasPendingNotifySignalLocked() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);  // Reads VibrationThread variables as well as signals.
        }
        return (mSignalCancel && !mCancelled)
            || (mSignalCancelImmediate && !mCancelledImmediately)
            || (mSignalVibratorsComplete.size() > 0);
    }

    /**
     * Process any notified cross-thread signals, applying the necessary VibrationThread state
     * changes.
     */
    private void processAllNotifySignals() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        int[] vibratorsToProcess = null;
        boolean doCancel = false;
        boolean doCancelImmediate = false;
        // Swap out the queue of completions to process.
        synchronized (mLock) {
            if (mSignalCancelImmediate) {
                if (mCancelledImmediately) {
                    Slog.wtf(TAG, "Immediate cancellation signal processed twice");
                }
                // This should only happen once.
                doCancelImmediate = true;
            }
            if (mSignalCancel && !mCancelled) {
                doCancel = true;
            }
            if (!doCancelImmediate && mSignalVibratorsComplete.size() > 0) {
                vibratorsToProcess = mSignalVibratorsComplete.toArray();  // makes a copy
                mSignalVibratorsComplete.clear();
            }
        }

        // Force cancellation means stop everything and clear all steps, so the execution loop
        // shouldn't come back to this method. To observe explicitly: this drops vibrator
        // completion signals that were collected in this call, but we won't process them
        // anyway as all steps are cancelled.
        if (doCancelImmediate) {
            processCancelImmediately();
            return;
        }
        if (doCancel) {
            processCancel();
        }
        if (vibratorsToProcess != null) {
            processVibratorsComplete(vibratorsToProcess);
        }
    }

    /**
     * Cancel the current queue, replacing all remaining steps with respective clean-up steps.
     *
     * <p>This will remove all steps and replace them with respective results of
     * {@link Step#cancel()}.
     */
    public void processCancel() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        mCancelled = true;
        // Vibrator callbacks should wait until all steps from the queue are properly cancelled
        // and clean up steps are added back to the queue, so they can handle the callback.
        List<Step> cleanUpSteps = new ArrayList<>();
        Step step;
        while ((step = pollNext()) != null) {
            cleanUpSteps.addAll(step.cancel());
        }
        // All steps generated by Step.cancel() should be clean-up steps.
        mPendingVibrateSteps = 0;
        mNextSteps.addAll(cleanUpSteps);
    }

    /**
     * Cancel the current queue immediately, clearing all remaining steps and skipping clean-up.
     *
     * <p>This will remove and trigger {@link Step#cancelImmediately()} in all steps, in order.
     */
    public void processCancelImmediately() {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        mCancelledImmediately = true;
        mCancelled = true;
        Step step;
        while ((step = pollNext()) != null) {
            step.cancelImmediately();
        }
        mPendingVibrateSteps = 0;
    }

    /**
     * Processes the vibrators that have sent their complete callbacks. A step is found that will
     * accept the completion callback, and this step is brought forward for execution in the next
     * run.
     *
     * <p>This assumes only one of the next steps is waiting on this given vibrator, so the
     * first step found will be resumed by this method, in no particular order.
     */
    private void processVibratorsComplete(@NonNull int[] vibratorsToProcess) {
        if (Build.IS_DEBUGGABLE) {
            expectIsVibrationThread(true);
        }

        for (int vibratorId : vibratorsToProcess) {
            Iterator<Step> it = mNextSteps.iterator();
            while (it.hasNext()) {
                Step step = it.next();
                if (step.acceptVibratorCompleteCallback(vibratorId)) {
                    it.remove();
                    mPendingOnVibratorCompleteSteps.offer(step);
                    break;
                }
            }
        }
    }

    private static CombinedVibration.Sequential toSequential(CombinedVibration effect) {
        if (effect instanceof CombinedVibration.Sequential) {
            return (CombinedVibration.Sequential) effect;
        }
        return (CombinedVibration.Sequential) CombinedVibration.startSequential()
                .addNext(effect)
                .combine();
    }

    /**
     * This check is used for debugging and documentation to indicate the thread that's expected
     * to invoke a given public method on this class. Most methods are only invoked by
     * VibrationThread, which is where all the steps and HAL calls should be made. Other threads
     * should only signal to the execution flow being run by VibrationThread.
     */
    private static void expectIsVibrationThread(boolean isVibrationThread) {
        if ((Thread.currentThread() instanceof VibrationThread) != isVibrationThread) {
            Slog.wtfStack("VibrationStepConductor",
                    "Thread caller assertion failed, expected isVibrationThread="
                            + isVibrationThread);
        }
    }
}
