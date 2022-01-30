#!/bin/bash
set -eux

./gradlew shadowJar

cp `ls -t build/libs/nitterFilter2-*-all.jar |head -n 1 | sed -e "s/[\r\n]\+//g"` nitterFilter2.jar

rsync -e ssh nitterFilter2.jar juggler-v3:/v/nitterFilter2/

ssh juggler-v3 "cd /v/nitterFilter2; ./startV3.sh"
