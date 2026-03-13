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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.future.await

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
        .executor(Executors.newFixedThreadPool(200))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val maxRetryAttempts = 3
    private val retryDelayMillis = 100L

    // private val dispatcherDB = Executors.newFixedThreadPool(150).asCoroutineDispatcher()
    private val dispatcherPayment = Executors.newFixedThreadPool(100).asCoroutineDispatcher()

    private val paymentScope = CoroutineScope(
        dispatcherPayment + SupervisorJob() + CoroutineName("payment-service-$accountName")
    )

    private val dbScope = CoroutineScope(
        Dispatchers.IO  + SupervisorJob() + CoroutineName("db-payment-service-$accountName")
    )

    private val paymentLocks = ConcurrentHashMap<UUID, Mutex>()

suspend fun safeUpdate(success: Boolean, paymentId: UUID, transactionId: UUID, reason: String, now: Long) {
    val mutex = paymentLocks.computeIfAbsent(paymentId) { Mutex() }
    mutex.withLock {
        paymentESService.update(paymentId) {
                it.logProcessing(success = success, now, transactionId = transactionId, reason = reason)
            }
    }
}

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long, createJob: kotlinx.coroutines.Job) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId NumberOfRequests: ${getNumberOfRequests()}")
        // metrics.RequestsCounter.increment()
        // logger.info(
        //     "stage 5 $paymentId"
        // )
        metrics.incrementTagRps("1");
        metrics.incrementTagTimeToDeadline("6", deadline - now(), TimeUnit.MILLISECONDS)
        val transactionId = UUID.randomUUID()

        val submissionJob = dbScope.launch {
            createJob.join()
            val mutex = paymentLocks.computeIfAbsent(paymentId) { Mutex() }
            mutex.withLock {
                // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
                // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
                paymentESService.update(paymentId) {
                    it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
                }
            }
        }
        // logger.info(
        //     "stage 6 $paymentId"
        // )
        metrics.incrementTagTimeToDeadline("7", deadline - now(), TimeUnit.MILLISECONDS)

        metrics.requestInPaymentServiceCount.incrementAndGet()
        paymentScope.launch{
            metrics.incrementTagTimeToDeadline("9", deadline - now(), TimeUnit.MILLISECONDS)
            sendRequestRetryManagerAsync(
                    "http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount",
                    paymentId,
                    transactionId,
                    deadline,
                    submissionJob,
                    paymentStartedAt
                    )
        }
        metrics.incrementTagTimeToDeadline("8", deadline - now(), TimeUnit.MILLISECONDS)
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

    suspend fun checkDeadline(paymentId: UUID, transactionId: UUID, deadline: Long, submissionJob: kotlinx.coroutines.Job, paymentStartedAt: Long, delay: Long = 0)  : Boolean {
        if (deadline < (now()+properties.averageProcessingTime.toMillis() + delay)) {
            val timeToDeadlie = deadline - paymentStartedAt
            logger.error("goodby payment 2: $paymentId timeToDeadlie $timeToDeadlie")
            dbScope.launch{
                submissionJob.join()
                safeUpdate(success = false, paymentId = paymentId, transactionId = transactionId, reason = "deadline", now = now())
            }
            return true
        }
        return false
    }

    // wait in semaphore queue
    suspend fun sendRequestRetryManagerAsync(url: String, paymentId: UUID, transactionId: UUID,deadline: Long, submissionJob: kotlinx.coroutines.Job, paymentStartedAt: Long) {
        try {

        //     logger.info(
        //     "stage 7 $paymentId"
        // )
            metrics.incrementTagRps("2");
            metrics.incrementTagTimeToDeadline("10", deadline - now(), TimeUnit.MILLISECONDS)

            logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

            if (deadline < (now()+properties.averageProcessingTime.toMillis())) {
                metrics.incrementTagDeadline("1")
                val timeToDeadlie = deadline - paymentStartedAt
                logger.error("goodby payment 2: $paymentId timeToDeadlie $timeToDeadlie")
                dbScope.launch{
                    submissionJob.join()
                    safeUpdate(success = false, paymentId = paymentId, transactionId = transactionId, reason = "deadline", now = now())
                }
                metrics.requestInPaymentServiceCount.decrementAndGet()
                return
            }

            metrics.incrementTagRps("3");
            metrics.incrementTagTimeToDeadline("11", deadline - now(), TimeUnit.MILLISECONDS)

            val startSemaphore = now()

            metrics.semaphoreQueueCount.incrementAndGet()

        //     logger.info(
        //     "stage 8 $paymentId"
        // )

            semaphore.withPermit {
                metrics.incrementTagRps("4");
                val durationSemaphore = now() - startSemaphore
                metrics.semaphoreQueueDurationTimer.record(durationSemaphore, TimeUnit.MILLISECONDS)
                metrics.semaphoreQueueCount.decrementAndGet()
                doRetryLoopAsync( url, paymentId, transactionId, deadline, submissionJob, paymentStartedAt)
            }

            // semaphore.withPermit {
            //         metrics.incrementTagRps("4");
            //         val durationSemaphore = now() - startSemaphore
            //         metrics.semaphoreQueueDurationTimer.record(durationSemaphore, TimeUnit.MILLISECONDS)
            //         metrics.semaphoreQueueCount.decrementAndGet()
            //         doRetryLoopAsync( url, paymentId, transactionId, deadline)
            //     }

            // paymentScope.launch {
                // waitRateLimiterAsync()
                // if (deadline < (now()+properties.averageProcessingTime.toMillis())) {
                //     metrics.incrementTagDeadline("10")
                //     logger.error("goodby payment 20: $paymentId")
                //     dbScope.launch{
                //         paymentESService.update(paymentId) {
                //         it.logProcessing(success = false, now(), transactionId = transactionId, reason = "deadline")
                //         }
                //     }
                //     metrics.requestInPaymentServiceCount.decrementAndGet()
                // }
                // else{
                // semaphore.withPermit {
                //     metrics.incrementTagRps("4");
                //     val durationSemaphore = now() - startSemaphore
                //     metrics.semaphoreQueueDurationTimer.record(durationSemaphore, TimeUnit.MILLISECONDS)
                //     metrics.semaphoreQueueCount.decrementAndGet()
                //     doRetryLoopAsync( url, paymentId, transactionId, deadline)
                // }
                // }
            // }
            
            
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.warn("[$accountName] fail Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    dbScope.launch{
                        submissionJob.join()
                        safeUpdate(success = false, paymentId = paymentId, transactionId = transactionId, reason = "Request timeout.", now = now())
                    }
                }

                else -> {
                    logger.warn("[$accountName] fail Payment failed for txId: $transactionId, payment: $paymentId", e)
                    dbScope.launch{
                        submissionJob.join()
                        safeUpdate(success = false, paymentId = paymentId, transactionId = transactionId, reason = "Strange Payment failed.", now = now())
                    }
                }
            }
            metrics.requestInPaymentServiceCount.decrementAndGet()
        }
    }

    // retry loop
    suspend fun doRetryLoopAsync(url: String, paymentId: UUID, transactionId: UUID,deadline: Long, submissionJob: kotlinx.coroutines.Job, paymentStartedAt: Long){
        metrics.incrementTagRps("5");
        // logger.info(
        //     "stage 9 $paymentId"
        // )
        if (checkDeadline(paymentId, transactionId, deadline, submissionJob, paymentStartedAt)){
                metrics.incrementTagDeadline("2")
                metrics.requestInPaymentServiceCount.decrementAndGet()
                return
            }
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofMillis(deadline - now()))
            .POST(HttpRequest.BodyPublishers.noBody())

        val request = requestBuilder.build()

        var n = 0
        var d = 0L
        while (true) {

            val (sendResult, reason) = sendFunc(request, transactionId, paymentId, deadline, submissionJob, paymentStartedAt)
            if (sendResult) {
                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                dbScope.launch{
                    submissionJob.join()
                    safeUpdate(success = true, paymentId = paymentId, transactionId = transactionId, reason = reason, now = now())
                }
                break
            }
            logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: false, reason: $reason")

            n += 1
            val (retryStatus, retryReason) = canRetry(n, reason, d, transactionId, paymentId, deadline, submissionJob, paymentStartedAt)
            if (!retryStatus){
                logger.warn("[$accountName] Fail Retry for payment txId: $transactionId, payment: $paymentId, succeeded: false, reason: $retryReason")
                dbScope.launch{
                    submissionJob.join()
                    safeUpdate(success = false, paymentId = paymentId, transactionId = transactionId, reason = reason+retryReason, now = now())
                }
                break
            }
            // if (reason.contains("Rate limit for account")){
            //     paymentESService.update(paymentId) {
            //         it.logProcessing(false, now(), transactionId, reason = reason+retryReason)
            //     }
            //     break
            //     // delay(1000L)
            //     // waitRateLimiterAsync()
            // }
            metrics.paymentRetryCounter.increment()
            delay(d)
            d += retryDelayMillis
        }
        // logger.info(
        //     "stage 10 $paymentId"
        // )

        // metrics.paymentResponceCounter.increment()
        metrics.requestInPaymentServiceCount.decrementAndGet()
    }

    suspend fun waitRateLimiterAsync() {
        metrics.incrementTagRps("6");
        metrics.rateLimiterQueueCount.incrementAndGet()
        val startRateLimiter = now()

        rateLimiter.tickSuspend()

        val durationRateLimiter = now() - startRateLimiter
        metrics.rateLimiterQueueDurationTimer.record(durationRateLimiter, TimeUnit.MILLISECONDS)
        metrics.rateLimiterQueueCount.decrementAndGet()     
    }
    
    // wait rate limiter and send
    suspend fun sendFunc(request : HttpRequest, transactionId: UUID, paymentId: UUID, deadline: Long, submissionJob: kotlinx.coroutines.Job, paymentStartedAt: Long) : Pair<Boolean, String> {
        waitRateLimiterAsync()
        if (checkDeadline(paymentId, transactionId, deadline, submissionJob, paymentStartedAt)){
            metrics.incrementTagDeadline("3")
            // metrics.paymentResponceCounter.increment()
            // metrics.requestInPaymentServiceCount.decrementAndGet()
            return Pair(false, "deadline will exceeded")
        }

        metrics.incomingRequestsCounter.increment()
        metrics.incrementTagRps("7");

        try {
            val startCall = now()
            val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            return processResponce(response, transactionId, paymentId, startCall)
        }
        catch (e: Exception) {
            return Pair(false, "exception: ${e.message}")
        }
    }

    suspend fun processResponce(response: HttpResponse<String>, transactionId: UUID, paymentId: UUID,startCall: Long): Pair<Boolean, String> {
        val executionTimeMillis = System.currentTimeMillis() - startCall
        metrics.recordLatency(response.statusCode(), executionTimeMillis)
        val body = try {
            mapper.readValue(response.body(), ExternalSysResponse::class.java)
        } catch (e: Exception) {
            ExternalSysResponse(transactionId.toString(), paymentId.toString(),false, e.message)
            return Pair(false, e.message ?: "Error parsing response")
        }
        if (body.result){
            return Pair(true, body.message ?: "Success")
        }
        else{
            return Pair(false, body.message ?: "Failed")
        }
    }

    suspend fun canRetry(n: Int, reason: String, delay: Long, transactionId: UUID, paymentId: UUID, deadline: Long, submissionJob: kotlinx.coroutines.Job, paymentStartedAt: Long) : Pair<Boolean, String> {
        if (n >= maxRetryAttempts) {
            return Pair(false, "Max retry attempts reached")
        }
        else {
            if (reason.contains("request timed out")){
                val timeToDeadlie = deadline - paymentStartedAt
                return Pair(false, "request timed out timeToDeadlie $timeToDeadlie")
            }
            if (checkDeadline(paymentId, transactionId, deadline, submissionJob,paymentStartedAt, delay)){
                return Pair(false, "Client deadline will exceeded")
            }
            return Pair(true, "Retry successful")
        }
    }

}

public fun now() = System.currentTimeMillis()