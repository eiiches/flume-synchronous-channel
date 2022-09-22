FROM openjdk:18.0.2.1-jdk-slim-bullseye

RUN DEBIAN_FRONTEND=noninteractive
RUN apt-get update -y && apt-get install -y curl procps pv
RUN curl -L https://downloads.apache.org/flume/1.10.1/apache-flume-1.10.1-bin.tar.gz | tar xz -C /opt
ENV FLUME_HOME /opt/apache-flume-1.10.1-bin
RUN curl -L https://repo1.maven.org/maven2/net/thisptr/scriptable-jmx-exporter/1.0.0-alpha3/scriptable-jmx-exporter-1.0.0-alpha3.jar -o /opt/scriptable-jmx-exporter-1.0.0-alpha3.jar
COPY scriptable-jmx-exporter.yaml $FLUME_HOME/conf/scriptable-jmx-exporter.yaml
RUN curl -L https://github.com/jvm-profiling-tools/async-profiler/releases/download/v2.8.3/async-profiler-2.8.3-linux-x64.tar.gz | tar xz -C /opt
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
