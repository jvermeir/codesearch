#!/bin/sh
exec java --add-modules jdk.incubator.vector -jar "$(dirname "$0")/target/codesearch.jar" "$@"
