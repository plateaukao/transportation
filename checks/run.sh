#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
check_dir=$(mktemp -d)
trap 'rm -rf "$check_dir"' EXIT
javac -encoding UTF-8 -d "$check_dir" app/src/main/java/info/plateaukao/transportation/Transit.java checks/TransitCheck.java
java -cp "$check_dir" info.plateaukao.transportation.TransitCheck checks/fixtures
