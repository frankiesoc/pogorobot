#!/bin/bash

if [ ! -z $1 ]
then
: # $1 was given
VERSION=$1
else
: # $1 was not given
VERSION='1.0.8-SNAPSHOT'
fi

screen -S telegram-bot -d -m java -XX:+HeapDumpOnOutOfMemoryError -Xmx800m -Dext.properties.dir=file:$PWD/config -jar pogorobot-$VERSION-exec.jar
