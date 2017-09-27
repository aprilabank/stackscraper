package `in`.tazj.stackscraper

import `in`.tazj.stackscraper.MetricType.Gauge
import `in`.tazj.stackscraper.MetricType.Untyped
import com.google.api.client.http.javanet.NetHttpTransport

private val requestFactory = NetHttpTransport.Builder().build().createRequestFactory()

class ScrapingException(message: String) : Exception(message)

fun ScrapeTarget.scrape(): List<ScrapeResult> {
    return urls.map {
        val response = requestFactory.buildGetRequest(it.url).execute()

        if (!response.isSuccessStatusCode) {
            throw ScrapingException("Error scraping ${it.name}: ${response.statusMessage} (${response.statusCode})")
        }

        val metrics = PROMETHEUS_TEXT_FORMAT
            .parse(response.content.reader())
            // Filter out unsupported metrics
            .filter { it.type == Gauge || it.type == Untyped }

        ScrapeResult(
            target = this,
            address = it,
            metrics = metrics
        )
    }
}

data class ScrapeResult(
    val target: ScrapeTarget,
    val address: ScrapeAddress,
    val metrics: List<Metric>
)
