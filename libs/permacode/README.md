# permacode #

[![Build Status](https://travis-ci.org/brosenan/permacode.svg?branch=master)](https://travis-ci.org/brosenan/permacode)
[![Clojars Project](https://img.shields.io/clojars/v/permacode.svg)](https://clojars.org/permacode)

*Permacode* is a Clojure library and Leiningen plug-in that allows developers to write *purely declarative* Clojure code
that will *allways run the same way*.

Please see [our documentations](https://brosenan.github.io/permacode/core.html#introduction) for more details. 

## Usage ##

There are three use cases for permacode, which are not mutual exclusive:
1. A developer developing a permacode module.
2. A developer using a specific version of a permacode module as a dependency of a Clojure project (which may or may not be a permacode module).
3. A developer receiving a fully-qualified name of (typically) a function, evaluating it.

The following subsections refer to these use cases.

#### Developing Permacode Modules ####

To make your project a permacode project, add `permacode` as both a dependency and a leiningen plug-in
in the `dev` profile.

When writing your code you're restricted to only use pure functions that are white-listed.
To see an up-to-date list of the functions white-listed in `clojure.core` see the definition of `core-white-list`
in [this source file](https://github.com/brosenan/permacode/blob/master/src/permacode/validate.clj).

There is also a small number of other namespaces (e.g., `clojure.string`, `clojure.set`) that are also white-listed
(see `white-listed-ns` in the same file), where all functions are considered pure and are therefore allowed for use

Each source file must begin with an `ns` expression, which may only contain `:require` clauses.
See [here](https://brosenan.github.io/permacode/validate.html#validate-ns) for the exact details.

The rest of the expressions must be wrapped in `premacode.core/pure`.
This is a macro that checks the underlying code (at compile time) for use of unauthorized symbols.
It results in the underlying code, so there is no run-time performance hit.

Source files can `:require` white-listed libraris, permacode hashed libraries (see below) and one another.
Other dependencies are not allowed.

Testing can be done using any kind of testing library, such as `clojure.test` or [midje](https://github.com/marick/Midje).

Once you're happy with the code, you can use `lein permacode publish` to *hash* it.
`publish` will serialize the source code and store it in a repository.
Currently only local repositories are supported.  The default location is `~/.permacode`, but it can be modified by
setting the `:permacode-repo` key in the `project.clj` file.

`publish` prints the hash codes produced for each module:
```bash
cloudlog.clj$ lein permacode publish
perm.QmYWHz6bjnvgiutyjNCRv8CAruCLFnJWuAA44Fos6pPj6z 	 cloudlog.interset
perm.QmQodG29316hRLwuJVGpCP3CW7VCJEE5KTpK6ABDBDUT1H 	 cloudlog.unify
perm.QmYX8EX8VtsAUhv9j2svB6m6KRTDaYb7NZn4AH73ifkzAA 	 cloudlog.core
```

### Using Permacode Modules From Projects ###
Whether or not your project is a permacode project (i.e., you intend to use `lein permacode publish` on it),
you can `:require` permacode modules by requiring a specific version.  For example:

```clojure
(ns perm-example.core
  (:require [perm.QmYX8EX8VtsAUhv9j2svB6m6KRTDaYb7NZn4AH73ifkzAA :as cloudlog]
            [permacode.core]))
```

To make this namespace available, use `lein permacode deps`.
It will retrieve the dependency and place it in a file in your source directory tree.
For example, if your source directory is `./src`, `lein permacode deps` will create
a directory `./src/perm` and place `QmYX8EX8VtsAUhv9j2svB6m6KRTDaYb7NZn4AH73ifkzAA.clj` inside it.

As a best practice it is advised to add this directory to `.gitignore`:
```
src/perm
```

Please note that for this to work the modules need to be in the repository.

### Calling Permacode Dynamically ###
The ultimate goal of permacode is to make it easy to distribute code.
Distributing code can be done by `publish`ing it, and then by providing someone a fully-qualified symbol
identifying the code you wish them to run.
This symbol is typically a function, which is guaranteed to always run the same way.

The function [eval-symbol](https://brosenan.github.io/permacode/core.html#eval-symbol) takes a fully qualified symbol
and evaluates it.
Please consult the [documentation](https://brosenan.github.io/permacode/core.html#eval-symbol) to see how to use this function.

## Documentation ##
Please see [here](https://brosenan.github.io/permacode/core.html).

