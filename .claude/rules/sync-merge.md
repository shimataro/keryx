---
paths:
  - "**/DatabaseMerger*.kt" # the merge runs here, on a dedicated JDBC connection
  - "**/MergeSql.kt"        # merge SQL statements
  - "**/SyncRepository.kt"  # download → merge → snapshot → upload orchestration
  - "**/DatabaseSnapshot*.kt"
---

# Critical constraint: ATTACH-DATABASE merge — DO NOT violate without explicit user approval

**The ATTACH-DATABASE merge runs through `platform/DatabaseMerger`, NOT the
SQLDelight driver.** SQLDelight's JVM `JdbcSqliteDriver` opens a fresh
connection per statement for file DBs, so an `ATTACH` on one call is invisible
to the merge statements on the next. `DatabaseMerger` does the whole
attach → version-check → merge → detach on a single dedicated JDBC connection.

See also: `docs/sync-architecture.md` ("Merge (`DatabaseMerger` + `MergeSql`)")
and `docs/app-architecture.md` ("DatabaseMerger (expect / actual)").
