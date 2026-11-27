package ch.bbw.trips.storage;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Schlüssel (Dateinamen) für Bilder. Sie werden immer vom Server generiert (UUID + Endung) und
 * vor jedem Zugriff geprüft – so kann ein Client weder fremde Pfade ({@code ../}) noch beliebige
 * S3-Keys ansprechen.
 */
final class ImageKeys {

	static final Map<String, String> EXTENSIONS = Map.of(
			"image/jpeg", "jpg",
			"image/png", "png",
			"image/gif", "gif",
			"image/webp", "webp");

	private static final Map<String, String> CONTENT_TYPES = Map.of(
			"jpg", "image/jpeg",
			"png", "image/png",
			"gif", "image/gif",
			"webp", "image/webp");

	private static final Pattern VALID_KEY =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|gif|webp)");

	private ImageKeys() {
	}

	static String newKey(String contentType) {
		String extension = EXTENSIONS.get(contentType);
		if (extension == null) {
			throw new IllegalArgumentException("Nicht unterstützter Bildtyp: " + contentType);
		}
		return UUID.randomUUID() + "." + extension;
	}

	static boolean isValid(String key) {
		return key != null && VALID_KEY.matcher(key).matches();
	}

	static String contentTypeFor(String key) {
		return CONTENT_TYPES.get(key.substring(key.lastIndexOf('.') + 1));
	}
}
