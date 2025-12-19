package ru.quipy.payments.logic

import org.springframework.beans.factory.annotation.Autowired
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.common.utils.SlidingWindowRateLimiter
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.quipy.payments.api.PaymentMetric
import java.util.concurrent.TimeUnit
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private var metrics: PaymentMetric
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }
    
    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private var rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private var rateLimiter = SlidingWindowRateLimiter(rate = rateLimitPerSec.toLong(), window = Duration.ofSeconds(1))

    private val semaphore = Semaphore(permits = parallelRequests)

    val inSemaphoreCounter = Gauge.builder(
            "availablePermits_in_semaphore",
            java.util.function.Supplier { semaphore.availablePermits.toDouble() }
        )
            .description("availablePermits in semaphore for account $accountName")
            .register(Metrics.globalRegistry)

    private val httpClient = HttpClient.newBuilder()
        .executor(Executors.newFixedThreadPool(100))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val maxRetryAttempts = 3
    private val retryDelayMillis = 100L

    private val dispatcherDB = Executors.newFixedThreadPool(30).asCoroutineDispatcher()
    private val dispatcherPayment = Executors.newFixedThreadPool(30).asCoroutineDispatcher()

    private val paymentScope = CoroutineScope(
        dispatcherPayment + SupervisorJob() + CoroutineName("payment-service-$accountName")
    )

    private val dbScope = CoroutineScope(
       Dispatchers.IO  + SupervisorJob() + CoroutineName("db-payment-service-$accountName")
    )

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId NumberOfRequests: ${getNumberOfRequests()}")
        metrics.RequestsCounter.increment()
        val transactionId = UUID.randomUUID()

        dbScope.launch {
            // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
            // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
            paymentESService.update(paymentId) {
                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
            }
        }

        metrics.requestInPaymentServiceCount.incrementAndGet()

        sendRequestRetryManagerAsync(
                "http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount",
                paymentId,
                transactionId,
                deadline
                )
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun getProperties() : PaymentAccountProperties {
        return properties
    }

    override fun getNumberOfRequests() : Long {
        return metrics.requestInPaymentServiceCount.get()
    }

    override fun name() = properties.accountName

    suspend fun checkDeadline(paymentId: UUID, transactionId: UUID, deadline: Long, delay: Long = 0)  : Boolean {
        if (deadline < (now()+properties.averageProcessingTime.toMillis() + delay)) {
            logger.error("goodby payment 2: $paymentId")
            dbScope.launch{
                paymentESService.update(paymentId) {
                it.logProcessing(success = false, now(), transactionId = transactionId, reason = "deadline")
                }
            }
            return true
        }
        return false
    }

    fun sendRequestRetryManagerAsync(url: String, paymentId: UUID, transactionId: UUID,deadline: Long) {
        try {

            metrics.ManagerCounter.increment()

            logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

            if (deadline < (now()+properties.averageProcessingTime.toMillis())) {
                metrics.DeadlineCounter.increment()
                logger.error("goodby payment 2: $paymentId")
                dbScope.launch{
                    paymentESService.update(paymentId) {
                    it.logProcessing(success = false, now(), transactionId = transactionId, reason = "deadline")
                    }
                }
                return
            }

            metrics.checkDeadlineCounter.increment()

            val startSemaphore = now()

            metrics.semaphoreQueueCount.incrementAndGet()

            paymentScope.launch {
                semaphore.withPermit {
                    metrics.AfterSemaphoreCounter.increment()
                    val durationSemaphore = now() - startSemaphore
                    metrics.semaphoreQueueDurationTimer.record(durationSemaphore, TimeUnit.MILLISECONDS)
                    metrics.semaphoreQueueCount.decrementAndGet()
                    doRetryLoopAsync( url, paymentId, transactionId, deadline)
                }
            }
            
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.warn("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    dbScope.launch{
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                        }
                    }
                }

                else -> {
                    logger.warn("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
                    dbScope.launch{
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = e.message)
                        }
                    }
                }
            }
        }
    }

    suspend fun doRetryLoopAsync(url: String, paymentId: UUID, transactionId: UUID,deadline: Long){
        metrics.LoopCounter.increment()
        if (checkDeadline(paymentId, transactionId, deadline)){
                metrics.DeadlineCounter2.increment()
                return
            }
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofMillis(deadline - now()))
            .POST(HttpRequest.BodyPublishers.noBody())

        val request = requestBuilder.build()

        var send = false
        var n = 0
        var delay = 0L
        while (!send) {

            metrics.rateLimiterQueueCount.incrementAndGet()
            val startRateLimiter = now()

            rateLimiter.tickSuspend()

            metrics.AfterRateLimiterCounter.increment()
            val durationRateLimiter = now() - startRateLimiter
            metrics.rateLimiterQueueDurationTimer.record(durationRateLimiter, TimeUnit.MILLISECONDS)
            metrics.rateLimiterQueueCount.decrementAndGet()     

            if (checkDeadline(paymentId, transactionId, deadline)){
                metrics.DeadlineCounter3.increment()
                metrics.paymentResponceCounter.increment()
                metrics.requestInPaymentServiceCount.decrementAndGet()
                return
            }

            metrics.incomingRequestsCounter.increment()

            try {
                delay(1)
                send = sendRequestAsync(request, now(), transactionId, paymentId)

                if (send) {
                    // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                    // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                    dbScope.launch{
                        paymentESService.update(paymentId) {
                            it.logProcessing(send, now(), transactionId, reason = null)
                        }
                    }
                    break
                }
                n += 1
                val retryResult = doRetry(n, "failed", delay, transactionId, paymentId, deadline)
                if (!retryResult){
                    break
                }
                delay += retryDelayMillis
            }
            catch (e: java.io.InterruptedIOException) {
                n += 1
                val retryResult = doRetry(n, "timeout", delay, transactionId, paymentId, deadline)
                if (!retryResult){
                    break
                }
                delay += retryDelayMillis
            }
        }

        metrics.paymentResponceCounter.increment()
        metrics.requestInPaymentServiceCount.decrementAndGet()
    }

    suspend fun sendRequestAsync(request : HttpRequest, startCall: Long, transactionId: UUID, paymentId: UUID) : Boolean {
        metrics.sendCounter.increment()
        var result = false

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            val executionTimeMillis = System.currentTimeMillis() - startCall
            metrics.recordLatency(response.statusCode(), executionTimeMillis)
            val body = try {
                mapper.readValue(response.body(), ExternalSysResponse::class.java)
            } catch (e: Exception) {
                logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.statusCode()}, reason: ${response.body()}")
                ExternalSysResponse(transactionId.toString(), paymentId.toString(),false, e.message)
            }
            result = body.result
            logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")
        }
        return result
    }

    suspend fun doRetry(n: Int, reason: String, delay: Long, transactionId: UUID, paymentId: UUID, deadline: Long) : Boolean{
        if (n >= maxRetryAttempts) {
            logger.warn("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId, max retry for reason $reason attempts reached")
            dbScope.launch{
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Max retry attempts reached")
                }
            }
            return false
        }
        else {
            if (checkDeadline(paymentId, transactionId, deadline, delay)){
                logger.warn("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId, client deadline will exceeded")
                return false
            }
            logger.warn("[$accountName] Payment retrying reason $reason for txId: $transactionId, payment: $paymentId, attempt: $n, delay: $delay ms")
            metrics.paymentRetryCounter.increment()
            delay(delay)
            return true
        }
    }

}

public fun now() = System.currentTimeMillis()