#!/bin/bash
scp target/feeds-clj-0.0.1-SNAPSHOT-standalone.jar ubuntu@aws:/projects/nikonyrh-feeds/target
scp data/*.edn ubuntu@aws:/projects/nikonyrh-feeds/data
scp startFeedsContainer.sh ubuntu@aws:/projects/nikonyrh-feeds

