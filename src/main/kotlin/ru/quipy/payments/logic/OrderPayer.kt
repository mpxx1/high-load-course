package ru.quipy.payments.logic

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import ru.quipy.apigateway.HttpMetrics
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Counter
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService
) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    private val dbScope = CoroutineScope(
        Dispatchers.IO  + SupervisorJob() + CoroutineName("db-order-payer")
    )
    private val linkedBlockingQueue = LinkedBlockingQueue<Runnable>(30000) 
    private val paymentExecutor : ThreadPoolExecutor

    private lateinit var executorScope: CoroutineScope;
    private lateinit var threadQueueCounter: Gauge
    private lateinit var activeCounter: Gauge
    private lateinit var taskCounter: Counter

    init {
        var maxThreads = paymentService.getAccountsProperties().minOf { p -> processingSpeed(p)}.toInt()

        maxThreads = kotlin.math.min(200, maxThreads)

        paymentExecutor = ThreadPoolExecutor(
            maxThreads,
            maxThreads,
            0L,
            TimeUnit.MILLISECONDS,
            linkedBlockingQueue,
            NamedThreadFactory("payment-submission-executor"),
            CallerBlockingRejectedExecutionHandler()
        )

        paymentExecutor.prestartAllCoreThreads()

        executorScope = CoroutineScope(paymentExecutor.asCoroutineDispatcher());
    
        threadQueueCounter = Gauge.builder(
            "requests_in_thread_queue_total",
            java.util.function.Supplier { linkedBlockingQueue.size.toDouble() }
        )
            .description("Total number of payment requests in queue")
            .register(Metrics.globalRegistry)

        activeCounter = Gauge.builder(
            "active_threads_in_pool",
            java.util.function.Supplier { paymentExecutor.activeCount.toDouble() }
        )
            .description("Number of active threads in payment thread pool")
            .register(Metrics.globalRegistry)

        taskCounter = Counter.builder("total_tasks_submitted")
            .description("Total number of tasks submitted to payment thread pool")
            .register(Metrics.globalRegistry)
    }

    private fun processingSpeed(property : PaymentAccountProperties) : Double{
        return kotlin.math.min(property.rateLimitPerSec.toDouble(), property.parallelRequests.toDouble() / property.averageProcessingTime.toSeconds())
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long,metrics: HttpMetrics): Triple<Long, Boolean, Long> {
        val createdAt = System.currentTimeMillis()
        // val canParallel = paymentService.getAccountsProperties().minOf { p -> processingSpeed(p)}
        // val maxProcessingTime = paymentService.getAccountsProperties().minOf { p -> p.averageProcessingTime}
        // val size = linkedBlockingQueue.size

        // if (linkedBlockingQueue.size >= 4500L){
        //     var randomNumber = Random.nextInt(10000, 50000)
        //     randomNumber = 5000
        //     return Triple(createdAt,false,createdAt + randomNumber.toLong())
        // }

        // val numberOfRequests = getNumberOfRequests() 
        // if (numberOfRequests >= 5500L){
        //     var randomNumber = Random.nextInt(10000, 50000)
        //     randomNumber = 5000
        //     return Triple(createdAt,false,createdAt + randomNumber.toLong())
        // }

        // val timeToProcessAllInQueue = ((numberOfRequests/ canParallel) + (maxProcessingTime.toSeconds()+1)) * 1000
        // val canRestInQueue =  maxProcessingTime.toSeconds() /- 1.0

        // logger.info("queue size $numberOfRequests $size , canParallel $canParallel ,maxProcessingTime $maxProcessingTime timeToProcessAllInQueue $timeToProcessAllInQueue"  )
        // logger.info("Payment ${paymentId} for order $orderId created. timeToProcessAllInQueue $timeToProcessAllInQueue queueSize $numberOfRequests"  )
        // if ((createdAt + timeToProcessAllInQueue ) > deadline)
        // {    
        //     logger.info("send too many requests becouse createdAt $createdAt + $timeToProcessAllInQueue > $deadline"  )
        //     metrics.toManyRequestsDelayTime2.record(timeToProcessAllInQueue.toLong(), TimeUnit.MILLISECONDS)
        //     return Triple(createdAt,false,createdAt + (timeToProcessAllInQueue - canRestInQueue*1000).toLong())
        // }
        logger.info(
            "stage 2 $orderId"
        )
        // if (linkedBlockingQueue.remainingCapacity() == 0) {
        //     return Triple(createdAt, false, createdAt + 10)
        // }
        incrementTagTimeToDeadline("2", deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
        executorScope.launch {
            incrementTagTimeToDeadline("4", deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            // withContext(Dispatchers.IO) {
            //     paymentESService.create {
            //         it.create(paymentId, orderId, amount)
            //     }
            // }

            // // val createdEvent = dbScope.launch {
            // //     paymentESService.create {
            // //         it.create(paymentId, orderId, amount)
            // //     }
            // // }

            // launch(Dispatchers.IO) {
            //     paymentESService.create {
            //         it.create(paymentId, orderId, amount)
            //     }
            // }
            logger.info(
            "stage 3 $orderId"
        )

            val createJob = launch(Dispatchers.IO) {
                paymentESService.create {
                    it.create(paymentId, orderId, amount)
                }
            }

            // paymentESService.create {
            //         it.create(paymentId, orderId, amount)
            //     }
            logger.info(
            "stage 4 $orderId $paymentId"
        )
            logger.info("Payment ${paymentId} for order $orderId created.")
            incrementTagTimeToDeadline("5", deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline, createJob)
            metrics.responceCounter.increment()
        }
        incrementTagTimeToDeadline("3", deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
        taskCounter.increment()
        return Triple(createdAt,true,0)
    }


    fun getAccountsProperties() : List<PaymentAccountProperties> {
        return paymentService.getAccountsProperties()
    }

    fun getNumberOfRequests(): Long {
        return (linkedBlockingQueue.size + paymentService.getNumberOfRequests()).toLong()
    }

    fun incrementTagTimeToDeadline(tagValue: String, duration: Long, unit: TimeUnit) {
        val safeDuration = maxOf(duration, 0L)
        Metrics.globalRegistry
            .timer(
                "time_to_deadline",
                "tag", tagValue
            )
            .record(safeDuration, unit)
        }
}