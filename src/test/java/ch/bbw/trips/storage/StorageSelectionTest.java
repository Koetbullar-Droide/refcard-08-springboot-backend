package ch.bbw.trips.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

/** Prüft, dass Spring je nach app.storage.type genau eine ImageStorage-Implementierung erzeugt. */
class StorageSelectionTest {

	@TempDir
	Path dir;

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(LocalImageStorage.class, S3ImageStorage.class);

	@Test
	void localIsDefault() {
		runner.withPropertyValues("app.storage.local.dir=" + dir)
				.run(ctx -> assertThat(ctx).hasSingleBean(ImageStorage.class).hasSingleBean(LocalImageStorage.class));
	}

	@Test
	void s3WhenConfigured() {
		runner.withPropertyValues("app.storage.type=s3", "app.storage.s3.bucket=test-bucket")
				.run(ctx -> assertThat(ctx).hasSingleBean(ImageStorage.class).hasSingleBean(S3ImageStorage.class));
	}

	@Test
	void s3WithoutBucketFailsAtStartup() {
		runner.withPropertyValues("app.storage.type=s3")
				.run(ctx -> assertThat(ctx).hasFailed());
	}
}
