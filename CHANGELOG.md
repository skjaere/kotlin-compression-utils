# Changelog

## [0.4.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.3.2...v0.4.0) (2026-05-09)


### Features

* **rar5:** support encrypted (HEAD_CRYPT) RAR5 archives + tighten obfuscated-volume validation ([e39fc17](https://github.com/skjaere/kotlin-compression-utils/commit/e39fc17687bea3695688b57d114540bd0bb4d09f))


### Bug Fixes

* use last-volume CRC for split files, null for inferred ([26ec8b0](https://github.com/skjaere/kotlin-compression-utils/commit/26ec8b09ba2fa84794ffb27ff1b072014b21e19e))

## [0.3.2](https://github.com/skjaere/kotlin-compression-utils/compare/v0.3.1...v0.3.2) (2026-03-04)


### Bug Fixes

* correct RAR5 split position inference for non-minimal vint encoding ([602f1d8](https://github.com/skjaere/kotlin-compression-utils/commit/602f1d858ab0eb7942a25329a092588e632e38ae))

## [0.3.1](https://github.com/skjaere/kotlin-compression-utils/compare/v0.3.0...v0.3.1) (2026-02-24)


### Bug Fixes

* read RAR5 split flags from block-level header, not file flags ([bdd85e0](https://github.com/skjaere/kotlin-compression-utils/commit/bdd85e0aa05e0012aa8c10081817d5beb517be93))

## [0.3.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.2.2...v0.3.0) (2026-02-22)


### Features

* supporting nested archives ([b075656](https://github.com/skjaere/kotlin-compression-utils/commit/b0756566cedfd6392da1a278466d8f93287c5b6e))


### Bug Fixes

* fixing incorrect crc32 values for split rar4 archives ([3f77921](https://github.com/skjaere/kotlin-compression-utils/commit/3f7792180f9c5a00258840723fcd06c1d9513e58))

## [0.2.2](https://github.com/skjaere/kotlin-compression-utils/compare/v0.2.1...v0.2.2) (2026-02-20)


### Bug Fixes

* support detecting par2 files from bytes ([0f70d6a](https://github.com/skjaere/kotlin-compression-utils/commit/0f70d6aac29f66b15caaf7f36eaa34c67d794c95))

## [0.2.1](https://github.com/skjaere/kotlin-compression-utils/compare/v0.2.0...v0.2.1) (2026-02-20)


### Bug Fixes

* fixing bug parsing 7zip volumes with compressed headers ([31521a2](https://github.com/skjaere/kotlin-compression-utils/commit/31521a2870522f46b3f92e38f519ea627cf2e78c))

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
