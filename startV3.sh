#!/bin/bash
set -eux

./safeKill.pl `cat nitterFilter2.pid` "-Dfile.encoding=UTF-8 -jar nitterFilter2.jar" || true
sleep 2

nohup java -Dfile.encoding=UTF-8 -jar nitterFilter2.jar config-V3.kts &>> nitterFilter2.log &
