package `in`.tazj.stackscraper

import com.google.api.client.http.GenericUrl
import io.fabric8.kubernetes.api.model.Endpoints
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.client.KubernetesClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

const val SCRAPE_ENABLED = "prometheus.io/scrape"
const val SCRAPE_PATH = "prometheus.io/path"
const val SCRAPE_PORT = "prometheus.io/port"
const val NODE_ZONE_LABEL = "failure-domain.beta.kubernetes.io/zone"

data class ScrapeAddress(
    val name: String,
    val node: String,
    val nodeZone: String,
    val url: GenericUrl
)

data class ScrapeTarget(
    val name: String,
    val namespace: String,
    val labels: Map<String, String>,
    val urls: List<ScrapeAddress>
)

private fun ObjectMeta.isScrapable(): Boolean {
    return this.annotations
        ?.get(SCRAPE_ENABLED)
        ?.let { it == "true" }
        ?: false
}

private fun ObjectMeta.getScrapePath(): String {
    return this.annotations
        ?.get(SCRAPE_PATH)
        ?: "/metrics"
}

private fun ObjectMeta.getScrapePort(): String {
    return this.annotations
        ?.get(SCRAPE_PORT)
        ?: "80"
}

class Discovery(private val client: KubernetesClient) {
    companion object {
        // TODO: Create actual in-cluster URLs instead of local test stuff
        fun prepareUrl(namespace: String, service: String): GenericUrl {
            val url = "http://localhost:8001/api/v1/namespaces/$namespace/services/$service:http/proxy/metrics"
            return GenericUrl(url)
        }
    }

    private val nodeZoneCache: ConcurrentMap<String, String> = ConcurrentHashMap()

    private fun findNodeZone(nodeName: String): String {
        return nodeZoneCache.computeIfAbsent(nodeName) {
            val node = client.nodes().withName(nodeName).get()
            node.metadata.labels[NODE_ZONE_LABEL] ?: "unknown"
        }
    }

    private fun prepareTarget(svc: Service): ScrapeTarget {
        val endpoints = findEndpoint(svc)
            ?.let { it.subsets.flatMap { it.addresses } }
            ?: emptyList()

        // TODO: The take(1) is for local test development
        val addresses = endpoints.take(1).map {
            ScrapeAddress(
                it.targetRef.name,
                it.nodeName,
                findNodeZone(it.nodeName),
                prepareUrl(svc.metadata.namespace, svc.metadata.name)
            )
        }

        return ScrapeTarget(
            name = svc.metadata.name,
            namespace = svc.metadata.namespace,
            labels = svc.metadata.labels,
            urls = addresses
        )
    }

    private fun findEndpoint(svc: Service): Endpoints? {
        return client.endpoints()
            .inNamespace(svc.metadata.namespace)
            .withName(svc.metadata.name)
            .get()
    }

    fun findAllTargets(): List<ScrapeTarget> {
        return client.services().inAnyNamespace()
            .list().items
            .filter { it.metadata.isScrapable() }
            .map { prepareTarget(it) }
    }
}
