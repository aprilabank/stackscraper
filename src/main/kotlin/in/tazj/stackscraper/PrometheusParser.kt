package `in`.tazj.stackscraper

import `in`.tazj.stackscraper.MetricType.*
import `in`.tazj.stackscraper.PrometheusLine.HelpLine
import `in`.tazj.stackscraper.PrometheusLine.MetricLine
import `in`.tazj.stackscraper.PrometheusLine.TypeLine
import org.jparsec.Parser
import org.jparsec.Parsers
import org.jparsec.Parsers.or
import org.jparsec.Scanners
import org.jparsec.Scanners.*
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
    string("counter").retn(Counter),
    string("gauge").retn(Gauge),
    string("histogram").retn(Histogram),
    string("summary").retn(Summary),
    string("untyped").retn(Untyped)
)

// TODO: This allows underscores, but should technically allow colons, too.
val METRIC_NAME = IDENTIFIER.source().followedBy(WHITESPACES.skipMany())

val TYPE_LINE = Parsers.sequence(
    string("# TYPE").followedBy(WHITESPACES.skipMany()),
    METRIC_NAME,
    METRIC_TYPE,
    { _, name, type -> TypeLine(name, type) }
)

val HELP_LINE = Parsers.sequence(
    string("# HELP").followedBy(WHITESPACES.skipMany()),
    METRIC_NAME,
    Patterns.many(CharPredicates.notChar('\n')).toScanner("help").source(),
    { _, name, help -> HelpLine(name, help) }
)

val LABEL = Parsers.sequence(
    Patterns.WORD.toScanner("key").source(),
    isChar('='),
    Terminals.StringLiteral.DOUBLE_QUOTE_TOKENIZER,
    { key, _, value -> Pair(key, value) }
)

val LABELS: Parser<Map<String, String>> = Parsers.between(
    isChar('{'),
    LABEL
        .sepEndBy(isChar(','))
        .map { it.toMap() },
    isChar('}')
).followedBy(WHITESPACES.skipMany())

val NUMBER = Parsers.or(
    Patterns.SCIENTIFIC_NOTATION.toScanner("scientific"),
    Patterns.STRICT_DECIMAL.toScanner("fraction")
)

val VALUE = Parsers.or(
    isChar('-').next(NUMBER),
    NUMBER
).source().map { it.toDouble() }

val METRIC_LINE = Parsers.sequence(
    METRIC_NAME,
    LABELS.asOptional(),
    VALUE,
    { name, labels, value -> MetricLine(name, labels.orElse(emptyMap()), value) }
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
    val help: HelpLine? = null,
    val type: TypeLine? = null
)

fun combineLines(lines: List<PrometheusLine>): List<Metric> {
    return lines.fold(
        initial = Accumulator(),
        operation = { acc, line -> combineStep(acc, line) }
    ).result
}

fun combineStep(acc: Accumulator, line: PrometheusLine): Accumulator {
    return when (line) {
        is TypeLine -> acc.copy(type = line)
        is HelpLine -> acc.copy(help = line)
        is MetricLine -> acc.copy(
            result = acc.result.plus(combineMetric(acc.help, acc.type, line)),
            help = null,
            type = null
        )
    }
}

fun combineMetric(help: HelpLine?, type: TypeLine?, metric: MetricLine): Metric {
    return Metric(
        name = metric.name,
        type = type?.type ?: Untyped,
        help = help?.help,
        labels = metric.labels,
        value = metric.value
    )
}

val PROMETHEUS_TEXT_FORMAT = LINE
    .sepEndBy(WHITESPACES)
    .map { combineLines(it) }
