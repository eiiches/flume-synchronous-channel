#!/bin/bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
	echo "Usage: $0 version" 1>&2
	exit 1
fi

scriptpath="$(readlink -f "$0")"
scriptdir="$(dirname "$scriptpath")"
cd "$scriptdir/.."

version="$1"

sed -i "s;<version>[0-9a-z.-]*</version>;<version>$version</version>;" README.md
sed -i "s;https://search.maven.org/artifact/net.thisptr.flume.plugins/flume-synchronous-channel/[0-9a-z.-]*/;https://search.maven.org/artifact/net.thisptr.flume.plugins/flume-synchronous-channel/$version/;" README.md
sed -i "s;https://repo1.maven.org/maven2/net/thisptr/flume/plugins/flume-synchronous-channel/[0-9a-z.-]*/;https://repo1.maven.org/maven2/net/thisptr/flume/plugins/flume-synchronous-channel/$version/;" README.md
