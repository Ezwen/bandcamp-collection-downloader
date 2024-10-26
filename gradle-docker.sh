#!/bin/bash

set -e
set -x

docker run --rm -v $PWD:/workspace:z -w /workspace docker.io/library/gradle:7-jdk11 gradle $*

