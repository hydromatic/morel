#!/bin/bash
#
# Licensed to Julian Hyde under one or more contributor license
# agreements.  See the NOTICE file distributed with this work
# for additional information regarding copyright ownership.
# Julian Hyde licenses this file to you under the Apache
# License, Version 2.0 (the "License"); you may not use this
# file except in compliance with the License.  You may obtain a
# copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied.  See the License for the specific
# language governing permissions and limitations under the
# License.
#
# Starts a Spark Connect server in a Docker container, in ANSI mode
# with the UTC time zone, holding the tables that init.py (in this
# directory) creates, and prints its URI (for example
# "sc://localhost:15002") on stdout once it accepts connections.
# Everything else goes to stderr, so
#
#   export SPARK_REMOTE=$(src/test/resources/spark/start-spark.sh)
#
# is enough to point the tests at it.
#
# Usage: start-spark.sh [OPTIONS]
#
#   --reuse         Use the container if it exists and was created from
#                   the current init.py, starting it if it is stopped;
#                   otherwise create a fresh one (default).
#   --new           Remove the container if it exists, and create a
#                   fresh one.
#   --stop          Stop and remove the container, and exit.
#   --name NAME     Container name (default: morel-spark).
#   --port PORT     Host port for Spark Connect (default: 15002).
#   --image IMAGE   Docker image (default: apache/spark:4.0.0).
#   --timeout SECS  How long to wait for the server (default: 120).
#   --help          Show this message and exit.

set -euo pipefail

name=morel-spark
port=15002
image=apache/spark:4.0.0
timeout=120
mode=reuse

usage() {
  sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# \{0,1\}//' >&2
}

while [ $# -gt 0 ]; do
  case "$1" in
    (--reuse) mode=reuse; shift ;;
    (--new) mode=new; shift ;;
    (--stop) mode=stop; shift ;;
    (--name) name=$2; shift 2 ;;
    (--port) port=$2; shift 2 ;;
    (--image) image=$2; shift 2 ;;
    (--timeout) timeout=$2; shift 2 ;;
    (--help) usage; exit 0 ;;
    (*) echo "start-spark.sh: unknown option '$1'" >&2; usage; exit 2 ;;
  esac
done

log() {
  echo "start-spark.sh: $*" >&2
}

dir=$(cd "$(dirname "$0")" && pwd)

# Hash of the init script, which the container records when it is
# created; "--reuse" recreates a container whose hash is not this one,
# because such a container holds tables that init.py no longer creates.
init_sha() {
  shasum -a 256 "$dir/init.py" | cut -d' ' -f1
}

# The hash the container records, or empty if it has none.
container_sha() {
  docker inspect --format '{{index .Config.Labels "morel.init.sha"}}' \
    "$name" 2>/dev/null || true
}

if ! docker info >/dev/null 2>&1; then
  log "the Docker daemon is not running"
  exit 1
fi

# Container state: "running", "exited", "created", etc., or empty if
# there is no container with this name.
state() {
  docker inspect --format '{{.State.Status}}' "$name" 2>/dev/null || true
}

case "$mode" in
  (stop)
    if [ -n "$(state)" ]; then
      docker rm -f "$name" >/dev/null
      log "removed container $name"
    else
      log "no container named $name"
    fi
    exit 0
    ;;
  (new)
    if [ -n "$(state)" ]; then
      docker rm -f "$name" >/dev/null
      log "removed container $name"
    fi
    ;;
esac

# A container created from a different init.py holds different tables, so
# reuse only one that was created from this init.py. Without this, editing
# init.py and running this script again would look as though it had done
# something, and the tables would be the old ones.
if [ "$mode" = reuse ] && [ -n "$(state)" ] \
    && [ "$(container_sha)" != "$(init_sha)" ]; then
  log "removing container $name: it was created from a different init.py"
  docker rm -f "$name" >/dev/null
fi

s=$(state)
if [ "$s" = running ]; then
  log "container $name is already running"
elif [ -n "$s" ]; then
  log "starting container $name (was $s)"
  docker start "$name" >/dev/null
else
  log "creating container $name from $image on port $port"
  # init.py is the driver program; the Connect server runs inside it
  # (spark.plugins), so clients see the tables it creates. The container
  # carries the hash of the init.py it was created from, so that --reuse
  # can tell whether it holds the tables this one would create.
  docker run -d --name "$name" -p "$port:15002" \
    --label "morel.init.sha=$(init_sha)" \
    -v "$dir:/morel:ro" "$image" \
    /opt/spark/bin/spark-submit \
    --conf spark.plugins=org.apache.spark.sql.connect.SparkConnectPlugin \
    --conf spark.connect.grpc.binding.port=15002 \
    --conf spark.sql.ansi.enabled=true \
    --conf spark.sql.session.timeZone=UTC \
    /morel/init.py >/dev/null
fi

# Wait until the driver logs that the tables exist, which is
# after the server started listening. A restarted container appends to
# its log, so count only lines since the last container start.
started=$(docker inspect --format '{{.State.StartedAt}}' "$name")
deadline=$((SECONDS + timeout))
ready() {
  # Not "grep -q": it would close the pipe early, and under pipefail
  # the pipeline would fail even when the line was found.
  [ "$(docker logs --since "$started" "$name" 2>&1 \
      | grep -c "Morel init tables created")" -gt 0 ]
}
until ready; do
  if [ "$(state)" != running ]; then
    log "container $name exited; last log lines:"
    docker logs --tail 20 "$name" >&2 || true
    exit 1
  fi
  if [ $SECONDS -ge $deadline ]; then
    log "server did not start within $timeout seconds"
    exit 1
  fi
  sleep 1
done

log "Spark Connect server is ready"

# The URI needs nothing but the address: each table says which of its
# columns hold nulls, in its own "morel.nullable" property, which
# init.py sets as it creates the table, because Spark keeps no NOT NULL
# for a table stored as files. So a client is told by the tables rather
# than by whoever typed the URI.
echo "sc://localhost:$port"

# End start-spark.sh
