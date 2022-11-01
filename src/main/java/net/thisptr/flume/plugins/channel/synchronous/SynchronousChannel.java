package net.thisptr.flume.plugins.channel.synchronous;

import com.google.common.annotations.VisibleForTesting;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.channel.BasicChannelSemantics;
import org.apache.flume.channel.BasicTransactionSemantics;
import org.apache.flume.conf.TransactionCapacitySupported;
import org.apache.flume.instrumentation.ChannelCounter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class SynchronousChannel extends BasicChannelSemantics implements TransactionCapacitySupported {

    private ChannelCounter channelCounter;

    private static final long DEFAULT_TAKE_TIMEOUT_MILLIS = 1 * 1000L;

    private volatile long takeTimeoutMillis = DEFAULT_TAKE_TIMEOUT_MILLIS;

    private final LinkedList<SynchronousTransaction> txWithPendingEvents = new LinkedList<>();

    private static class PendingEvent {
        private final Event event;

        // The transaction that put this event into the channel
        private final SynchronousTransaction putTx;

        public PendingEvent(Event event, SynchronousTransaction putTx) {
            this.event = event;
            this.putTx = putTx;
        }
    }

    private class SynchronousTransaction extends BasicTransactionSemantics {

        // Events that this tx put, but not yet taken by other txs
        private final LinkedList<PendingEvent> putList = new LinkedList<>();

        // Events that this tx took from other txs.
        private final LinkedList<PendingEvent> takeList = new LinkedList<>();

        // This tx will not be committed until these dependency txs are committed. There will never be circular dependencies,
        // because events can only be taken from a transaction that is committing.
        private final Set<SynchronousTransaction> dependencyTxs = new HashSet<>();

        @Override
        protected void doPut(Event event) {
            channelCounter.incrementEventPutAttemptCount();

            synchronized (this) { // Actually, we don't need synchronized here, since no other threads access this tx before commit().
                putList.addLast(new PendingEvent(event, this));
            }
        }

        @Override
        protected Event doTake() throws InterruptedException {
            channelCounter.incrementEventTakeAttemptCount();

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(takeTimeoutMillis);

            boolean alreadyTookSome;
            synchronized (this) {
                alreadyTookSome = !takeList.isEmpty();
            }

            PendingEvent event;

            while (true) {
                SynchronousTransaction putTx;
                synchronized (txWithPendingEvents) {
                    while (txWithPendingEvents.isEmpty()) {
                        if (alreadyTookSome)
                            return null; // immediately return null and let the sink commit this tx

                        long timeoutNanos = deadlineNanos - System.nanoTime();
                        if (timeoutNanos < 0)
                            return null; // timeout reached
                        TimeUnit.NANOSECONDS.timedWait(txWithPendingEvents, timeoutNanos); // throws InterruptedException
                    }
                    putTx = txWithPendingEvents.peek();
                }

                synchronized (putTx) {
                    if (putTx.putList.isEmpty()) {
                        synchronized (txWithPendingEvents) {
                            txWithPendingEvents.remove(putTx);
                        }
                        continue;
                    }
                    event = putTx.putList.removeFirst();
                    if (putTx.putList.isEmpty()) {
                        synchronized (txWithPendingEvents) {
                            txWithPendingEvents.remove(putTx);
                        }
                    }
                    putTx.dependencyTxs.add(this);
                }

                break;
            }

            synchronized (this) {
                takeList.addLast(event);
            }

            return event.event;
        }

        @Override
        protected void doCommit() throws InterruptedException {
            synchronized (this) {
                if (!takeList.isEmpty()) { // Most likely, this tx is sink side.
                    // Notify dependent transactions that we are done
                    Set<SynchronousTransaction> dependentTxs = takeList.stream()
                            .map((pe) -> pe.putTx)
                            .collect(Collectors.toSet());
                    for (SynchronousTransaction dependentTx : dependentTxs) {
                        synchronized (dependentTx) {
                            dependentTx.dependencyTxs.remove(this);
                            if (dependentTx.dependencyTxs.isEmpty())
                                dependentTx.notify(); // There's always only 1 thread waiting. No need for notifyAll().
                        }
                    }

                    channelCounter.addToEventPutSuccessCount(takeList.size());
                    channelCounter.addToEventTakeSuccessCount(takeList.size());
                    takeList.clear();
                }

                if (!putList.isEmpty()) { // Most likely, this tx is source side.

                    // Notify sinks to take events from us.
                    synchronized (txWithPendingEvents) {
                        if (txWithPendingEvents.add(this)) // always true here
                            txWithPendingEvents.notifyAll(); // notify blocked sinks
                    }

                    // We wait for other sinks to drain our putQueue
                    while (!putList.isEmpty() || !dependencyTxs.isEmpty())
                        this.wait(); // throws InterruptedException
                }
            }
        }

        @Override
        protected void doRollback() {
            // NOTE: We don't have to worry about lock order inconsistencies because there's no circular dependencies among transactions.
            synchronized (this) {
                if (!takeList.isEmpty()) { // Most likely, this tx is sink side.

                    Map<SynchronousTransaction, List<PendingEvent>> perPutTxTakeQueue = takeList.stream()
                            .collect(Collectors.toMap(
                                    e -> e.putTx,
                                    e -> new ArrayList<>(Collections.singletonList(e)),
                                    (a, b) -> {
                                        a.addAll(b);
                                        return a;
                                    })
                            );

                    // Return events we took from other txs
                    perPutTxTakeQueue.forEach((putTx, events) -> {
                        synchronized (putTx) {
                            for (PendingEvent event : events)
                                putTx.putList.addFirst(event);
                            putTx.dependencyTxs.remove(this);
                        }

                        synchronized (txWithPendingEvents) {
                            if (txWithPendingEvents.add(putTx))
                                txWithPendingEvents.notifyAll(); // notify blocked sinks
                        }
                    });

                    takeList.clear();
                }
                // We just discard the putQueue. The source is responsible for re-sending the events again.
                putList.clear();
            }
        }
    }

    @Override
    public void configure(Context context) {
        if (channelCounter == null)
            channelCounter = new ChannelCounter(getName());
        takeTimeoutMillis = context.getLong("takeTimeout", DEFAULT_TAKE_TIMEOUT_MILLIS);
    }

    @Override
    public synchronized void start() {
        channelCounter.start();
        super.start();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        channelCounter.stop();
    }

    @Override
    protected BasicTransactionSemantics createTransaction() {
        return new SynchronousTransaction();
    }

    @Override
    public long getTransactionCapacity() {
        return Integer.MAX_VALUE;
    }

    @VisibleForTesting
    ChannelCounter getChannelCounter() {
        return channelCounter;
    }
}
