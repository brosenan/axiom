#!/bin/sh

echo "(use 'lucid.publish) (load-settings) (copy-assets) (publish-all)" | lein repl
