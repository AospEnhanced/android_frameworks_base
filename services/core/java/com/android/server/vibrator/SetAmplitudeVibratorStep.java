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

import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a step to turn the vibrator on and change its amplitude.
 *
 * <p>This step ignores vibration completion callbacks and control the vibrator on/off state
 * and amplitude to simulate waveforms represented by a sequence of {@link StepSegment}.
 */
final class SetAmplitudeVibratorStep extends AbstractVibratorStep {
    /**
     * The repeating waveform keeps the vibrator ON all the time. Use a minimum duration to
     * prevent short patterns from turning the vibrator ON too frequently.
     */
    private static final int REPEATING_EFFECT_ON_DURATION = 5000; // 5s

    private long mNextOffTime;

    SetAmplitudeVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long previousStepVibratorOffTimeout) {
        // This step has a fixed startTime coming from the timings of the waveform it's playing.
        super(conductor, startTime, controller, effect, index, previousStepVibratorOffTimeout);
        mNextOffTime = previousStepVibratorOffTimeout;
    }

    @Override
    public boolean acceptVibratorCompleteCallback(int vibratorId) {
        if (controller.getVibratorInfo().getId() == vibratorId) {
            mVibratorCompleteCallbackReceived = true;
            mNextOffTime = SystemClock.uptimeMillis();
        }
        // Timings are tightly controlled here, so only trigger this step if the vibrator was
        // supposed to be ON but has completed prematurely, to turn it back on as soon as
        // possible.
        return mNextOffTime < startTime && controller.getCurrentAmplitude() > 0;
    }

    @Override
    public List<Step> play() {
        // TODO: consider separating the "on" steps at the start into a separate Step.
        // TODO: consider instantiating the step with the required amplitude, rather than
        // needing to dig into the effect.
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "SetAmplitudeVibratorStep");
        try {
            long now = SystemClock.uptimeMillis();
            long latency = now - startTime;
            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG,
                        "Running amplitude step with " + latency + "ms latency.");
            }

            if (mVibratorCompleteCallbackReceived && latency < 0) {
                // This step was run early because the vibrator turned off prematurely.
                // Turn it back on and return this same step to run at the exact right time.
                mNextOffTime = turnVibratorBackOn(/* remainingDuration= */ -latency);
                return Arrays.asList(new SetAmplitudeVibratorStep(conductor, startTime, controller,
                        effect, segmentIndex, mNextOffTime));
            }

            VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
            if (!(segment instanceof StepSegment)) {
                Slog.w(VibrationThread.TAG,
                        "Ignoring wrong segment for a SetAmplitudeVibratorStep: " + segment);
                return skipToNextSteps(/* segmentsSkipped= */ 1);
            }

            StepSegment stepSegment = (StepSegment) segment;
            if (stepSegment.getDuration() == 0) {
                // Skip waveform entries with zero timing.
                return skipToNextSteps(/* segmentsSkipped= */ 1);
            }

            float amplitude = stepSegment.getAmplitude();
            if (amplitude == 0) {
                if (previousStepVibratorOffTimeout > now) {
                    // Amplitude cannot be set to zero, so stop the vibrator.
                    stopVibrating();
                    mNextOffTime = now;
                }
            } else {
                if (startTime >= mNextOffTime) {
                    // Vibrator is OFF. Turn vibrator back on for the duration of another
                    // cycle before setting the amplitude.
                    long onDuration = getVibratorOnDuration(effect, segmentIndex);
                    if (onDuration > 0) {
                        mVibratorOnResult = startVibrating(onDuration);
                        mNextOffTime = now + onDuration
                                + VibrationStepConductor.CALLBACKS_EXTRA_TIMEOUT;
                    }
                }
                changeAmplitude(amplitude);
            }

            // Use original startTime to avoid propagating latencies to the waveform.
            long nextStartTime = startTime + segment.getDuration();
            return nextSteps(nextStartTime, mNextOffTime, /* segmentsPlayed= */ 1);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private long turnVibratorBackOn(long remainingDuration) {
        long onDuration = getVibratorOnDuration(effect, segmentIndex);
        if (onDuration <= 0) {
            // Vibrator is supposed to go back off when this step starts, so just leave it off.
            return previousStepVibratorOffTimeout;
        }
        onDuration += remainingDuration;
        float expectedAmplitude = controller.getCurrentAmplitude();
        mVibratorOnResult = startVibrating(onDuration);
        if (mVibratorOnResult > 0) {
            // Set the amplitude back to the value it was supposed to be playing at.
            changeAmplitude(expectedAmplitude);
        }
        return SystemClock.uptimeMillis() + onDuration
                + VibrationStepConductor.CALLBACKS_EXTRA_TIMEOUT;
    }

    private long startVibrating(long duration) {
        if (VibrationThread.DEBUG) {
            Slog.d(VibrationThread.TAG,
                    "Turning on vibrator " + controller.getVibratorInfo().getId() + " for "
                            + duration + "ms");
        }
        long vibratorOnResult = controller.on(duration, getVibration().id);
        getVibration().stats().reportVibratorOn(vibratorOnResult);
        return vibratorOnResult;
    }

    /**
     * Get the duration the vibrator will be on for a waveform, starting at {@code startIndex}
     * until the next time it's vibrating amplitude is zero or a different type of segment is
     * found.
     */
    private long getVibratorOnDuration(VibrationEffect.Composed effect, int startIndex) {
        List<VibrationEffectSegment> segments = effect.getSegments();
        int segmentCount = segments.size();
        int repeatIndex = effect.getRepeatIndex();
        int i = startIndex;
        long timing = 0;
        while (i < segmentCount) {
            VibrationEffectSegment segment = segments.get(i);
            if (!(segment instanceof StepSegment)
                    || ((StepSegment) segment).getAmplitude() == 0) {
                break;
            }
            timing += segment.getDuration();
            i++;
            if (i == segmentCount && repeatIndex >= 0) {
                i = repeatIndex;
                // prevent infinite loop
                repeatIndex = -1;
            }
            if (i == startIndex) {
                return Math.max(timing, REPEATING_EFFECT_ON_DURATION);
            }
        }
        if (i == segmentCount && effect.getRepeatIndex() < 0) {
            // Vibration ending at non-zero amplitude, add extra timings to ramp down after
            // vibration is complete.
            timing += conductor.vibrationSettings.getRampDownDuration();
        }
        return timing;
    }
}
