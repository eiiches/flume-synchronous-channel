package net.thisptr.flume.plugins.channel.synchronous;

import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.Transaction;
import org.apache.flume.event.SimpleEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class SynchronousChannelTest {
    private static final Logger LOG = LoggerFactory.getLogger(SynchronousChannelTest.class);

    @Test
    public void testSingleThread() throws Exception {
        testChannelDoesNotLoseEvents(10000000, 1, 1);
    }

    @Test
    public void testMultiThread() throws Exception {
        testChannelDoesNotLoseEvents(10000000, 10, 10);
    }

    private static void testChannelDoesNotLoseEvents(int numEvents, int sourceThreads, int sinkThreads) throws Exception {
        SynchronousChannel sut = new SynchronousChannel();
        sut.setName("test-ch");
        sut.configure(new Context());
        sut.start();
        try {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {

                AtomicInteger source = new AtomicInteger();

                List<CompletableFuture<?>> sourceFutures = new ArrayList<>();
                for (int th = 0; th < sourceThreads; ++th) {
                    int tid = th;

                    sourceFutures.add(CompletableFuture.runAsync(() -> {
                        Thread.currentThread().setName(String.format("%s-source-%d", sut.getName(), tid));

                        boolean end = false;
                        while (!end) {
                            int maxSize = ThreadLocalRandom.current().nextInt(20);
                            List<Event> events = new ArrayList<>();
                            for (int i = 0; i < maxSize; ++i) {
                                int value = source.getAndIncrement();
                                if (value >= numEvents) {
                                    end = true;
                                    break;
                                }
                                Event event = new SimpleEvent();
                                event.setBody(Ints.toByteArray(value));
                                events.add(event);
                            }

                            while (true) {
                                Transaction tx = sut.getTransaction();
                                try {
                                    tx.begin();
                                    for (Event event : events)
                                        sut.put(event);
                                    if (ThreadLocalRandom.current().nextDouble() < 0.01) {
                                        tx.rollback();
                                        continue; // retry transaction
                                    } else {
                                        tx.commit();
                                        break;
                                    }
                                } finally {
                                    tx.close();
                                }
                            }
                        }
                    }, executor));
                }

                BitSet received = new BitSet(numEvents);

                AtomicBoolean sourceCompleted = new AtomicBoolean(false);

                List<Future<?>> sinkFutures = new ArrayList<>();
                for (int th = 0; th < sinkThreads; ++th) {
                    int tid = th;

                    sinkFutures.add(CompletableFuture.runAsync(() -> {
                        Thread.currentThread().setName(String.format("%s-sink-%d", sut.getName(), tid));

                        while (!sourceCompleted.get()) {
                            Transaction tx = sut.getTransaction();
                            try {
                                tx.begin();

                                int batchSize = ThreadLocalRandom.current().nextInt(20);
                                List<Event> events = new ArrayList<>(batchSize);

                                for (int i = 0; i < batchSize; ++i) {
                                    Event event = sut.take();
                                    if (event == null)
                                        break;
                                    events.add(event);
                                }

                                if (ThreadLocalRandom.current().nextDouble() < 0.01) {
                                    tx.rollback();
                                    continue;
                                } else {
                                    synchronized (received) {
                                        for (Event event : events) {
                                            received.set(Ints.fromByteArray(event.getBody()));
                                        }
                                    }
                                    tx.commit();
                                }

                                if (batchSize > 0 && events.isEmpty()) {
                                    Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
                                }
                            } finally {
                                tx.close();
                            }
                        }
                    }, executor));
                }

                CompletableFuture.allOf(sourceFutures.toArray(new CompletableFuture[0])).whenComplete((r, th) -> {
                    sourceCompleted.set(true);
                });

                executor.submit(() -> {
                    while (!sourceCompleted.get()) {
                        LOG.info("ChannelCounter: {}", sut.getChannelCounter());
                        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
                    }
                    LOG.info("ChannelCounter: {}", sut.getChannelCounter());
                });

                CompletableFuture.allOf(sourceFutures.toArray(new CompletableFuture[0])).join();
                CompletableFuture.allOf(sinkFutures.toArray(new CompletableFuture[0])).join();

                assertThat(received.nextClearBit(0))
                        .withFailMessage("SynchronousChannel lost some events")
                        .isEqualTo(numEvents);
            } finally {
                executor.shutdown();
            }
        } finally {
            sut.stop();
        }
    }
}
