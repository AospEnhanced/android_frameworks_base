// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <gtest/gtest.h>

#include "src/anomaly/DurationAnomalyTracker.h"
#include "src/StatsLogProcessor.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

namespace {

StatsdConfig CreateStatsdConfig(int num_buckets,
                                uint64_t threshold_ns,
                                DurationMetric::AggregationType aggregationType,
                                bool nesting) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    auto screenIsOffPredicate = CreateScreenIsOffPredicate();
    *config.add_predicate() = screenIsOffPredicate;

    auto holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    FieldMatcher dimensions = CreateAttributionUidDimensions(
            util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    dimensions.add_child()->set_field(3);  // The wakelock tag is set in field 3 of the wakelock.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() = dimensions;
    holdingWakelockPredicate.mutable_simple_predicate()->set_count_nesting(nesting);
    *config.add_predicate() = holdingWakelockPredicate;

    auto durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(screenIsOffPredicate.id());
    durationMetric->set_aggregation_type(aggregationType);
    *durationMetric->mutable_dimensions_in_what() =
        CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);

    auto alert = config.add_alert();
    alert->set_id(StringToId("alert"));
    alert->set_metric_id(StringToId("WakelockDuration"));
    alert->set_num_buckets(num_buckets);
    alert->set_refractory_period_secs(2);
    alert->set_trigger_if_sum_gt(threshold_ns);
    return config;
}

std::vector<int> attributionUids1 = {111, 222};
std::vector<string> attributionTags1 = {"App1", "GMSCoreModule1"};

std::vector<int> attributionUids2 = {111, 222};
std::vector<string> attributionTags2 = {"App2", "GMSCoreModule1"};

std::vector<int> attributionUids3 = {222};
std::vector<string> attributionTags3 = {"GMSCoreModule1"};

MetricDimensionKey dimensionKey1(
        HashableDimensionKey({FieldValue(Field(util::WAKELOCK_STATE_CHANGED,
                                               (int32_t)0x02010101),
                                         Value((int32_t)111))}),
        DEFAULT_DIMENSION_KEY);

MetricDimensionKey dimensionKey2(
    HashableDimensionKey({FieldValue(Field(util::WAKELOCK_STATE_CHANGED,
                                           (int32_t)0x02010101), Value((int32_t)222))}),
    DEFAULT_DIMENSION_KEY);

}  // namespace

TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_single_bucket) {
    const int num_buckets = 1;
    const uint64_t threshold_ns = NS_PER_SEC;
    auto config = CreateStatsdConfig(num_buckets, threshold_ns, DurationMetric::SUM, true);
    const uint64_t alert_id = config.alert(0).id();
    const uint32_t refractory_period_sec = config.alert(0).refractory_period_secs();

    int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    sp<AnomalyTracker> anomalyTracker =
            processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];

    auto screen_on_event = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 1, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    auto screen_off_event = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 10, android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screen_on_event.get());
    processor->OnLogEvent(screen_off_event.get());

    // Acquire wakelock wl1.
    auto acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + 11, attributionUids1,
                                                    attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + 11 + threshold_ns) / NS_PER_SEC + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Release wakelock wl1. No anomaly detected. Alarm cancelled at the "release" event.
    auto release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + 101, attributionUids1,
                                                    attributionTags1, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Acquire wakelock wl1 within bucket #0.
    acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + 110, attributionUids2,
                                               attributionTags2, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + 110 + threshold_ns - 90) / NS_PER_SEC + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Release wakelock wl1. One anomaly detected.
    release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + NS_PER_SEC + 109,
                                               attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + NS_PER_SEC + 109) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Acquire wakelock wl1.
    acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + NS_PER_SEC + 112,
                                               attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    // Wakelock has been hold longer than the threshold in bucket #0. The alarm is set at the
    // end of the refractory period.
    const int64_t alarmFiredTimestampSec0 = anomalyTracker->getAlarmTimestampSec(dimensionKey1);
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + NS_PER_SEC + 109) / NS_PER_SEC + 1,
              (uint32_t)alarmFiredTimestampSec0);

    // Anomaly alarm fired.
    auto alarmSet = processor->getAnomalyAlarmMonitor()->popSoonerThan(
            static_cast<uint32_t>(alarmFiredTimestampSec0));
    ASSERT_EQ(1u, alarmSet.size());
    processor->onAnomalyAlarmFired(alarmFiredTimestampSec0 * NS_PER_SEC, alarmSet);
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(refractory_period_sec + alarmFiredTimestampSec0,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Release wakelock wl1.
    release_event =
            CreateReleaseWakelockEvent(alarmFiredTimestampSec0 * NS_PER_SEC + NS_PER_SEC + 1,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    // Within refractory period. No more anomaly detected.
    EXPECT_EQ(refractory_period_sec + alarmFiredTimestampSec0,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Acquire wakelock wl1.
    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - 5 * NS_PER_SEC - 11,
                                       attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(acquire_event.get());
    const int64_t alarmFiredTimestampSec1 = anomalyTracker->getAlarmTimestampSec(dimensionKey1);
    EXPECT_EQ((bucketStartTimeNs + bucketSizeNs - 5 * NS_PER_SEC) / NS_PER_SEC,
              (uint64_t)alarmFiredTimestampSec1);

    // Release wakelock wl1.
    release_event =
            CreateReleaseWakelockEvent(bucketStartTimeNs + bucketSizeNs - 4 * NS_PER_SEC - 10,
                                       attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(refractory_period_sec +
                      (bucketStartTimeNs + bucketSizeNs - 4 * NS_PER_SEC - 10) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    alarmSet = processor->getAnomalyAlarmMonitor()->popSoonerThan(
            static_cast<uint32_t>(alarmFiredTimestampSec1));
    ASSERT_EQ(0u, alarmSet.size());

    // Acquire wakelock wl1 near the end of bucket #0.
    acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - 2,
                                               attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + bucketSizeNs) / NS_PER_SEC,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));

    // Release the event at early bucket #1.
    release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + bucketSizeNs + NS_PER_SEC - 1,
                                               attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    // Anomaly detected when stopping the alarm. The refractory period does not change.
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs + NS_PER_SEC) / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Condition changes to false.
    screen_on_event =
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs + 20,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    processor->OnLogEvent(screen_on_event.get());
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs + NS_PER_SEC) / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));

    acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 30,
                                               attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(acquire_event.get());
    // The condition is false. Do not start the alarm.
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(refractory_period_sec + (bucketStartTimeNs + bucketSizeNs + NS_PER_SEC) / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Condition turns true.
    screen_off_event =
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs + NS_PER_SEC,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screen_off_event.get());
    EXPECT_EQ((bucketStartTimeNs + 2 * bucketSizeNs + NS_PER_SEC + threshold_ns) / NS_PER_SEC,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));

    // Condition turns to false.
    screen_on_event =
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs + 2 * NS_PER_SEC + 1,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    processor->OnLogEvent(screen_on_event.get());
    // Condition turns to false. Cancelled the alarm.
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    //  Detected one anomaly.
    EXPECT_EQ(refractory_period_sec +
                      (bucketStartTimeNs + 2 * bucketSizeNs + 2 * NS_PER_SEC + 1) / NS_PER_SEC + 1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Condition turns to true again.
    screen_off_event =
            CreateScreenStateChangedEvent(bucketStartTimeNs + 2 * bucketSizeNs + 2 * NS_PER_SEC + 2,
                                          android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screen_off_event.get());
    EXPECT_EQ((bucketStartTimeNs + 2 * bucketSizeNs) / NS_PER_SEC + 2 + 2 + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));

    release_event =
            CreateReleaseWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 5 * NS_PER_SEC,
                                       attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(refractory_period_sec +
                      (bucketStartTimeNs + 2 * bucketSizeNs + 5 * NS_PER_SEC) / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
}

TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_multiple_buckets) {
    const int num_buckets = 3;
    const uint64_t threshold_ns = NS_PER_SEC;
    auto config = CreateStatsdConfig(num_buckets, threshold_ns, DurationMetric::SUM, true);
    const uint64_t alert_id = config.alert(0).id();
    const uint32_t refractory_period_sec = config.alert(0).refractory_period_secs();

    int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    sp<AnomalyTracker> anomalyTracker =
            processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];

    auto screen_off_event = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 1, android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screen_off_event.get());

    // Acquire wakelock "wc1" in bucket #0.
    auto acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - NS_PER_SEC / 2 - 1,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + bucketSizeNs) / NS_PER_SEC + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Release wakelock "wc1" in bucket #0.
    auto release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + bucketSizeNs - 1,
                                                    attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Acquire wakelock "wc1" in bucket #1.
    acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 1,
                                               attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + bucketSizeNs) / NS_PER_SEC + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + bucketSizeNs + 100,
                                               attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Acquire wakelock "wc2" in bucket #2.
    acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 1,
                                               attributionUids3, attributionTags3, "wl2");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + 2 * bucketSizeNs) / NS_PER_SEC + 2,
              anomalyTracker->getAlarmTimestampSec(dimensionKey2));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    // Release wakelock "wc2" in bucket #2.
    release_event =
            CreateReleaseWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 2 * NS_PER_SEC,
                                       attributionUids3, attributionTags3, "wl2");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey2));
    EXPECT_EQ(refractory_period_sec +
                      (bucketStartTimeNs + 2 * bucketSizeNs + 2 * NS_PER_SEC) / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey2));

    // Acquire wakelock "wc1" in bucket #2.
    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 2 * NS_PER_SEC,
                                       attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + 2 * bucketSizeNs) / NS_PER_SEC + 2 + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Release wakelock "wc1" in bucket #2.
    release_event =
            CreateReleaseWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 2.5 * NS_PER_SEC,
                                       attributionUids2, attributionTags2, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(refractory_period_sec +
                      (int64_t)(bucketStartTimeNs + 2 * bucketSizeNs + 2.5 * NS_PER_SEC) /
                              NS_PER_SEC +
                      1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + 6 * bucketSizeNs - NS_PER_SEC + 4,
                                       attributionUids3, attributionTags3, "wl2");
    processor->OnLogEvent(acquire_event.get());
    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + 6 * bucketSizeNs - NS_PER_SEC + 5,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + 6 * bucketSizeNs) / NS_PER_SEC + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ((bucketStartTimeNs + 6 * bucketSizeNs) / NS_PER_SEC + 1,
              anomalyTracker->getAlarmTimestampSec(dimensionKey2));

    release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + 6 * bucketSizeNs + 2,
                                               attributionUids3, attributionTags3, "wl2");
    processor->OnLogEvent(release_event.get());
    release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + 6 * bucketSizeNs + 6,
                                               attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey2));
    // The buckets are not messed up across dimensions. Only one dimension has anomaly triggered.
    EXPECT_EQ(refractory_period_sec + (int64_t)(bucketStartTimeNs + 6 * bucketSizeNs) / NS_PER_SEC +
                      1,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));
}

TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_long_refractory_period) {
    const int num_buckets = 2;
    const uint64_t threshold_ns = 3 * NS_PER_SEC;
    auto config = CreateStatsdConfig(num_buckets, threshold_ns, DurationMetric::SUM, false);
    int64_t bucketStartTimeNs = 10 * NS_PER_SEC;
    int64_t bucketSizeNs =
            TimeUnitToBucketSizeInMillis(config.duration_metric(0).bucket()) * 1000000;

    const uint64_t alert_id = config.alert(0).id();
    const uint32_t refractory_period_sec = 3 * bucketSizeNs / NS_PER_SEC;
    config.mutable_alert(0)->set_refractory_period_secs(refractory_period_sec);

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    ASSERT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());
    ASSERT_EQ(1u, processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers.size());

    sp<AnomalyTracker> anomalyTracker =
            processor->mMetricsManagers.begin()->second->mAllAnomalyTrackers[0];

    auto screen_off_event = CreateScreenStateChangedEvent(
            bucketStartTimeNs + 1, android::view::DisplayStateEnum::DISPLAY_STATE_OFF);
    processor->OnLogEvent(screen_off_event.get());

    // Acquire wakelock "wc1" in bucket #0.
    auto acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs - 100,
                                                    attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + bucketSizeNs) / NS_PER_SEC + 3,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Acquire the wakelock "wc1" again.
    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + bucketSizeNs + 2 * NS_PER_SEC + 1,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    // The alarm does not change.
    EXPECT_EQ((bucketStartTimeNs + bucketSizeNs) / NS_PER_SEC + 3,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(0u, anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // Anomaly alarm fired late.
    const int64_t firedAlarmTimestampNs = bucketStartTimeNs + 2 * bucketSizeNs - NS_PER_SEC;
    auto alarmSet = processor->getAnomalyAlarmMonitor()->popSoonerThan(
            static_cast<uint32_t>(firedAlarmTimestampNs / NS_PER_SEC));
    ASSERT_EQ(1u, alarmSet.size());
    processor->onAnomalyAlarmFired(firedAlarmTimestampNs, alarmSet);
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(refractory_period_sec + firedAlarmTimestampNs / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    acquire_event = CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs - 100,
                                               attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    EXPECT_EQ(refractory_period_sec + firedAlarmTimestampNs / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    auto release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 1,
                                                    attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));
    // Within the refractory period. No anomaly.
    EXPECT_EQ(refractory_period_sec + firedAlarmTimestampNs / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    // A new wakelock, but still within refractory period.
    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + 2 * bucketSizeNs + 10 * NS_PER_SEC,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ(refractory_period_sec + firedAlarmTimestampNs / NS_PER_SEC,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));

    release_event = CreateReleaseWakelockEvent(bucketStartTimeNs + 3 * bucketSizeNs - NS_PER_SEC,
                                               attributionUids1, attributionTags1, "wl1");
    // Still in the refractory period. No anomaly.
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(refractory_period_sec + firedAlarmTimestampNs / NS_PER_SEC,
              anomalyTracker->getRefractoryPeriodEndsSec(dimensionKey1));

    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + 5 * bucketSizeNs - 3 * NS_PER_SEC - 5,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + 5 * bucketSizeNs) / NS_PER_SEC,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));

    release_event =
            CreateReleaseWakelockEvent(bucketStartTimeNs + 5 * bucketSizeNs - 3 * NS_PER_SEC - 4,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(release_event.get());
    EXPECT_EQ(0u, anomalyTracker->getAlarmTimestampSec(dimensionKey1));

    acquire_event =
            CreateAcquireWakelockEvent(bucketStartTimeNs + 5 * bucketSizeNs - 3 * NS_PER_SEC - 3,
                                       attributionUids1, attributionTags1, "wl1");
    processor->OnLogEvent(acquire_event.get());
    EXPECT_EQ((bucketStartTimeNs + 5 * bucketSizeNs) / NS_PER_SEC,
              anomalyTracker->getAlarmTimestampSec(dimensionKey1));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
