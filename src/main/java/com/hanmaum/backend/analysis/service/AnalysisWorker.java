package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.analysis.repository.AnalysisRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** All tuning comes from explicit external configuration. No retry limit, delay or TTL policy. */
public final class AnalysisWorker implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
  private final AnalysisWorkerConfiguration.Settings settings;
  private final AnalysisRepository runs;
  private final AnalysisExecutionService execution;
  private final AnalysisProcessor processor;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService executor;
  private final Semaphore slots;

  public AnalysisWorker(
      AnalysisWorkerConfiguration.Settings settings,
      AnalysisRepository runs,
      AnalysisExecutionService execution,
      AnalysisProcessor processor) {
    this.settings = settings;
    this.runs = runs;
    this.execution = execution;
    this.processor = processor;
    slots = new Semaphore(settings.concurrency());
    executor =
        Executors.newFixedThreadPool(
            settings.concurrency(), Thread.ofPlatform().name("analysis-execution-", 0).factory());
    scheduler =
        Executors.newScheduledThreadPool(
            settings.concurrency() + 1,
            Thread.ofPlatform().name("analysis-scheduler-", 0).factory());
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(
        this::tick, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
  }

  private void tick() {
    try {
      for (var run : runs.expired(settings.concurrency()))
        execution.recover(run.spaceId(), run.id());
      for (var run : runs.queued(slots.availablePermits())) {
        if (!slots.tryAcquire()) break;
        try {
          executor.execute(
              () -> {
                try {
                  var work = execution.claim(run.spaceId(), run.id(), settings.leaseDuration());
                  if (work.isEmpty()) return;
                  long heartbeatMillis = settings.leaseDuration().toMillis() / 3;
                  var heartbeat =
                      scheduler.scheduleWithFixedDelay(
                          () -> {
                            try {
                              runs.heartbeat(
                                  run.id(),
                                  work.get().run().workerToken(),
                                  settings.leaseDuration());
                            } catch (RuntimeException exception) {
                              log.warn("Analysis heartbeat failed: {}", run.id());
                            }
                          },
                          heartbeatMillis,
                          heartbeatMillis,
                          TimeUnit.MILLISECONDS);
                  try {
                    processor.process(work.get());
                  } finally {
                    heartbeat.cancel(false);
                  }
                } catch (RuntimeException exception) {
                  log.warn("Analysis execution interrupted: {}", run.id());
                } finally {
                  slots.release();
                }
              });
        } catch (RuntimeException exception) {
          slots.release();
          throw exception;
        }
      }
    } catch (RuntimeException exception) {
      // Pending DB work survives a failed poll; never log source/provider/SQL exception bodies.
      log.warn("Analysis worker could not check pending work");
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
    scheduler.shutdownNow();
  }
}
