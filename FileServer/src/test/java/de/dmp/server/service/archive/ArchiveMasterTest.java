package de.dmp.server.service.archive;

import de.dmp.server.service.archive.ops.ArchiveOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveMasterTest {

	@TempDir
	Path directory;

	@Test
	void queuesTasksAndReusesWorker() throws Exception {

		var context = ArchiveContext.create(directory);
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var master = new ArchiveMaster(context, 1)) {
			var first = master.submit(ctx -> {
				started.countDown();
				try {
					if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
				} catch (InterruptedException ex) {
					Thread.currentThread()
					      .interrupt();
					throw new IOException(ex);
				}
				return Thread.currentThread();
			});
			try {
				assertTrue(started.await(5, TimeUnit.SECONDS));
				var second = master.submit(ctx -> Thread.currentThread());
				assertFalse(second.isDone());
				release.countDown();
				assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void reportsFailuresAndRejectsSubmissionsAfterClose() throws Exception {

		var master = new ArchiveMaster(ArchiveContext.create(directory), 1);
		var result = master.submit(ctx -> {
			throw new IOException("operation failed");
		});
		assertInstanceOf(IOException.class,
		                 assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
		master.close();
		assertThrows(RejectedExecutionException.class, () -> master.submit(ctx -> null));
	}

	@Test
	void workersShareBacklogAndShutdownDrainsAcceptedTasks() throws Exception {

		var context = ArchiveContext.create(directory);
		var started = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var master = new ArchiveMaster(context, 2);
		ArchiveOperation<Path> operation = ctx -> {
			started.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
			} catch (InterruptedException ex) {
				Thread.currentThread()
				      .interrupt();
				throw new IOException(ex);
			}
			return ctx.uploadDir();
		};
		try {
			var first = master.submit(operation);
			var second = master.submit(operation);
			assertTrue(started.await(5, TimeUnit.SECONDS));
			var queued = master.submit(ctx -> ctx.uploadDir());
			master.close();
			assertThrows(RejectedExecutionException.class, () -> master.submit(ctx -> null));
			release.countDown();
			assertEquals(directory, first.get(5, TimeUnit.SECONDS));
			assertEquals(directory, second.get(5, TimeUnit.SECONDS));
			assertEquals(directory, queued.get(5, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			master.close();
		}
	}

	@Test
	void rejectsInvalidWorkerCount() throws IOException {

		var context = ArchiveContext.create(directory);
		assertThrows(IllegalArgumentException.class, () -> new ArchiveMaster(context, 0));
	}

}
