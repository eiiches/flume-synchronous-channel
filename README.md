Flume Synchronous Channel
=========================

A Flume Channel in which every transaction that puts events waits for corresponding transactions that take the events, like [SynchronousQueue](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/SynchronousQueue.html).
This channel can be used as a faster alternative to File Channel when the channel doesn't need to act as a reservoir. See [Choosing channel implementations](#choosing-channel-implementations) and [Performance](#performance).

Motivation
----------

Let's say, we have the following Flume configuration: `[Taildir Source] --> [File Channel] --> [Avro Sink]`.
The Taildir Source reads lines of a log file as they are written by some application. File Channel temporarily stores the events on the disk.
Avro Sink takes events from the channel and sends to a remote Flume instance. We chose File Channel instead of Memory Channel here because we don't want to lose events.

The problem here is that we are basically writing the same data twice to the disk: one in the log file the Taildir Source is watching, and another in the File Channel.

Considering the File Channel, in this case, doesn't need to act as a reservoir since the log file itself already works like one, we should be able to replace
it with something new that behaves like [SynchronousQueue](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/SynchronousQueue.html).
It should be more performant because it doesn't use disks. It doesn't lose events and provides at-least once delivery because events to the channel are synchronously taken from the channel.

Usage
-----

1. [Download a pre-built jar](https://repo1.maven.org/maven2/net/thisptr/flume/plugins/flume-synchronous-channel/0.0.1/flume-synchronous-channel-0.0.1.jar) and put it under `$FLUME_HOME/lib`.

   Alternatively, you can build a jar manually:

   ```bash
   mvn clean package
   cp target/flume-synchronous-channel-1.0.0-SNAPSHOT.jar $FLUME_HOME/lib
   ```

2. Add SynchronousChannel to your Flume configuration.

   ```
   agent.channels = ch1
   agent.channels.ch1.type = net.thisptr.flume.plugins.channel.synchronous.SynchronousChannel
   ```


How it works
------------

When the source has put() events and called commit() of a transaction, the commit() is blocked until the sink(s) take() all the events of the transaction and calls corresponding commit().
This guarantees at-least once delivery because the source transaction succeeds only after the sink has consumed all the events of the transaction.

Choosing channel implementations
-----------------

If you don't need at-least once delivery, just use Memory Channel. If the source itself works like a reservoir, such as in case of Taildir Source, Kinesis Source, or Pub/Sub Source, you can try Synchronous Channel. Otherwise, use File Channel.

|                         | At-least Once Delivery     | Acts as a reservoir                        | Performance          |
|-------------------------|----------------------------|--------------------------------------------|----------------------|
| File Channel            | Yes                        | Yes                                        | Low (Disks are slow) |
| Memory Channel          | No (Loses events on crash) | Yes                                        | High                 |
| **Synchronous Channel** | Yes                        | No (Source is blocked if the sink is slow) | High                 |

Performance
-----------

YMMV, but in our benchmark, we observed 80-90% increase in throughput and 75-95% reduction in CPU usage compared to File Channel. See [docs/benchmark/README.md](docs/benchmark/README.md) for the details.

License
-------

Apache License, Version 2.0
