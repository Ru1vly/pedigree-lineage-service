package com.edevlet.lineage.web;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.dto.LineageQueryAcceptedResponse;
import com.edevlet.lineage.dto.LineageQueryRequest;
import com.edevlet.lineage.dto.LineageQueryStatusResponse;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionService;
import com.edevlet.lineage.service.LineageQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/api/v1/lineage/queries")
@RequiredArgsConstructor
@Tag(name = "Lineage Async Ingestion & Status API", description = "e-Devlet Pedigree Family Tree Lineage Async Task Processing Endpoints")
public class LineageQueryController {

    private static final long SSE_TIMEOUT_MILLIS = 60_000L;
    private static final long SSE_POLL_INTERVAL_MILLIS = 2_000L;

    private final LineageQueryService queryService;
    private final TcknEncryptionService tcknEncryptionService;
    private final ThreadPoolTaskScheduler sseProgressScheduler;

    @PostMapping
    @Operation(summary = "Submit Async Lineage Query", description = "Ingests a citizen pedigree query task, rate-limits per user/IP, saves metadata to transactional outbox, and returns HTTP 202 Accepted immediately.")
    @ApiResponse(responseCode = "202", description = "Task Accepted for Asynchronous Processing")
    @ApiResponse(responseCode = "400", description = "Invalid Request Parameters / Validation Error")
    @ApiResponse(responseCode = "429", description = "Rate Limit Exceeded")
    public ResponseEntity<LineageQueryAcceptedResponse> submitLineageQuery(
            @Valid @RequestBody LineageQueryRequest request) {

        NationalIdentityContext identity = UserSecurityContextHolder.getRequiredContext();
        log.info("Received POST /api/v1/lineage/queries request from userId={}, nationalId={}",
                identity.userId(), tcknEncryptionService.mask(request.getNationalId()));

        LineageQueryAcceptedResponse response = queryService.submitQuery(request, identity);
        URI locationUri = URI.create("/api/v1/lineage/queries/" + response.getTransactionId());

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(locationUri)
                .header("Retry-After", String.valueOf(response.getRetryAfterSeconds()))
                .body(response);
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Poll Lineage Task Status", description = "Retrieves the current execution phase, progress percentage, or finalized pedigree result tree.")
    @ApiResponse(responseCode = "200", description = "Current Task Status Retrieved")
    @ApiResponse(responseCode = "403", description = "Unauthorized Access - User does not own the task")
    @ApiResponse(responseCode = "404", description = "Transaction ID Not Found")
    public ResponseEntity<LineageQueryStatusResponse> getLineageQueryStatus(
            @PathVariable String transactionId) {

        NationalIdentityContext identity = UserSecurityContextHolder.getRequiredContext();
        log.info("Received GET /api/v1/lineage/queries/{} from userId={}", transactionId, identity.userId());

        LineageQueryStatusResponse response = queryService.getQueryStatus(transactionId, identity);

        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noCache().mustRevalidate());
        headers.set("Retry-After", String.valueOf(response.getRetryAfterSeconds()));
        headers.setETag("\"v1-" + response.getStatus() + "-" + response.getProgressPercentage() + "\"");

        return ResponseEntity
                .ok()
                .headers(headers)
                .body(response);
    }

    @DeleteMapping("/{transactionId}")
    @Operation(summary = "Cancel Lineage Query Task", description = "Cancels a pending or processing lineage query task.")
    @ApiResponse(responseCode = "204", description = "Task Cancelled Successfully")
    public ResponseEntity<Void> cancelLineageQuery(@PathVariable String transactionId) {
        NationalIdentityContext identity = UserSecurityContextHolder.getRequiredContext();
        log.info("Received DELETE /api/v1/lineage/queries/{} from userId={}", transactionId, identity.userId());

        queryService.cancelQuery(transactionId, identity);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{transactionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream Live Lineage Task Progress (SSE)", description = "Establishes a Server-Sent Events (SSE) stream pushing real-time task progress updates to the client UI.")
    public SseEmitter streamLineageProgress(@PathVariable String transactionId) {
        NationalIdentityContext identity = UserSecurityContextHolder.getRequiredContext();
        log.info("Establishing SSE progress stream for transactionId={}, userId={}", transactionId, identity.userId());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        // The poll is scheduled on the shared sseProgressScheduler and cancels itself the moment
        // the task reaches a terminal status. The handle is published through an AtomicReference
        // because the first run can fire - and need to cancel itself - before schedule() returns.
        AtomicReference<ScheduledFuture<?>> pollHandle = new AtomicReference<>();
        AtomicBoolean isStreamFinished = new AtomicBoolean(false);

        Runnable progressPollTask = () -> {
            if (isStreamFinished.get()) {
                return;
            }
            try {
                LineageQueryStatusResponse status = queryService.getQueryStatus(transactionId, identity);
                emitter.send(SseEmitter.event()
                        .name("progress-update")
                        .data(status));

                if (status.getStatus().isTerminal()) {
                    closeStream(emitter, isStreamFinished, pollHandle, null);
                }
            } catch (Exception executionError) {
                closeStream(emitter, isStreamFinished, pollHandle, executionError);
            }
        };

        // Ensure client disconnects, timeouts, and errors release the scheduler handle immediately
        emitter.onCompletion(() -> cancelScheduledPoll(isStreamFinished, pollHandle));
        emitter.onTimeout(() -> closeStream(emitter, isStreamFinished, pollHandle, null));
        emitter.onError(error -> cancelScheduledPoll(isStreamFinished, pollHandle));

        pollHandle.set(sseProgressScheduler.scheduleWithFixedDelay(
                progressPollTask, Duration.ofMillis(SSE_POLL_INTERVAL_MILLIS)));

        if (isStreamFinished.get()) {
            cancelScheduledPoll(isStreamFinished, pollHandle);
        }

        return emitter;
    }

    private static void closeStream(
            SseEmitter emitter,
            AtomicBoolean isStreamFinished,
            AtomicReference<ScheduledFuture<?>> pollHandle,
            Throwable failureCause) {
        if (!isStreamFinished.compareAndSet(false, true)) {
            return;
        }
        cancelScheduledPoll(isStreamFinished, pollHandle);
        if (failureCause != null) {
            emitter.completeWithError(failureCause);
        } else {
            emitter.complete();
        }
    }

    private static void cancelScheduledPoll(
            AtomicBoolean isStreamFinished,
            AtomicReference<ScheduledFuture<?>> pollHandle) {
        isStreamFinished.set(true);
        ScheduledFuture<?> future = pollHandle.get();
        if (future != null) {
            future.cancel(false);
        }
    }
}
