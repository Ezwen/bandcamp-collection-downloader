#!/bin/bash

docker run --rm -v $PWD:/workspace:z -w /workspace docker.io/library/gradle gradle fatjar