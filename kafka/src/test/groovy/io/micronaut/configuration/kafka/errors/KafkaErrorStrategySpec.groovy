package io.micronaut.configuration.kafka.errors

import io.micronaut.configuration.kafka.AbstractEmbeddedServerSpec
import io.micronaut.configuration.kafka.annotation.*
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException
import io.micronaut.configuration.kafka.exceptions.KafkaListenerExceptionHandler
import io.micronaut.context.annotation.Requires
import org.apache.kafka.common.TopicPartition

import java.util.concurrent.atomic.AtomicInteger

import static io.micronaut.configuration.kafka.annotation.ErrorStrategyValue.NONE
import static io.micronaut.configuration.kafka.annotation.ErrorStrategyValue.RESUME_AT_NEXT_RECORD
import static io.micronaut.configuration.kafka.annotation.ErrorStrategyValue.RETRY_ON_ERROR
import static io.micronaut.configuration.kafka.annotation.OffsetReset.EARLIEST
import static io.micronaut.configuration.kafka.annotation.OffsetStrategy.SYNC

class KafkaErrorStrategySpec extends AbstractEmbeddedServerSpec {

    void "test when the error strategy is 'resume at next offset' the next message is consumed"() {
        when:"A consumer throws an exception"
        ResumeErrorClient myClient = context.getBean(ResumeErrorClient)
        myClient.sendMessage("One")
        myClient.sendMessage("Two")

        ResumeAtNextRecordErrorCausingConsumer myConsumer = context.getBean(ResumeAtNextRecordErrorCausingConsumer)

        then:"The message that threw the exception is skipped and the next message in the poll is processed"
        conditions.eventually {
            myConsumer.received == ["One", "Two"]
            myConsumer.count.get() == 2
        }
    }

    void "test when the error strategy is 'retry on error' the second message is not consumed"() {
        when:"A consumer throws an exception"
        RetryErrorClient myClient = context.getBean(RetryErrorClient)
        myClient.sendMessage("One")
        myClient.sendMessage("Two")

        RetryOnErrorErrorCausingConsumer myConsumer = context.getBean(RetryOnErrorErrorCausingConsumer)

        then:"The message that threw the exception is re-consumed"
        conditions.eventually {
            myConsumer.received == ["One", "One", "Two"]
            myConsumer.count.get() == 3
        }
        and:"the retry of the first message is delivered at least 50ms afterwards"
        myConsumer.times[1] - myConsumer.times[0] >= 50
    }

    /**
     * @deprecated This test is deprecated as the poll next strategy is default to ensure backwards
     * compatibility with existing (broken) functionality that people may have workarounds for with
     * custom error handlers.
     */
    @Deprecated
    void "test an exception that is thrown is not committed with default error strategy"() {
        when:"A consumer throws an exception"
        PollNextErrorClient myClient = context.getBean(PollNextErrorClient)
        myClient.sendMessage("One")
        myClient.sendMessage("Two")

        PollNextErrorCausingConsumer myConsumer = context.getBean(PollNextErrorCausingConsumer)

        then:"The message is re-delivered and eventually handled"
        conditions.eventually {
            myConsumer.received.size() == 2
            myConsumer.count.get() == 3
        }
    }

    @Requires(property = 'spec.name', value = 'KafkaErrorStrategySpec')
    @KafkaListener(offsetReset = EARLIEST, offsetStrategy = SYNC, errorStrategy = @ErrorStrategy(value = RESUME_AT_NEXT_RECORD))
    static class ResumeAtNextRecordErrorCausingConsumer {
        AtomicInteger count = new AtomicInteger(0)
        List<String> received = []

        @Topic("errors-resume")
        void handleMessage(String message) {
            received.add(message)
            if (count.getAndIncrement() == 0) {
                throw new RuntimeException("Won't handle first")
            }
        }
    }

    @Requires(property = 'spec.name', value = 'KafkaErrorStrategySpec')
    @KafkaListener(
        offsetReset = EARLIEST,
        offsetStrategy = SYNC,
        errorStrategy = @ErrorStrategy(value = RETRY_ON_ERROR, retryDelay = "50ms")
    )
    static class RetryOnErrorErrorCausingConsumer {
        AtomicInteger count = new AtomicInteger(0)
        List<String> received = []
        List<Long> times = []

        @Topic("errors-retry")
        void handleMessage(String message) {
            received.add(message)
            times.add(System.currentTimeMillis())
            if (count.getAndIncrement() == 0) {
                throw new RuntimeException("Won't handle first")
            }
        }
    }

    @Requires(property = 'spec.name', value = 'KafkaErrorStrategySpec')
    @KafkaListener(offsetReset = EARLIEST, offsetStrategy = SYNC, errorStrategy = @ErrorStrategy(value = NONE))
    static class PollNextErrorCausingConsumer implements KafkaListenerExceptionHandler {
        AtomicInteger count = new AtomicInteger(0)
        List<String> received = []

        @Topic("errors-poll")
        void handleMessage(String message) {
            if (count.getAndIncrement() == 1) {
                throw new RuntimeException("Won't handle first")
            }
            received.add(message)
        }

        @Override
        void handle(KafkaListenerException exception) {
            def record = exception.consumerRecord.orElse(null)
            def consumer = exception.kafkaConsumer
            consumer.seek(
                    new TopicPartition("errors-poll", record.partition()),
                    record.offset()
            )
        }
    }

    @Requires(property = 'spec.name', value = 'KafkaErrorStrategySpec')
    @KafkaClient
    static interface ResumeErrorClient {
        @Topic("errors-resume")
        void sendMessage(String message)
    }

    @Requires(property = 'spec.name', value = 'KafkaErrorStrategySpec')
    @KafkaClient
    static interface RetryErrorClient {
        @Topic("errors-retry")
        void sendMessage(String message)
    }

    @Requires(property = 'spec.name', value = 'KafkaErrorStrategySpec')
    @KafkaClient
    static interface PollNextErrorClient {
        @Topic("errors-poll")
        void sendMessage(String message)
    }
}
