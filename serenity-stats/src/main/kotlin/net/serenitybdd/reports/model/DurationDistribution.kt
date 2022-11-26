package net.serenitybdd.reports.model

import net.serenitybdd.core.environment.EnvironmentSpecificConfiguration
import net.thucydides.core.ThucydidesSystemProperty
import net.thucydides.core.model.TestOutcome
import net.thucydides.core.model.TestTag
import net.thucydides.core.reports.TestOutcomes
import net.thucydides.core.util.EnvironmentVariables
import java.time.Duration
import java.time.temporal.ChronoUnit

class DurationDistribution(
    val environmentVariables: EnvironmentVariables,
    val testOutcomes: TestOutcomes
) {

    companion object {
        const val DEFAULT_DURATION_RANGES_IN_SECONDS = "1, 10, 30, 60, 120, 300, 600"

    }

    var durationLimits = durationLimitsDefinedIn(environmentVariables)
    var durationBuckets = durationBucketsFrom(durationLimits)

    init {
        populateDurationBuckets()
    }

    private fun durationBucketsFrom(durationLimits: List<Duration>): List<DurationBucket> {
        val durationBuckets: MutableList<DurationBucket> = mutableListOf()
        val bucketLabels = distributionLabels()
        var lowerLimit = 0L
        for ((index, durationLimit) in durationLimits.plus(Duration.ofSeconds(Long.MAX_VALUE)).withIndex()) {
            val upperLimit = durationLimit.get(ChronoUnit.SECONDS)
            durationBuckets.add(
                DurationBucket(
                    bucketLabels[index],
                    lowerLimit,
                    upperLimit,
                    mutableListOf()
                )
            )
            lowerLimit = upperLimit
        }
        return durationBuckets
    }

    fun getBucketCount(): Int {
        return distributionLabels().size
    }

    fun getNumberOfTestsPerDuration(): String {
        val durationCounts = durationBuckets.map { bucket -> bucket.outcomes.size }
        return asFormattedList(durationCounts)
    }

    private fun populateDurationBuckets() {
        // Find all the test case durations in the current test outcomes
        val testCaseDurations = testOutcomes.tests.flatMap { testCase -> testCaseDurationsIn(testCase) }
        // Find the bucket or buckets that match the test results in each test outcome and add the test outcome to the corresponding buckets
        for (testCaseDuration in testCaseDurations) {
            // Assign each test outcome containing a scenario
            findMatchingBucketForTestCase(testCaseDuration).addOutcome(testCaseDuration)
        }
    }

    private fun testCaseDurationsIn(testOutcome: TestOutcome): List<TestCaseDuration> {
        return if (testOutcome.isDataDriven) {
            testOutcome.testSteps.map { testStep ->
                TestCaseDuration(testStep.description, testStep.duration, testOutcome.fromStep(testStep))
            }
        } else {
            listOf(TestCaseDuration(testOutcome.title, testOutcome.duration, testOutcome))
        }
    }

    fun distributionLabels(): List<String> {

        val labels: MutableList<String> = mutableListOf()

        labels.add("Under ${formatted(durationLimits.first())}")
        for (i in 0..durationLimits.size - 2) {
            val lowerLimit = durationLimits[i]
            val upperLimit = durationLimits[i + 1]
            val label = if (unitOf(lowerLimit) == unitOf(upperLimit)) {
                // 1 to 2 seconds
                "${valueOf(lowerLimit, unitOf(lowerLimit))} to ${formatted(upperLimit)}"
            } else if ((unitOf(lowerLimit) != unitOf(upperLimit)) && (valueOf(upperLimit, unitOf(upperLimit)) == "1")) {
                // 30 seconds to 1 minute -> 30 to 60 seconds
                "${valueOf(lowerLimit, unitOf(lowerLimit))} to ${formattedWithUnit(upperLimit, unitOf(lowerLimit))}"
            } else {
                // 30 seconds to 1 minute 30 seconds
                "${formatted(lowerLimit)} to ${formatted(upperLimit)}"
            }
            labels.add(label)
        }
        labels.add("${formatted(durationLimits.last())} or over")

        return labels
    }

    fun getBucketLabels(): String {
        return asFormattedList(distributionLabels())
    }

    fun asFormattedList(labels: List<Any>) = "[${labels.joinToString(",") { duration -> "'${duration}'" }}]"

    fun unitOf(duration: Duration): String {
        return if (toHoursPart(duration) > 0) "HOURS"
        else if (toMinutesPart(duration) > 0) "MINUTES"
        else if (duration.seconds > 0) return "SECONDS"
        else "MILLISECONDS"
    }

    private fun formattedWithUnit(duration: Duration, unit: String): String {
        return when (unit) {
            "SECONDS" -> seconds(duration.get(ChronoUnit.SECONDS)).trim()
            "MINUTES" -> minutes(duration.get(ChronoUnit.MINUTES)).trim()
            "HOURS" -> hours(duration.get(ChronoUnit.HOURS)).trim()
            else -> "${duration.toMillis()} ms"
        }
    }

    fun valueOf(duration: Duration, unit: String): String {
        return when (unit) {
            "SECONDS" -> duration.seconds.toString()
            "MINUTES" -> (duration.seconds / 60).toString()
            "HOURS" -> (duration.seconds / 3600).toString()
            else -> duration.toMillis().toString()
        }
    }

    fun formatted(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = toMinutesPart(duration)
        val seconds = toSecondsPart(duration)
        return "${hours(hours)} ${minutes(minutes.toLong())} ${seconds(seconds.toLong())}".trim()
    }

    fun seconds(value: Long): String {
        if (value == 0L) return ""
        return if (value == 1L) "$value second" else "$value seconds"
    }

    fun minutes(value: Long): String {
        if (value == 0L) return ""
        return if (value == 1L) "$value minute" else "$value minutes"
    }

    fun hours(value: Long): String {
        if (value == 0L) return ""
        return if (value == 1L) "$value hour" else "$value hours"
    }

    private fun toHoursPart(duration: Duration): Int {
        return (duration.toHours() % 24).toInt()
    }

    private fun toMinutesPart(duration: Duration): Int {
        return (duration.toMinutes() % 60).toInt()
    }

    private fun toSecondsPart(duration: Duration): Int {
        return (duration.seconds % 60).toInt()
    }

    private fun durationLimitsDefinedIn(environmentVariables: EnvironmentVariables): List<Duration> {
        val durationLimits = EnvironmentSpecificConfiguration.from(environmentVariables)
            .getOptionalProperty(ThucydidesSystemProperty.SERENITY_REPORT_DURATIONS)
            .orElse(DEFAULT_DURATION_RANGES_IN_SECONDS)

        return durationLimits.split(",").map { value ->
            Duration.ofSeconds(Integer.parseInt(value.trim()).toLong())
        }
    }

    fun findMatchingBucketForTestCase(testCase: TestCaseDuration) =
        if (testCase.duration < durationBuckets.first().minDurationInSeconds) {
            durationBuckets.first()
        }
        else if (testCase.duration >= durationBuckets.last().minDurationInSeconds)
            durationBuckets.last()
        else
            durationBuckets.first { bucket ->
                testCase.duration in bucket.minDurationInSeconds * 1000 until bucket.maxDurationInSeconds * 1000
            }

    fun findMatchingBucketsForTestOutcome(testOutcome: TestOutcome) =
        testCaseDurationsIn(testOutcome).map { testCaseDuration -> findMatchingBucketForTestCase(testCaseDuration) }

    fun getDurationTags(): List<TestTag> = durationBuckets.map { bucket -> bucket.getTag() }

}
