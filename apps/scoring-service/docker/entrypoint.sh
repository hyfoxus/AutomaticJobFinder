#!/bin/sh
set -e

# Optional Docker secret: overrides DB_PASSWORD when the file is mounted.
if [ -f /run/secrets/db_password ]; then
  export DB_PASSWORD="$(cat /run/secrets/db_password)"
fi

# No globbing of JAVA_OPTS.
set -f
exec java $JAVA_OPTS -jar /app/app.jar
