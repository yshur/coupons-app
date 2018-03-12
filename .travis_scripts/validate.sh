#! /bin/bash

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`

# Sonatype staging (avoid Central sync delay)
perl -pe 's|:= buildVersion|:= buildVersion\r\n\r\nresolvers += "Sonatype Staging" at "https://oss.sonatype.org/content/repositories/staging/"|' < "$SCRIPT_DIR/../build.sbt" > /tmp/build.sbt && mv /tmp/build.sbt "$SCRIPT_DIR/../build.sbt"

if [ `sbt 'show version' 2>&1 | tail -n 1 | cut -d ' ' -f 2 | grep -- '-SNAPSHOT' | wc -l` -eq 1 ]; then
  perl -pe 's|:= buildVersion|:= buildVersion\r\n\r\nresolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"|' < "$SCRIPT_DIR/../build.sbt" > /tmp/build.sbt && mv /tmp/build.sbt "$SCRIPT_DIR/../build.sbt"
fi

sbt +testOnly
