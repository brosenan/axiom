#!/bin/sh

set -e
basedir=$(cd $(dirname $0) && pwd)

cd $basedir
git clone https://github.com/brosenan/axiom-seed.git tmp

target=$basedir/resources/leiningen/new/axiom_clj/
cd $basedir/tmp
rm -rf .git

edit='sed -e s/my-app/{{name}}/g -e s/my_app/{{sanitized}}/g -e s%axiom-clj/\(.*\)".*"%axiom-clj/\1"{{axiom-ver}}"%g'

for file in `find . -type f`; do
    basefile=`basename $file`
    $edit $file > $target/$basefile
    echo "[\"$file\" (render \"$basefile\" data)]" | $edit
done

rm -rf $basedir/tmp
