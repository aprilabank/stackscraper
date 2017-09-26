package `in`.tazj.stackscraper

import com.google.api.LabelDescriptor
import com.google.api.MetricDescriptor
import com.google.api.MetricDescriptor.MetricKind.GAUGE
import com.google.api.MetricDescriptor.ValueType.DOUBLE
import com.google.api.MonitoredResource
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.ServiceOptions
import com.google.cloud.monitoring.v3.MetricServiceClient
import com.google.monitoring.v3.CreateTimeSeriesRequest
import com.google.monitoring.v3.MetricDescriptorName
import com.google.monitoring.v3.Point
import com.google.monitoring.v3.ProjectName
import com.google.monitoring.v3.TimeInterval
import com.google.monitoring.v3.TimeSeries
import com.google.monitoring.v3.TypedValue
import com.google.protobuf.util.Timestamps
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

typealias StackdriverMetric = com.google.api.Metric

// All monitored resources are GKE containers. Documented here: https://cloud.google.com/monitoring/api/resources
const val GKE_CONTAINER = "gke_container"
const val CUSTOM_METRIC_DOMAIN = "custom.googleapis.com"

private fun gkeContainer(
    result: ScrapeResult,
    cluster_name: String,
    project_id: String = ServiceOptions.getDefaultProjectId()
): MonitoredResource {
    val labels = mapOf(
        "project_id" to project_id,
        "cluster_name" to cluster_name,
        "namespace_id" to result.target.namespace,
        "container_name" to result.target.name,
        "pod_id" to result.address.name,
        "instance_id" to result.address.node,
        "zone" to "europe-west1-d" // TODO: Get this from k8s
    )

    return MonitoredResource.newBuilder()
        .setType(GKE_CONTAINER)
        .putAllLabels(labels)
        .build()
}

/*
Mapping of Prometheus metrics to Stackdriver metrics:

Gauges in Prometheus can only go up, but on Stackdriver they represent instantaneous measurements. This means that both
the "untyped" and "gauge" types in Prometheus map to "Gauge" on Stackdriver.

TODO: Cumulative

The value type is always "DOUBLE" in Stackdriver.

References:
https://cloud.google.com/monitoring/api/ref_v3/rest/v3/projects.metricDescriptors#metrickind


*/

fun metricsTypeFor(metric: Metric): String {
    return "$CUSTOM_METRIC_DOMAIN/${metric.name}"
}

class StackdriverClient(
    val client: MetricServiceClient,
    val projectId: String,
    val clusterName: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val projectName = ProjectName.create(projectId)

    fun publishMetrics(result: ScrapeResult) {
        log.info(
            "Publishing {} metrics for resource {}/{}",
            result.metrics.size, result.target.namespace, result.target.name
        )

        val interval = TimeInterval.newBuilder()
            .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
            .build()

        val resource = gkeContainer(result, clusterName, projectId)

        // ensure all metrics descriptors exist:
        result.metrics.parallelStream().forEach {
            prepareMetricDescriptor(it)
        }

        val timeSeriesList = result.metrics.map { it.toTimeSeries(interval, resource) }

        val request = CreateTimeSeriesRequest.newBuilder()
            .setNameWithProjectName(projectName)
            .addAllTimeSeries(timeSeriesList)
            .build()

        client.createTimeSeries(request)
    }

    fun Metric.toTimeSeries(interval: TimeInterval, resource: MonitoredResource): TimeSeries {
        val typedValue = TypedValue.newBuilder()
            .setDoubleValue(this.value)
            .build()

        val point = Point.newBuilder()
            .setValue(typedValue)
            .setInterval(interval)
            .build()

        val metric = StackdriverMetric.newBuilder()
            .setType(metricsTypeFor(this))
            .putAllLabels(this.labels)
            .build()

        return TimeSeries.newBuilder()
            .setMetric(metric)
            .setResource(resource)
            .addPoints(point)
            .build()
    }

    fun prepareMetricDescriptor(metric: Metric) {
        val name = MetricDescriptorName.create(projectId, metricsTypeFor(metric))

        log.debug("Attempting to find metrics descriptor {}", name)

        if (name.existsInService()) {
            log.debug("Found metric descriptor: {}", name)
        } else {
            val descriptor = createMetricDescriptor(metric)
            val created = client.createMetricDescriptor(projectName, descriptor)
            awaitDescriptor(name)
            log.info("Created metric descriptor '{}'", created.name)
        }
    }

    fun awaitDescriptor(name: MetricDescriptorName) {
        val counter = AtomicInteger(0)
        while (!name.existsInService()) {
            log.debug("{} does not exist after {} attempts, sleeping...", name, counter.getAndIncrement())
            Thread.sleep(500)
        }
    }

    private fun MetricDescriptorName.existsInService(): Boolean {
        try {
            client.getMetricDescriptor(this)
            return true
        } catch (e: NotFoundException) {
            return false
        }
    }

    fun createMetricDescriptor(metric: Metric): MetricDescriptor {
        return MetricDescriptor.newBuilder().apply {
            type = metricsTypeFor(metric)
            metricKind = GAUGE
            valueType = DOUBLE
            metric.labels.forEach() { key, _ ->
                val label = LabelDescriptor.newBuilder()
                    .setKey(key)
                    .build()

                addLabels(label)
            }
            metric.help?.let { setDescription(it) }
        }.build()
    }
}
