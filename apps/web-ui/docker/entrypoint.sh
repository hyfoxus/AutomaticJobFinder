#!/bin/sh
set -e

# No globbing of JAVA_OPTS.
set -f
exec java $JAVA_OPTS -jar /app/app.jar
