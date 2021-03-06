package com.jashmore.sqs.examples;

import static com.jashmore.sqs.aws.AwsConstants.MAX_NUMBER_OF_MESSAGES_IN_BATCH;
import static java.util.stream.Collectors.toSet;

import akka.http.scaladsl.Http;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jashmore.sqs.QueueProperties;
import com.jashmore.sqs.argument.ArgumentResolverService;
import com.jashmore.sqs.argument.CoreArgumentResolverService;
import com.jashmore.sqs.argument.messageid.MessageId;
import com.jashmore.sqs.argument.payload.Payload;
import com.jashmore.sqs.argument.payload.mapper.JacksonPayloadMapper;
import com.jashmore.sqs.argument.payload.mapper.PayloadMapper;
import com.jashmore.sqs.broker.MessageBroker;
import com.jashmore.sqs.broker.concurrent.CachingConcurrentMessageBrokerProperties;
import com.jashmore.sqs.broker.concurrent.ConcurrentMessageBroker;
import com.jashmore.sqs.broker.concurrent.ConcurrentMessageBrokerProperties;
import com.jashmore.sqs.container.CoreMessageListenerContainer;
import com.jashmore.sqs.container.MessageListenerContainer;
import com.jashmore.sqs.processor.CoreMessageProcessor;
import com.jashmore.sqs.processor.MessageProcessor;
import com.jashmore.sqs.resolver.MessageResolver;
import com.jashmore.sqs.resolver.batching.BatchingMessageResolver;
import com.jashmore.sqs.resolver.batching.StaticBatchingMessageResolverProperties;
import com.jashmore.sqs.retriever.MessageRetriever;
import com.jashmore.sqs.retriever.prefetch.PrefetchingMessageRetriever;
import com.jashmore.sqs.retriever.prefetch.StaticPrefetchingMessageRetrieverProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.elasticmq.rest.sqs.SQSRestServerBuilder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.validation.constraints.Min;
import javax.validation.constraints.PositiveOrZero;

/**
 * This example shows the core framework being used to processing messages place onto the queue with a dynamic level of concurrency via the
 * {@link ConcurrentMessageBroker}. The rate of concurrency is randomly changed every {@link #CONCURRENCY_LEVEL_PERIOD_IN_MS} to a new value between 0
 * and {@link #CONCURRENCY_LEVEL_LIMIT}.
 *
 * <p>This example will also show how the performance of the message processing can be improved by prefetching messages via the
 * {@link PrefetchingMessageRetriever}.
 *
 * <p>While this is running you should see the messages being processed and the number of messages that are concurrently being processed. This will highlight
 * how the concurrency can change during the running of the application.
 */
@Slf4j
public class ConcurrentBrokerExample {
    private static final long CONCURRENCY_LEVEL_PERIOD_IN_MS = 5000L;
    private static final int CONCURRENCY_LEVEL_LIMIT = 10;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String QUEUE_NAME = "my_queue";

    /**
     * Example that will continue to place messages on the message queue with a message listener consuming them.
     *
     * @param args unused args
     * @throws Exception if there was a problem running the program
     */
    public static void main(final String[] args) throws Exception {
        // Sets up the SQS that will be used
        final SqsAsyncClient sqsAsyncClient = startElasticMqServer();
        final String queueUrl = sqsAsyncClient.createQueue((request) -> request.queueName(QUEUE_NAME).build()).get().queueUrl();
        final QueueProperties queueProperties = QueueProperties
                .builder()
                .queueUrl(queueUrl)
                .build();

        final MessageListenerContainer messageListenerContainer = new CoreMessageListenerContainer(
                "core-example-container",
                ConcurrentBrokerExample::buildBroker,
                () -> buildMessageRetriever(queueProperties, sqsAsyncClient),
                () -> buildMessageProcessor(queueProperties, sqsAsyncClient),
                () -> buildMessageResolver(queueProperties, sqsAsyncClient)
        );
        messageListenerContainer.start();

        log.info("Starting producing");
        Executors.newSingleThreadExecutor().submit(new Producer(sqsAsyncClient, queueUrl))
                .get();
    }

    /**
     * Runs a local Elastic MQ server that will act like the SQS queue for local testing.
     *
     * <p>This is useful as it means the users of this example don't need to worry about setting up any queue system them self.
     *
     * @return amazon sqs client for connecting the local queue
     */
    private static SqsAsyncClient startElasticMqServer() throws URISyntaxException {
        log.info("Starting Local ElasticMQ SQS Server");
        final SQSRestServer sqsRestServer = SQSRestServerBuilder
                .withInterface("localhost")
                .withDynamicPort()
                .start();

        final Http.ServerBinding serverBinding = sqsRestServer.waitUntilStarted();

        return SqsAsyncClient.builder()
                .region(Region.of("elasticmq"))
                .endpointOverride(new URI("http://localhost:" + serverBinding.localAddress().getPort()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("accessKeyId", "secretAccessKey")))
                .build();
    }

    private static MessageBroker buildBroker() {
        return new ConcurrentMessageBroker(
                // Represents a concurrent implementation that will fluctuate between 0 and 10 threads all processing messages
                new CachingConcurrentMessageBrokerProperties(10000, new ConcurrentMessageBrokerProperties() {
                    private final Random random = new Random(1);

                    @Override
                    public int getConcurrencyLevel() {
                        return random.nextInt(CONCURRENCY_LEVEL_LIMIT);
                    }

                    @Override
                    public @Min(0) Long getConcurrencyPollingRateInMilliseconds() {
                        return CONCURRENCY_LEVEL_PERIOD_IN_MS;
                    }

                    @Override
                    public @PositiveOrZero Long getErrorBackoffTimeInMilliseconds() {
                        return 500L;
                    }
                })
        );
    }

    private static MessageProcessor buildMessageProcessor(final QueueProperties queueProperties,
                                                          final SqsAsyncClient sqsAsyncClient) {
        final MessageConsumer messageConsumer = new MessageConsumer();
        final Method messageReceivedMethod;
        try {
            messageReceivedMethod = MessageConsumer.class.getMethod("method", Request.class, String.class);
        } catch (final NoSuchMethodException exception) {
            throw new RuntimeException(exception);
        }

        return new CoreMessageProcessor(
                argumentResolverService(),
                queueProperties,
                sqsAsyncClient,
                messageReceivedMethod,
                messageConsumer
        );
    }

    private static MessageResolver buildMessageResolver(final QueueProperties queueProperties,
                                                        final SqsAsyncClient sqsAsyncClient) {
        return new BatchingMessageResolver(queueProperties, sqsAsyncClient,
                StaticBatchingMessageResolverProperties.builder()
                        .bufferingSizeLimit(MAX_NUMBER_OF_MESSAGES_IN_BATCH)
                        .bufferingTimeInMs(5000)
                        .build());

    }

    private static MessageRetriever buildMessageRetriever(final QueueProperties queueProperties,
                                                          final SqsAsyncClient sqsAsyncClient) {
        return new PrefetchingMessageRetriever(
                sqsAsyncClient,
                queueProperties,
                StaticPrefetchingMessageRetrieverProperties.builder()
                        .desiredMinPrefetchedMessages(10)
                        .maxPrefetchedMessages(20)
                        .build()
        );
    }

    /**
     * Builds the {@link ArgumentResolverService} that will be used to parse the messages into arguments for the {@link MessageConsumer}.
     *
     * @return the service to resolve arguments for the message consumer
     */
    private static ArgumentResolverService argumentResolverService() {
        final PayloadMapper payloadMapper = new JacksonPayloadMapper(OBJECT_MAPPER);
        return new CoreArgumentResolverService(payloadMapper, OBJECT_MAPPER);
    }

    /**
     * The thread that will be producing messages to place onto the queue.
     */
    @Slf4j
    @AllArgsConstructor
    private static class Producer implements Runnable {
        /**
         * Contains the number of messages that will be published from the producer at once.
         */
        private static final int NUMBER_OF_MESSAGES_FOR_PRODUCER = 10;

        private final SqsAsyncClient async;
        private final String queueUrl;

        @Override
        public void run() {
            final AtomicInteger count = new AtomicInteger(0);
            boolean shouldStop = false;
            while (!shouldStop) {
                try {
                    final SendMessageBatchRequest.Builder batchRequestBuilder = SendMessageBatchRequest.builder().queueUrl(queueUrl);
                    batchRequestBuilder.entries(IntStream.range(0, NUMBER_OF_MESSAGES_FOR_PRODUCER)
                            .mapToObj(index -> {
                                final String messageId = "" + index;
                                final Request request = new Request("key_" + count.getAndIncrement());
                                try {
                                    return SendMessageBatchRequestEntry.builder().id(messageId).messageBody(OBJECT_MAPPER.writeValueAsString(request)).build();
                                } catch (JsonProcessingException exception) {
                                    throw new RuntimeException(exception);
                                }
                            })
                            .collect(toSet()));
                    log.info("Put 10 messages onto queue");
                    async.sendMessageBatch(batchRequestBuilder.build());
                    Thread.sleep(500);
                } catch (final InterruptedException interruptedException) {
                    log.info("Producer Thread has been interrupted");
                    shouldStop = true;
                } catch (final Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        }
    }

    @Data
    @AllArgsConstructor
    private static class Request {
        private final String key;
    }

    @Slf4j
    @SuppressWarnings("WeakerAccess")
    public static class MessageConsumer {
        /**
         * Static variable that is used to show that the level of concurrency is being followed.
         *
         * <p>This is useful when the concurrency is dynamically changing we can see that we are in fact properly processing them with the correct
         * concurrency level.
         */
        private static AtomicInteger concurrentMessagesBeingProcessed = new AtomicInteger(0);

        /**
         * Random number generator for calculating the random time that a message will take to be processed.
         */
        private final Random processingTimeRandom = new Random();

        /**
         * Method that will consume the messages.
         *
         * @param request   the payload of the message
         * @param messageId the SQS message ID of this message
         * @throws InterruptedException if the thread was interrupted while sleeping
         */
        public void method(@Payload final Request request, @MessageId final String messageId) throws InterruptedException {
            try {
                final int concurrentMessages = concurrentMessagesBeingProcessed.incrementAndGet();
                int processingTimeInMs = processingTimeRandom.nextInt(3000);
                log.trace("Payload: {}, messageId: {}", request, messageId);
                log.info("Message processing in {}ms. {} currently being processed concurrently", processingTimeInMs, concurrentMessages);
                Thread.sleep(processingTimeInMs);
            } finally {
                concurrentMessagesBeingProcessed.decrementAndGet();
            }
        }
    }
}
