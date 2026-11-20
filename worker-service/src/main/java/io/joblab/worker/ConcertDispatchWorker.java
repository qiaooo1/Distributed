package io.joblab.worker;

import io.joblab.messaging.ConcertDispatchProtocol.Lease;
import io.joblab.messaging.ConcertDispatchProtocol.WarehouseReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static io.joblab.messaging.JobProtocol.*;

@Component
class ConcertDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(ConcertDispatchWorker.class);
    private final RestClient scheduler;

    ConcertDispatchWorker(@LoadBalanced RestClient.Builder builder, @Value("${joblab.internal-key}") String key) {
        this.scheduler = builder.clone().baseUrl("http://scheduler-service/internal/concert-dispatch")
                .defaultHeader(INTERNAL_KEY_HEADER,key)
                .defaultHeader(WORKER_PROTOCOL_HEADER,WORKER_PROTOCOL_VERSION).build();
    }

    @Scheduled(fixedDelay = 850)
    void process() {
        Lease lease;
        try {
            lease = scheduler.post().uri("/claim").retrieve().body(Lease.class);
        } catch (RestClientException ex) {
            log.debug("Concert dispatch claim unavailable: {}",ex.getMessage());
            return;
        }
        if (lease == null) return;

        try {
            WarehouseReceipt receipt = scheduler.get().uri("/receipts/{id}",lease.attemptId())
                    .retrieve().body(WarehouseReceipt.class);
            if (receipt == null) {
                scheduler.post().uri("/receipts").body(lease).retrieve().body(WarehouseReceipt.class);
            }
            scheduler.post().uri("/complete").body(lease).retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            // Let the lease expire. The next worker checks the same attempt's receipt before sending.
            log.warn("Concert dispatch attempt {} will resume on its next lease: {}",lease.attemptId(),ex.getMessage());
        }
    }
}
