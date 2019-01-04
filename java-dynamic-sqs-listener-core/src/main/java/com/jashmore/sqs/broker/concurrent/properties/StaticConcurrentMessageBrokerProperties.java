package com.jashmore.sqs.broker.concurrent.properties;

import com.google.common.base.Preconditions;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.Optional;

/**
 * Implementation that stores the value as non-mutable field values and therefore will return the same value on every call.
 *
 * <p>This is useful when you don't need the listener to be dynamic or have the ability to turn off when needed.
 */
@Value
@Builder
public final class StaticConcurrentMessageBrokerProperties implements ConcurrentMessageBrokerProperties {
    private static final Integer DEFAULT_CONCURRENCY_POLLING_IN_MS = 60_000;

    @NonNull
    private final Integer concurrencyLevel;

    @NonNull
    private final Integer preferredConcurrencyPollingRateInMilliseconds;

    public StaticConcurrentMessageBrokerProperties(final Integer concurrencyLevel, final Integer preferredConcurrencyPollingRateInMilliseconds) {
        Preconditions.checkArgument(concurrencyLevel == null || concurrencyLevel >= 0, "concurrencyLevel should be greater than or equal to zero");
        Preconditions.checkArgument(preferredConcurrencyPollingRateInMilliseconds == null || preferredConcurrencyPollingRateInMilliseconds >= 0,
                "preferredConcurrencyPollingRateInMilliseconds should be greater than or equal to zero");

        this.concurrencyLevel = Optional.ofNullable(concurrencyLevel)
                .orElse(0);
        this.preferredConcurrencyPollingRateInMilliseconds = Optional.ofNullable(preferredConcurrencyPollingRateInMilliseconds)
                .orElse(DEFAULT_CONCURRENCY_POLLING_IN_MS);
    }
}