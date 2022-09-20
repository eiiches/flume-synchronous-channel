Flume Synchronous Channel
=========================

An experimental Flume Channel in which every transaction that puts events must wait for corresponding transactions that take the events, like SynchronousQueue in Java.

Motivation
----------

Let's say, we have the following Flume configuration: `[Taildir Source] --> [File Channel] --> [Avro Sink]`.
The Taildir Source reads lines of a log file as they are written by some application. File Channel temporarily buffers the events on the disk.
We chose File Channel here instead of Memory Channel because we don't want to lose events. Avro Sink takes events from the channel and sends to a remote Flume instance.

Question here is, do we really need a File Channel? The log file itself already serves as a buffer, isn't the File Channel just extra unnecessary IO?
Disks are slow and hard to maintain. What if, we can provide at-least once guarantee without using a disk? This is my experiment to answer these questions.

Usage
-----

We don't have a pre-built jar in the Maven Central yet. You need to build a jar manually.

```bash
mvn clean package
cp target/flume-synchronous-channel-1.0.0-SNAPSHOT.jar $FLUME_HOME/lib
```

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

If you don't need at-least once delivery, just use Memory Channel. If the source itself has a buffering capability, such as in case of Taildir Source, Kinesis Source, or Pub/Sub Source, you can try Synchronous Channel. Otherwise, use File Channel.

|            | At-least Once Delivery | Events are buffered when sink is slow |
|------------|------------|-----------|
| File Channel | Yes | Yes |
| Memory Channel | No (Loses events on crash) | Yes |
| **Synchronous Channel** | Yes | No |

Performance
-----------

TODO: do some benchmark

Because sources and sinks are synchronized, I expect the throughput to be slightly worse than using a normal File Channel, unless the number of parallel sinks/sources is increased.

License
-------

Apache License, Version 2.0
