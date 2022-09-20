FROM openjdk:18.0.2.1-jdk-slim-bullseye

RUN DEBIAN_FRONTEND=noninteractive
RUN apt-get update -y && apt-get install -y curl procps
RUN curl -L https://downloads.apache.org/flume/1.10.1/apache-flume-1.10.1-bin.tar.gz | tar xz -C /opt
ENV FLUME_HOME /opt/apache-flume-1.10.1-bin
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
