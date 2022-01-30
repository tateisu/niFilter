#!/bin/bash
set -eux

./gradlew shadowJar

cp `ls -t build/libs/nitterFilter2-*-all.jar |head -n 1 | sed -e "s/[\r\n]\+//g"` nitterFilter2.jar

./safeKill.pl `cat nitterFilter2.pid` "-Dfile.encoding=UTF-8 -jar nitterFilter2.jar" || true
sleep 2

java -Dfile.encoding=UTF-8 -jar nitterFilter2.jar config-x570.kts
