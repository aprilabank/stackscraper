import `in`.tazj.stackscraper.Discovery
import `in`.tazj.stackscraper.ScrapeTarget
import `in`.tazj.stackscraper.StackdriverClient
import `in`.tazj.stackscraper.scrape
import com.google.cloud.ServiceOptions
import com.google.cloud.monitoring.v3.MetricServiceClient
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val log = LoggerFactory.getLogger("MainKt")

fun main(args: Array<String>) {
    val kubeClient = DefaultKubernetesClient()
    val metricServiceClient = MetricServiceClient.create()
    val stackdriverClient = StackdriverClient(
        client = metricServiceClient,
        projectId = discoverProjectId(),
        clusterName = System.getenv("CLUSTER_NAME")
            ?: throw RuntimeException("Missing environment variable 'CLUSTER_NAME'")
    )

    val discovery = Discovery(kubeClient)
    val discovered = CountDownLatch(1)

    val targets: AtomicReference<List<ScrapeTarget>> = AtomicReference(emptyList())
    val scheduler = Executors.newSingleThreadScheduledExecutor()

    val discoverTask = exceptionalRunnable("discover") {
        targets.set(discovery.findAllTargets())
        discovered.countDown()
    }

    val scrapeTask = exceptionalRunnable("scrape", true) {
        log.info("Stackscraping ...")
        targets.get()
            .flatMap { it.scrape() } // TODO: Scrape in parallel?
            .parallelStream()
            .forEach { stackdriverClient.publishMetrics(it) }
    }

    scheduler.scheduleAtFixedRate(discoverTask, 0, 1, TimeUnit.MINUTES)

    // Wait until a discovery run has been performed before scheduling scraping
    discovered.await()
    scheduler.scheduleAtFixedRate(scrapeTask, 0, 15, TimeUnit.SECONDS)
}

/*
 * Java's ExecutorService class does not handle errors/exceptions happening during task execution at all. In the
 * specific case of a scheduled executor service running this means that any exception inside of the task will cause
 * the executor to stop scheduling new executions - without returning an error.
 *
 * This wraps the task in an exception handler that will at least log the error. It can be configured to shutdown the
 * entire process if necessary.
 * */
private fun exceptionalRunnable(name: String, exit: Boolean = false, task: () -> Unit): Runnable {
    return Runnable {
        try {
            task()
        } catch (e: Exception) {
            log.error("Error during execution of task '{}': ", name, e)
            if (exit) {
                System.exit(1)
            }
        }
    }
}

/**
 * The Stackdriver project a user wants to publish metrics into is not necessarily the same as the GCP project the
 * service is running in.
 *
 * This makes it possible to override the project by setting 'STACKDRIVER_PROJECT' in the environment.
 * */
private fun discoverProjectId(): String {
    return System.getenv("STACKDRIVER_PROJECT") ?: ServiceOptions.getDefaultProjectId()
}
