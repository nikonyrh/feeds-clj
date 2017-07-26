#!/bin/bash

docker run -d \
    -v "$PWD/target/feeds-clj-0.0.1-SNAPSHOT-standalone.jar:/main.jar" \
    -v "$PWD/data:/data" \
    -e DATA_HOME=/data   \
    -e FEED_REFRESH=1    \
    -p 3000:3000         \
    nikonyrh/java8

