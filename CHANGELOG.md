# Changelog

## [0.2.2](https://github.com/skjaere/kotlin-compression-utils/compare/v0.2.1...v0.2.2) (2026-02-20)


### Bug Fixes

* support detecting par2 files from bytes

## [0.2.1](https://github.com/skjaere/kotlin-compression-utils/compare/v0.2.0...v0.2.1) (2026-02-20)


### Bug Fixes

* fixing bug parsing 7zip volumes with compressed headers

## [0.2.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.1.0...v0.2.0) (2026-02-14)


### Features

* optimizing rar5 parsing by calculating split positions by inference rather than reading


### Bug Fixes

* correct 7z variable-length uint64 encoding/decoding
* making read and seek functions of SeekableInputStream suspend

## [0.1.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.0.2...v0.1.0) (2026-02-11)


### Features

* exposing crc checksums

## [0.0.2](https://github.com/skjaere/kotlin-compression-utils/compare/v0.0.1...v0.0.2) (2026-02-10)


### Bug Fixes

* adding missing files
