package ch.bbw.trips.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalImageStorageTest {

	@TempDir
	Path dir;

	@Test
	void storeLoadListDelete() throws Exception {
		LocalImageStorage storage = new LocalImageStorage(dir.toString());
		byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

		String key = storage.store("image/png", new ByteArrayInputStream(png), png.length);

		assertThat(key).endsWith(".png");
		assertThat(storage.list()).containsExactly(key);
		assertThat(storage.load(key)).hasValueSatisfying(img -> {
			assertThat(img.content()).isEqualTo(png);
			assertThat(img.contentType()).isEqualTo("image/png");
		});
		assertThat(storage.delete(key)).isTrue();
		assertThat(storage.delete(key)).isFalse();
		assertThat(storage.list()).isEmpty();
	}

	@Test
	void rejectsKeysOutsideTheStorageDirectory() throws Exception {
		Path images = Files.createDirectories(dir.resolve("images"));
		Files.writeString(dir.resolve("secret.txt"), "geheim");
		LocalImageStorage storage = new LocalImageStorage(images.toString());

		assertThat(storage.load("../secret.txt")).isEmpty();
		assertThat(storage.delete("../secret.txt")).isFalse();
		assertThat(dir.resolve("secret.txt")).exists();
	}
}
