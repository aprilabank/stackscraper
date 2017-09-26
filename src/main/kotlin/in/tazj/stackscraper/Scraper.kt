package `in`.tazj.stackscraper

import `in`.tazj.stackscraper.MetricType.Gauge
import `in`.tazj.stackscraper.MetricType.Untyped
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import io.fabric8.kubernetes.api.model.EndpointAddress
import io.fabric8.kubernetes.api.model.Endpoints
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.client.KubernetesClient

private val requestFactory = NetHttpTransport.Builder().build().createRequestFactory()

const val SCRAPE_ENABLED = "prometheus.io/scrape"
const val SCRAPE_PATH = "prometheus.io/path"
const val SCRAPE_PORT = "prometheus.io/port"

data class ScrapeAddress(
    val name: String,
    val node: String,
    val url: GenericUrl
)

data class ScrapeTarget(
    val name: String,
    val namespace: String,
    val labels: Map<String, String>,
    val urls: List<ScrapeAddress>
)

data class ScrapeResult(
    val target: ScrapeTarget,
    val address: ScrapeAddress,
    val metrics: List<Metric>
)

fun ObjectMeta.isScrapable(): Boolean {
    return this.annotations
        ?.get(SCRAPE_ENABLED)
        ?.let { it == "true" }
        ?: false
}

fun ObjectMeta.getScrapePath(): String {
    return this.annotations
        ?.get(SCRAPE_PATH)
        ?: "/metrics"
}

fun ObjectMeta.getScrapePort(): String {
    return this.annotations
        ?.get(SCRAPE_PORT)
        ?: "80"
}

fun findEndpoint(client: KubernetesClient, svc: Service): Endpoints? {
    return client.endpoints()
        .inNamespace(svc.metadata.namespace)
        .withName(svc.metadata.name)
        .get()
}

fun prepareTarget(svc: Service, addresses: List<EndpointAddress>): ScrapeTarget {
    val meta = svc.metadata
    val urls = addresses.map {
        val url = "http://${it.ip}:${meta.getScrapePort()}${meta.getScrapePath()}"
        ScrapeAddress(
            it.targetRef.name,
            it.nodeName,
            GenericUrl(url)
        )
    }

    return ScrapeTarget(
        name = meta.name,
        namespace = meta.namespace,
        labels = meta.labels,
        urls = urls
    )
}

fun scrapeTarget(target: ScrapeTarget): List<ScrapeResult> {
    return target.urls.map {
        val response = requestFactory.buildGetRequest(it.url).execute()

        if (!response.isSuccessStatusCode) {
            throw RuntimeException("Oh no!")
        }

        val metrics = PROMETHEUS_TEXT_FORMAT
            .parse(response.content.reader())
            // Filter out unsupported metrics
            .filter { it.type == Gauge || it.type == Untyped }

        ScrapeResult(
            target = target,
            address = it,
            metrics = metrics
        )
    }
}

// TODO: Create actual in-cluster URLs instead of local test stuff
fun prepareUrl(namespace: String, service: String): GenericUrl {
    val url = "http://localhost:8001/api/v1/namespaces/$namespace/services/$service:http/proxy/metrics"
    return GenericUrl(url)
}

fun findAllTargets(client: KubernetesClient): List<ScrapeTarget> {
    return client.services().inAnyNamespace()
        .list().items
        .filter { it.metadata.isScrapable() }
        .map { svc ->
            val endpoints = findEndpoint(client, svc)
                ?.let { it.subsets.flatMap { it.addresses } }
                ?: emptyList()

            // TODO: The take(1) is for local test development
            val addresses = endpoints.take(1).map {
                ScrapeAddress(
                    it.targetRef.name,
                    it.nodeName,
                    prepareUrl(svc.metadata.namespace, svc.metadata.name)
                )
            }

            ScrapeTarget(
                name = svc.metadata.name,
                namespace = svc.metadata.namespace,
                labels = svc.metadata.labels,
                urls = addresses
            )
        }
}