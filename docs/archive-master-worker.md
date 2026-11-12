# Reactive archive operations

`ArchiveService` delegates reactive store, retrieve, and delete operations to
implementations of `ArchiveOperation`. They share an `ArchiveContext` for the
archive directory and path validation. Multipart transfer is reactive; blocking
ZIP and filesystem work uses a dedicated bounded-elastic scheduler. Download
streams are read on bounded-elastic threads.

Set `ARCHIVE_WORKERS` to a positive integer to configure the pool size. The
default is two. The scheduler is disposed when the application closes.

The scheduler backlog is bounded and in memory; it is not recoverable after a
restart. Uploads wait for archive and metadata persistence before responding.
Durable job records, recovery, job status, and asynchronous upload responses
are not implemented.
