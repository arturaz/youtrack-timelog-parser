#!/bin/sh
java -jar `dirname $0`/target/scala-2.12/youtrack-timelog-parser-assembly-1.2.1.jar "$@"
