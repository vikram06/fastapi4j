#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
command="${1:-test}"
case "$command" in
  compile|test|jar|example) ;;
  *) echo 'Usage: sh build.sh [compile|test|jar|example]' >&2; exit 2 ;;
esac
rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes
javac --release 21 -parameters -Xlint:all -d build/classes src/fastapi4j/*.java
case "$command" in
  test)
    javac --release 21 -parameters -Xlint:all -cp build/classes -d build/test-classes test/fastapi4j/*.java
    java -cp build/classes:build/test-classes fastapi4j.FrameworkTest
    ;;
  jar)
    jar --create --file build/fastapi4j.jar -C build/classes .
    echo 'Created build/fastapi4j.jar'
    ;;
  example)
    javac --release 21 -parameters -cp build/classes -d build/classes example/Main.java
    java -cp build/classes Main
    ;;
esac
