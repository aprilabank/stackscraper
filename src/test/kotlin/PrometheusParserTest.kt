import `in`.tazj.stackscraper.HELP_LINE
import `in`.tazj.stackscraper.METRIC_LINE
import `in`.tazj.stackscraper.METRIC_TYPE
import `in`.tazj.stackscraper.Metric
import `in`.tazj.stackscraper.MetricType
import `in`.tazj.stackscraper.PROMETHEUS_TEXT_FORMAT
import `in`.tazj.stackscraper.PrometheusLine.HelpLine
import `in`.tazj.stackscraper.PrometheusLine.MetricLine
import `in`.tazj.stackscraper.PrometheusLine.TypeLine
import `in`.tazj.stackscraper.TYPE_LINE
import `in`.tazj.stackscraper.VALUE
import `in`.tazj.stackscraper.combineMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrometheusParserTest {
    @Test
    fun testParseDecimal() {
        val line = "jvm_threads_started_total 31.0"
        val expected = MetricLine("jvm_threads_started_total", emptyMap(), 31.0)
        val result = METRIC_LINE.parse(line)
        assertEquals(expected, result)
    }

    @Test
    fun testParseScientific() {
        val line = "process_start_time_seconds 1.506349368333E9"
        val expected = MetricLine("process_start_time_seconds", emptyMap(), 1.506349368333E9)
        val result = METRIC_LINE.parse(line)
        assertEquals(expected, result)
    }

    @Test
    fun testParseNegativeDecimal() {
        val expected = -4.318
        val result = VALUE.parse("-4.318")
        assertEquals(expected, result, 0.0)
    }

    @Test
    fun testParseNegativeScientific() {
        val expected = -1.4876672E7
        val result = VALUE.parse("-1.4876672E7")
        assertEquals(expected, result, 0.0)
    }

    @Test
    fun testParseLabel() {
        val line = "jvm_gc_collection_seconds_sum{gc=\"MarkSweepCompact\",} 0.1"
        val expected = MetricLine(
            "jvm_gc_collection_seconds_sum",
            mapOf("gc" to "MarkSweepCompact"),
            0.1
        )
        val result = METRIC_LINE.parse(line)
        assertEquals(expected, result)
    }

    @Test
    fun testParseMultipleLabels() {
        val line = "jvm_gc_collection_seconds_sum{gc=\"MarkSweepCompact\",foo=\"bar\",} 0.1"
        val expected = MetricLine(
            "jvm_gc_collection_seconds_sum",
            mapOf("gc" to "MarkSweepCompact", "foo" to "bar"),
            0.1
        )
        val result = METRIC_LINE.parse(line)
        assertEquals(expected, result)
    }

    @Test
    fun testParseEmptyLabels() {
        val line = "jvm_gc_collection_seconds_sum{} 0.1"
        val expected = MetricLine("jvm_gc_collection_seconds_sum", emptyMap(), 0.1)
        val result = METRIC_LINE.parse(line)
        assertEquals(expected, result)
    }

    @Test
    fun testParseTypeLine() {
        val expected = TypeLine("jvm_memory_pool_bytes_used", MetricType.Gauge)
        val result = TYPE_LINE.parse("# TYPE jvm_memory_pool_bytes_used gauge")
        assertEquals(expected, result)
    }

    @Test
    fun testParseMetricTypes() {
        MetricType.values().forEach {
            val input = it.toString().toLowerCase()
            val result = METRIC_TYPE.parse(input)
            assertEquals("Type parser should parse $it", it, result)
        }
    }

    @Test
    fun testParseHelpLine() {
        val expected = HelpLine("jvm_memory_bytes_used", "Used bytes of a given JVM memory area.")
        val result = HELP_LINE.parse(
            "# HELP jvm_memory_bytes_used Used bytes of a given JVM memory area."
        )
        assertEquals(expected, result)
    }

    @Test
    fun testCombineUnannotatedMetric() {
        val expected = Metric("test_metric", MetricType.Untyped, null, emptyMap(), 1.0)
        val result = combineMetric(
            null,
            null,
            MetricLine("test_metric", emptyMap(), 1.0)
        )

        assertEquals(expected, result)
    }

    @Test
    fun testCombineAnnotatedMetric() {
        val expected = Metric(
            "test_metric",
            MetricType.Gauge,
            "Some help text.",
            emptyMap(),
            1.0
        )

        val result = combineMetric(
            HelpLine("test_metric", "Some help text."),
            TypeLine("test_metric", MetricType.Gauge),
            MetricLine("test_metric", emptyMap(), 1.0)
        )

        assertEquals(expected, result)
    }

    @Test
    fun parseMetricsSet() {
        val file = this.javaClass.getResource("jvm.prometheus").readText()
        val result = PROMETHEUS_TEXT_FORMAT.parse(file)

        assertEquals("Result list should have expected size", 44, result.size)

        val expected = Metric(
            name = "jvm_memory_bytes_max",
            type = MetricType.Gauge,
            help = "Max (bytes) of a given JVM memory area.",
            labels = mapOf("area" to "heap"),
            value = 4.31816704E8
        )

        assertTrue(
            "Result list should contain expected element",
            result.contains(expected)
        )
    }
}
