package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.ShadowProperties;
import com.example.llmshadow.persistence.ShadowOutboxRepository;
import com.example.llmshadow.queue.ShadowJobQueue;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Service;

@Service
public class BackpressureService {

    private final ShadowJobQueue shadowJobQueue;
    private final ShadowOutboxRepository shadowOutboxRepository;
    private final ShadowProperties shadowProperties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public BackpressureService(
            ShadowJobQueue shadowJobQueue,
            ShadowOutboxRepository shadowOutboxRepository,
            ShadowProperties shadowProperties,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.shadowJobQueue = shadowJobQueue;
        this.shadowOutboxRepository = shadowOutboxRepository;
        this.shadowProperties = shadowProperties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public void assertAcceptingTraffic() {
        ShadowProperties.Backpressure backpressure = shadowProperties.backpressure();
        if (shadowJobQueue.queuedCount() + shadowJobQueue.retryCount() >= backpressure.maxQueuedJobs()) {
            throw new RequestRejectedException(
                    "Shadow queue backlog is above the configured threshold",
                    RequestRejectedException.Reason.BACKPRESSURE);
        }

        if (shadowOutboxRepository.count() >= backpressure.maxOutboxJobs()) {
            throw new RequestRejectedException(
                    "Shadow outbox backlog is above the configured threshold",
                    RequestRejectedException.Reason.BACKPRESSURE);
        }

        if (backpressure.rejectWhenCandidateCircuitOpen() && candidateCircuitOpen()) {
            throw new RequestRejectedException(
                    "Candidate circuit is open and ingress backpressure is enabled",
                    RequestRejectedException.Reason.BACKPRESSURE);
        }
    }

    private boolean candidateCircuitOpen() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("candidate");
        return circuitBreaker.getState() == CircuitBreaker.State.OPEN
                || circuitBreaker.getState() == CircuitBreaker.State.FORCED_OPEN;
    }
}
