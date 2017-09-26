# Here be dragons!

This is an **alpha** project and this is *not* the project's final repository
location.

--------------

Stackscraper Monitoring
=======================

This project combines the simplicity of running [Stackdriver Monitoring] with
the simplicity of exposing [Prometheus] metrics.

In short, in some cases Prometheus is not worth the operational overhead and
something as simple as Stackdriver is sufficient for alerting, graphing and so
on.

However there is currently no easy way to get custom metrics into Stackdriver
Monitoring and some sort of push-based system would have to run in every
monitored service.

This project attempts to solve that by scraping metrics from services via the
standard Prometheus scraping protocol and then piping them into Google Stackdriver.

It is solely intended to be run in Kubernetes clusters and relies heavily on
the Kubernetes API.

## Overview

Right now this project is only concerned with monitoring services that run
inside of Prometheus.

This is done by following three annotations on `Service` objects:

* `prometheus.io/scrape`: Can be set to `"true"` (yes, as a string!) to enable
  metrics scraping for this service.
* `prometheus.io/port`: Override the default port (80) used for scraping.
* `prometheus.io/path`: Override the default path (`/metrics`) used for scraping.

Stackscraper will resolve the endpoints of the `Service` and scrape all pods
individually. Every pod will become a distinct "monitored resource" in Stackdriver.

## Caveats

There are a lot of caveats right now, this is a young alpha project after all!

* Only `gauge` and `untyped` Prometheus metrics are supported right now. Not all
  Prometheus metric types have an equivalent Stackdriver type, so this is not
  guaranteed to change much.
* Metric labels are static after creation
* This is not actually production-ready code (it does a lot more API calls than
  it should for basic operation right now, don't use it).

## Prometheus scraping

A short note on scraping: I didn't find an existing scraper/parser in the Java
world so I [wrote one](src/main/kotlin/in/tazj/stackscraper/PrometheusParser.kt).

If there is an existing project for this feel free to ping me about it!

