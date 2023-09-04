# Changelog

For a list of breaking changes, check [here](#breaking-changes).

## Unreleased

- Replace `Jsonista` with `charred`
- fix: match opts `{:with-details true}` and {:mode :count} are incompatible
- String matching specialization #4
- Optimize construction of a batch of documents for matching #5
- Bump lt.jocas/lucene-custom-analyzer to 1.0.34
  - Support loading resources from the classpath
  - Build analyzers with mutable containers
- Faster monitor query serde #6

## 1.0.2 (2023-08-31)

- initial release

## Breaking changes
