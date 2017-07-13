#!/bin/bash -xe

monoversion=MONOLITH-SNAPSHOT

mydir=`dirname $0`
echo My working dir is $mydir
cd $mydir

master_ver=`head -1 project.clj | cut -f2 -d'"'`
echo Master version: $master_ver

if [[ `git status --porcelain | grep "^ M"` ]]; then
    echo Repository has uncommitted changes.  Please commit them before proceeding.
    exit 1
fi

echo Replacing $monoversion to $master_ver
find $mydir -name "project.clj" | xargs sed -i -e "s%$monoversion%$master_ver%"
find $mydir -name "project.clj-e" | xargs rm

echo Deploying...
lein monolith each deploy clojars

echo Bringing things back to normal
git reset --hard
