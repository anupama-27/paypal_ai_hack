package com.cashflowai.agent;

import com.cashflowai.detection.ExceptionDetectionService;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.lifecycle.CaseLifecycleService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs detection, then the agent on every waiting case, in the background.
 * The UI gets a run id immediately and polls progress, since LLM calls take a while.
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    public record RunView(String id, String status, int total, int done, List<Long> caseIds,
                          Instant startedAt, Instant finishedAt, String error) {
    }

    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final List<Long> caseIds;
        final Instant startedAt = Instant.now();
        volatile String status = "RUNNING";
        volatile int done;
        volatile Instant finishedAt;
        volatile String error;

        Run(List<Long> caseIds) {
            this.caseIds = caseIds;
        }

        RunView view() {
            return new RunView(id, status, caseIds.size(), done, caseIds, startedAt, finishedAt, error);
        }
    }

    private final ExceptionDetectionService detection;
    private final CaseLifecycleService lifecycle;
    private final AgentOrchestrator agent;
    private final ArCaseRepository cases;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-run");
        t.setDaemon(true);
        return t;
    });
    private volatile Run current;

    public AgentRunService(ExceptionDetectionService detection, CaseLifecycleService lifecycle,
                           AgentOrchestrator agent, ArCaseRepository cases) {
        this.detection = detection;
        this.lifecycle = lifecycle;
        this.agent = agent;
        this.cases = cases;
    }

    /** Starts a run, or returns the one already in progress. */
    public synchronized RunView start() {
        if (current != null && "RUNNING".equals(current.status)) {
            return current.view();
        }
        lifecycle.runFollowUps();
        detection.detect();
        List<Long> waiting = cases.findByStatus(CaseStatus.DETECTED).stream().map(ArCase::getId).sorted().toList();
        Run run = new Run(waiting);
        runs.put(run.id, run);
        current = run;
        worker.submit(() -> execute(run));
        return run.view();
    }

    public Optional<RunView> get(String id) {
        return Optional.ofNullable(runs.get(id)).map(Run::view);
    }

    public Optional<RunView> latest() {
        return Optional.ofNullable(current).map(Run::view);
    }

    private void execute(Run run) {
        try {
            for (Long caseId : run.caseIds) {
                try {
                    agent.investigate(caseId);
                } catch (RuntimeException e) {
                    log.error("Agent failed on case {}", caseId, e);
                    cases.findById(caseId).filter(c -> c.getStatus() == CaseStatus.INVESTIGATING).ifPresent(c -> {
                        c.setStatus(CaseStatus.ESCALATED);
                        c.setExplanation("Agent error (" + e.getClass().getSimpleName() + "); handed to a human.");
                        cases.save(c);
                    });
                }
                run.done++;
            }
            run.status = "DONE";
        } catch (RuntimeException e) {
            run.status = "FAILED";
            run.error = e.getMessage();
        } finally {
            run.finishedAt = Instant.now();
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
