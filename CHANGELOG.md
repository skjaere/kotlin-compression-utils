# Changelog

## [0.4.1](https://github.com/skjaere/kotlin-compression-utils/compare/v0.4.0...v0.4.1) (2026-11-12)


### Bug Fixes

* **7z:** report a set missing a volume as a malformed archive ([28100ec](https://github.com/skjaere/kotlin-compression-utils/commit/28100ecb0020477285b9accc0c3fa422c3ed7c7d))
* **adapters:** run blocking I/O on Dispatchers.IO, interrupt on cancel ([5de2722](https://github.com/skjaere/kotlin-compression-utils/commit/5de2722b33295341294e270a834affdf77efa3e0))
* **android:** stop calling JDK APIs that Android only has from API 33 ([d935ccf](https://github.com/skjaere/kotlin-compression-utils/commit/d935ccfb09d497bd08323453e9f750d3aa08001b))
* **build:** target JVM 17 so the library can be dexed for Android ([a6862ac](https://github.com/skjaere/kotlin-compression-utils/commit/a6862ac7450fb9ebc9fc4bf0ded609dd202e2087))
* **generation:** number generated RAR5 volumes in their main headers ([ba171e5](https://github.com/skjaere/kotlin-compression-utils/commit/ba171e5ef9e4c0e3798cdd76ec7625215b5a3683))
* **par2:** don't give files that start alike the same PAR2 name ([a0834e2](https://github.com/skjaere/kotlin-compression-utils/commit/a0834e2dbb2e09a0a55b97da80f92a9c8a450057))
* **rar4:** read each set of a multi-set release from its own volumes ([591a72b](https://github.com/skjaere/kotlin-compression-utils/commit/591a72b694cec5667f6969ebc32b1b1e675e98de))
* **rar4:** respect LHD_LONG_BLOCK ADD_SIZE in non-file blocks + fallback for short inference ([f3fb9a5](https://github.com/skjaere/kotlin-compression-utils/commit/f3fb9a59d2c87615d78371a0d81fb6d05dd222ec))
* **rar:** loop signature read to tolerate partial-read SeekableInputStreams ([111fb7e](https://github.com/skjaere/kotlin-compression-utils/commit/111fb7edd2a0334b361aabc4ace95cf80f35d650))
* **resolve-volumes:** keep a volume PAR2 names with a folder in its set ([83cab66](https://github.com/skjaere/kotlin-compression-utils/commit/83cab668b040a5c928e1212c95939af5e16d57de))
* **resolve-volumes:** order RAR5 volumes by their headers' numbers ([5c5453c](https://github.com/skjaere/kotlin-compression-utils/commit/5c5453c1dfa16af32f2a924f3c973f0bde49350b))

## [0.4.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.3.2...v0.4.0) (2026-05-09)


### Features

* **rar5:** support encrypted (HEAD_CRYPT) RAR5 archives + tighten obfuscated-volume validation


### Bug Fixes

* use last-volume CRC for split files, null for inferred

## [0.3.2](https://github.com/skjaere/kotlin-compression-utils/compare/v0.3.1...v0.3.2) (2026-03-04)


### Bug Fixes

* correct RAR5 split position inference for non-minimal vint encoding

## [0.3.1](https://github.com/skjaere/kotlin-compression-utils/compare/v0.3.0...v0.3.1) (2026-02-24)


### Bug Fixes

* read RAR5 split flags from block-level header, not file flags

## [0.3.0](https://github.com/skjaere/kotlin-compression-utils/compare/v0.2.2...v0.3.0) (2026-02-22)


### Features

* supporting nested archives


### Bug Fixes

* fixing incorrect crc32 values for split rar4 archives

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
