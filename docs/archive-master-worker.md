# Archive master and workers

`ArchiveService` is the archive API facade. `ArchiveMaster` submits typed
`ArchiveOperation` instances to a local fixed-size worker pool. Operations
share an `ArchiveContext` for the archive directory and path validation.
Synchronous API methods execute directly; asynchronous methods use the pool.

Set `ARCHIVE_WORKERS` to a positive integer to configure the pool size. The
default is two. Workers are reused and the pool stops accepting work when the
application closes, while already submitted work is allowed to finish.

The current executor backlog is in memory and unbounded. It is not recoverable
after a restart. Uploads still wait for archive and metadata persistence and
return the existing response. Durable job records, upload staging, recovery,
job status, and asynchronous upload responses are not implemented yet.
