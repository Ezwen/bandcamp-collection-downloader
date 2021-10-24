#!/bin/bash

set -e
set -x

docker run --rm -v $PWD:/workspace:z -w /workspace docker.io/library/gradle:jdk11 gradle $*