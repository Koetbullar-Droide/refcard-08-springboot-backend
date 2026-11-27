package ch.bbw.trips.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RefCard 04: Bilder in einem Verzeichnis (lokal bzw. Docker-Volume). Default für die lokale
 * Entwicklung. In der Cloud ungeeignet: jeder ECS-Task hat sein eigenes, flüchtiges Dateisystem.
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalImageStorage implements ImageStorage {

	private final Path dir;

	public LocalImageStorage(@Value("${app.storage.local.dir:./data/images}") String dir) throws IOException {
		this.dir = Path.of(dir).toAbsolutePath().normalize();
		Files.createDirectories(this.dir);
	}

	@Override
	public String store(String contentType, InputStream content, long size) throws IOException {
		String key = ImageKeys.newKey(contentType);
		Files.copy(content, dir.resolve(key));
		return key;
	}

	@Override
	public Optional<StoredImage> load(String key) throws IOException {
		if (!ImageKeys.isValid(key) || !Files.exists(dir.resolve(key))) {
			return Optional.empty();
		}
		return Optional.of(new StoredImage(Files.readAllBytes(dir.resolve(key)), ImageKeys.contentTypeFor(key)));
	}

	@Override
	public List<String> list() throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).filter(ImageKeys::isValid).sorted().toList();
		}
	}

	@Override
	public boolean delete(String key) throws IOException {
		return ImageKeys.isValid(key) && Files.deleteIfExists(dir.resolve(key));
	}
}
