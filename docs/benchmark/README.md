Benchmark
=========

The purpose of this benchmark is to determine how much better or worse Synchronous Channel performs in term of throughput and CPU usage, compared to File Channel.

For this benchmark, we use the following setup with two components:

```
Sender:

  [Taildir Source] --> [Synchronous or File Channel] --> [Avro Sink]

Receiver:

  [Avro Source] --> [File Channel] --> [Null Sink]
```

In `Sender` component, the Taildir Source reads a file and puts each line to the channel. Here, we can simply use `yes` as a load generator, redirecting its standard output to the file the Taildir Source is watching. 
Avro Sink takes events from the channel and then sends them to the `Receiver`.

## Preparation

1. Launch two c6id.large instances. One for the `Sender` and another for the `Receiver`.

   This time, we put the two instances in the same Availability Zone in a single region (ap-northeast-1).

   ```console
   $ uname -r
   5.10.130-118.517.amzn2.x86_64
   ```

2. Setup tools, etc.

   ```sh
   # Create a file system on NVMe SSD instance store and mount it on /mnt. We are going to
   # use this directory as dataDirs and checkpointDir for File Channel.
   sudo parted --align optimal /dev/nvme1n1 mklabel gpt
   sudo parted --align optimal /dev/nvme1n1 mkpart primary 0% 100%
   sudo mkfs.ext4 /dev/nvme1n1p1
   sudo mount /dev/nvme1n1p1 /mnt
   
   # setup some tools
   sudo yum install git dstat
   
   # setup docker
   sudo yum install docker
   sudo systemctl start docker.service
   
   # setup docker-compose
   sudo mkdir -p /usr/local/lib/docker/cli-plugins
   sudo curl -SL https://github.com/docker/compose/releases/download/v2.11.0/docker-compose-linux-x86_64 -o /usr/local/lib/docker/cli-plugins/docker-compose
   sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
   
   # sysctl for async-profiler
   sudo sysctl kernel.perf_event_paranoid=1
   sudo sysctl kernel.kptr_restrict=0
   ```

3. Start Flume processes.

   On the receiver side instance,

   ```sh
   sudo docker compose -f docker-compose.receiver.yaml build
   sudo docker compose -f docker-compose.receiver.yaml up
   ```

   On the sender side instance, after replacing `CHANGE_THIS_TO_RECEIVER_IP` in flume-conf-sender-(file|synchronous)-channel.properties to the real IP of the receiver instance,

   ```sh
   sudo docker compose -f docker-compose.sender.yaml build
   sudo docker compose -f docker-compose.sender.yaml up
   ```

## Running Benchmarks

##### Metrics

* Throughput [events/s]: `irate(org_apache_flume_channel_EventPutSuccessCount[1m])` on the sender side.
* CPU Usage [%]: `irate(java_lang_OperatingSystem_ProcessCpuTime[1m]) / 1000 / 1000 / 1000 * 100 / 2`. Division by 2 is because c6id.large instance has 2 vCPU.

Flame Graphs are obtained with `/opt/async-profiler-2.8.3-linux-x64/profiler.sh -d 60 -f output.html 1`.

#### Max Throughput Test

In this test, we write 100M lines at once (or faster than taildir source can catch up) and observe the sustained throughput.

```bash
# 1-byte
yes | head -100000000 > /data/input.$type.txt

# 512-byte
yes 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef | head -100000000 > /data/input.$type.txt
```

##### Results

Numbers are average over 15 minutes.

| Channel Type        | Event Size [bytes] | Throughput [events/s] | Sender CPU Usage [%] | Receiver CPU Usage [%] | Flame Graph                      |
|---------------------|--------------------|-----------------------|----------------------|------------------------|----------------------------------|
| File Channel (*1)   | 1                  | 48751.11              | 47.12 (*1)           | 30.78                  | [results/max-1-file.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/max-1-file.html) |
| Synchronous Channel | 1                  | 92753.33              | 2.63                 | 58.04                  | [results/max-1-synchronous.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/max-1-synchronous.html) |
| File Channel (*1)   | 512                | 35997.78              | 49.58 (*1)           | 27.73                  | [results/max-512-file.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/max-512-file.html) |
| Synchronous Channel | 512                | 66257.78              | 12.37                | 54.52                  | [results/max-512-synchronous.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/max-512-synchronous.html) |

(*1) The ChannelFillPercentage of the ch1 on the sender side is almost always 100% because taildir source read lines faster than avro sink can process. Due to synchronization overhead CPU usage gets significantly higher when File Channel is full.

#### Fixed Rate Test

In this test, we write 30k lines per second and observe the CPU usage.

```bash
# 1-byte; (1 + 1) * 30000 = 60000
yes | pv -L 60000 > /data/input.$type.txt

# 512-byte; (512 + 1) * 30000 = 15390000
yes 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef | pv -L 15390000 > /data/input.$type.txt
```

##### Results

Numbers are average over 15 minutes.

| Channel Type        | Event Size [bytes] | Throughput [events/s] | Sender CPU Usage [%] | Receiver CPU Usage [%] | Flame Graph                      |
|---------------------|--------------------|-----------------------|----------------------|------------------------|----------------------------------|
| File Channel        | 1                  | 30137.78              | 18.60                | 20.51                  | [results/30k-1-file.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/30k-1-file.html) |
| Synchronous Channel | 1                  | 30064.44              | 0.89                 | 20.75                  | [results/30k-1-synchronous.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/30k-1-synchronous.html) |
| File Channel        | 512                | 30030.0               | 28.23                | 23.95                  | [results/30k-512-file.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/30k-512-file.html) |
| Synchronous Channel | 512                | 30062.22              | 5.28                 | 23.96                  | [results/30k-512-synchronous.html](https://htmlpreview.github.io/?https://github.com/eiiches/flume-synchronous-channel/blob/main/docs/benchmark/results/30k-512-synchronous.html) |


