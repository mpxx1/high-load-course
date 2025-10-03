package ru.quipy.apigateway

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import org.springframework.stereotype.Component

@Component
class HttpMetrics() {

    val requestsCounter = Counter.builder("pay_requests_total")
            .description("Total number of paymennt requests")
            .register(Metrics.globalRegistry)


    val responceCounter = Counter.builder("pay_requests_completed")
            .description("Total number of completed payment requests")
            .register(Metrics.globalRegistry)

}