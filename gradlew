#!/bin/sh
# Gradle wrapper script

# Determine the project base dir
APP_HOME=$(cd "$(dirname "$0")" && pwd)

# Add default JVM options here
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

# Use JAVA_HOME if set
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
