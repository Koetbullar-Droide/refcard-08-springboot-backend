package ch.bbw.trips.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Ablage für hochgeladene Bilder. Welche Implementierung aktiv ist, entscheidet
 * {@code app.storage.type}: {@code local} (Verzeichnis/Volume, RefCard 04) oder
 * {@code s3} (Amazon S3, RefCard 05). Controller und restliche App kennen nur dieses Interface.
 */
public interface ImageStorage {

	/** Speichert das Bild und gibt den generierten Schlüssel zurück (z. B. {@code 3f2c...e1.png}). */
	String store(String contentType, InputStream content, long size) throws IOException;

	Optional<StoredImage> load(String key) throws IOException;

	List<String> list() throws IOException;

	/** @return {@code false}, wenn es kein Bild mit diesem Schlüssel gibt */
	boolean delete(String key) throws IOException;

	record StoredImage(byte[] content, String contentType) {
	}
}
