# Changelog

## [0.2.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.1.0...v0.2.0) (2026-02-14)


### Features

* optimizing rar5 parsing by calculating split positions by inference rather than reading ([b2f2be5](https://github.com/skjaere/kotlin-compression-utils/commit/b2f2be5bcd118c4916915a975ef207f895ad9e8c))


### Bug Fixes

* correct 7z variable-length uint64 encoding/decoding ([2329a45](https://github.com/skjaere/kotlin-compression-utils/commit/2329a45a0585d0dc9e500f7209f4623f4e1e2fc8))
* making read and seek functions of SeekableInputStream suspend ([45ddf75](https://github.com/skjaere/kotlin-compression-utils/commit/45ddf75fd9f7a1f999c2a4616dd57bd85957d631))

## [0.1.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.0.2...v0.1.0) (2026-02-11)


### Features

* exposing crc checksums ([a2f0e09](https://github.com/skjaere/kotlin-compression-utils/commit/a2f0e09d79855d973b88bf652ccf4d944acdd9c4))

## [0.0.2](https://github.com/skjaere/kotlin-compression-utils/compare/v0.0.1...v0.0.2) (2026-02-10)


### Bug Fixes

* adding missing files ([3fffb1d](https://github.com/skjaere/kotlin-compression-utils/commit/3fffb1d73038650afcf658690ab1ac16d0b0fe83))
