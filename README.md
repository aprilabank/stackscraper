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

## Questions

* Discovery: Not quite sure yet, there used to be annotations on k8s objects?
* Scraping metrics: Is there a lib for _reading_ metrics? Or only for exposing?
* How to map Prometheus metrics to Stackdriver metrics?
** monitored resource <-> pod (?)  / service (?)
** endpoint disambiguation: one metric per pod? Labels?
