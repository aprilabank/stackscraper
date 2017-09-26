package `in`.tazj.stackscraper

import org.jparsec.Parser
import org.jparsec.Parsers
import org.jparsec.Parsers.or
import org.jparsec.Scanners
import org.jparsec.Terminals
import org.jparsec.pattern.CharPredicates
import org.jparsec.pattern.Patterns

/*

This file implements a parser for the Prometheus metrics text format:

https://prometheus.io/docs/instrumenting/exposition_formats/#text-format-details

*/

typealias MetricName = String

enum class MetricType {
    Counter,
    Gauge,
    Histogram,
    Summary,
    Untyped
}

sealed class PrometheusLine {
    data class TypeLine(val name: MetricName, val type: MetricType) : PrometheusLine()
    data class HelpLine(val name: MetricName, val help: String) : PrometheusLine()
    data class MetricLine(
        val name: String,
        val labels: Map<String, String>,
        val value: Double
    ) : PrometheusLine()
}

data class Metric(
    val name: MetricName,
    val type: MetricType,
    val help: String?,
    val labels: Map<String, String>,
    val value: Double
)

val METRIC_TYPE: Parser<MetricType> = or(
    Scanners.string("counter").retn(MetricType.Counter),
    Scanners.string("gauge").retn(MetricType.Gauge),
    Scanners.string("histogram").retn(MetricType.Histogram),
    Scanners.string("summary").retn(MetricType.Summary),
    Scanners.string("untyped").retn(MetricType.Untyped)
)

// TODO: This allows underscores, but should technically allow colons, too.
val METRIC_NAME = Scanners.IDENTIFIER.source().followedBy(Scanners.WHITESPACES.skipMany())

val TYPE_LINE = Parsers.sequence(
    Scanners.string("# TYPE").followedBy(Scanners.WHITESPACES.skipMany()),
    METRIC_NAME,
    METRIC_TYPE,
    { _, name, type -> PrometheusLine.TypeLine(name, type) }
)

val HELP_LINE = Parsers.sequence(
    Scanners.string("# HELP").followedBy(Scanners.WHITESPACES.skipMany()),
    METRIC_NAME,
    Patterns.many(CharPredicates.notChar('\n')).toScanner("help").source(),
    { _, name, help -> PrometheusLine.HelpLine(name, help) }
)

val LABEL = Parsers.sequence(
    Patterns.WORD.toScanner("key").source(),
    Scanners.isChar('='),
    Terminals.StringLiteral.DOUBLE_QUOTE_TOKENIZER,
    { key, _, value -> Pair(key, value) }
)

val LABELS: Parser<Map<String, String>> = Parsers.between(
    Scanners.isChar('{'),
    LABEL
        .sepEndBy(Scanners.isChar(','))
        .map { it.toMap() },
    Scanners.isChar('}')
).followedBy(Scanners.WHITESPACES.skipMany())

val NUMBER = Parsers.or(
    Patterns.SCIENTIFIC_NOTATION.toScanner("scientific"),
    Patterns.STRICT_DECIMAL.toScanner("fraction")
)

val VALUE = Parsers.or(
    Scanners.isChar('-').next(NUMBER),
    NUMBER
).source().map { it.toDouble() }

val METRIC_LINE = Parsers.sequence(
    METRIC_NAME,
    LABELS.asOptional(),
    VALUE,
    { name, labels, value -> PrometheusLine.MetricLine(name, labels.orElse(emptyMap()), value) }
)

val LINE: Parser<PrometheusLine> = Parsers.or(
    HELP_LINE,
    TYPE_LINE,
    METRIC_LINE
)

/*
 * These functions combine the help, type and metric lines from the Prometheus output format. The format specification
 * guarantees that related lines are grouped together.
 */

data class Accumulator(
    val result: List<Metric> = emptyList(),
    val help: PrometheusLine.HelpLine? = null,
    val type: PrometheusLine.TypeLine? = null
)

fun combineLines(lines: List<PrometheusLine>): List<Metric> {
    return lines.fold(
        initial = Accumulator(),
        operation = { acc, line -> combineStep(acc, line) }
    ).result
}

fun combineStep(acc: Accumulator, line: PrometheusLine): Accumulator {
    return when (line) {
        is PrometheusLine.TypeLine -> acc.copy(type = line)
        is PrometheusLine.HelpLine -> acc.copy(help = line)
        is PrometheusLine.MetricLine -> acc.copy(
            result = acc.result.plus(combineMetric(acc.help, acc.type, line)),
            help = null,
            type = null
        )
    }
}

fun combineMetric(help: PrometheusLine.HelpLine?, type: PrometheusLine.TypeLine?, metric: PrometheusLine.MetricLine): Metric {
    return Metric(
        name = metric.name,
        type = type?.type ?: MetricType.Untyped,
        help = help?.help,
        labels = metric.labels,
        value = metric.value
    )
}

val PROMETHEUS_TEXT_FORMAT = LINE
    .sepEndBy(Scanners.WHITESPACES)
    .map { combineLines(it) }
