#!/bin/bash
set -ev # Exit with nonzero exit code if anything fails; verbose

rm -rf docs/

SOURCE_BRANCH="master"
TARGET_BRANCH="gh-pages"

# Save some useful information
REPO=`git config remote.origin.url`
SSH_REPO=${REPO/https:\/\/github.com\//git@github.com:}
SHA=`git rev-parse --verify HEAD`

# Clone the existing gh-pages for this repo into docs/
# Create a new empty branch if gh-pages doesn't exist yet (should only happen on first deply)
git clone $REPO docs
cd docs
git checkout $TARGET_BRANCH || git checkout --orphan $TARGET_BRANCH
cd ..

# Clean docs existing contents
rm -rf docs/**/* || exit 0

# Run our compile script
./build-docs.sh

# Now let's go have some fun with the cloned repo
cd docs

# Commit the "changes", i.e. the new version.
# The delta will show diffs between new and old versions.
git add .
git commit -m "Deploy to GitHub Pages: ${SHA}"

# Now that we're all set up, we can push.
git push $SSH_REPO $TARGET_BRANCH
