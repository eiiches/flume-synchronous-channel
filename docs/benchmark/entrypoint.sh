#!/bin/bash
set -euo pipefail

exec java ${JAVA_OPTS:-} -javaagent:/opt/scriptable-jmx-exporter-1.0.0-alpha3.jar=@$FLUME_HOME/conf/scriptable-jmx-exporter.yaml -cp $FLUME_HOME/conf:$FLUME_HOME/lib/*:/lib/* -Djava.library.path= org.apache.flume.node.Application -f $FLUME_HOME/conf/flume-conf.properties -n agent --no-reload-conf
