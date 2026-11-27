package ch.bbw.trips.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bilder hoch-/herunterladen. Weiss nicht, wo die Bilder liegen – das entscheidet die aktive
 * {@link ImageStorage}-Implementierung (lokal oder S3).
 */
@RestController
@RequestMapping("/v1/images")
public class ImageController {

	private final ImageStorage storage;

	public ImageController(ImageStorage storage) {
		this.storage = storage;
	}

	@GetMapping
	public List<String> list() throws IOException {
		return storage.list();
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datei ist leer");
		}
		if (!ImageKeys.EXTENSIONS.containsKey(file.getContentType())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nur JPEG, PNG, GIF oder WebP erlaubt");
		}
		String key;
		try (InputStream in = file.getInputStream()) {
			key = storage.store(file.getContentType(), in, file.getSize());
		}
		String url = "/v1/images/" + key;
		return ResponseEntity.created(URI.create(url)).body(Map.of("key", key, "url", url));
	}

	@GetMapping("/{key}")
	public ResponseEntity<byte[]> download(@PathVariable String key) throws IOException {
		return storage.load(key)
				.map(image -> ResponseEntity.ok()
						.contentType(MediaType.parseMediaType(image.contentType()))
						.cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
						.header("X-Content-Type-Options", "nosniff")
						.body(image.content()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bild nicht gefunden"));
	}

	/** S3 nicht erreichbar / keine Rechte -> 503 mit klarer Meldung (Details stehen im Log). */
	@ExceptionHandler(StorageUnavailableException.class)
	public ResponseEntity<Map<String, String>> storageUnavailable(StorageUnavailableException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
	}

	@DeleteMapping("/{key}")
	public ResponseEntity<Void> delete(@PathVariable String key) throws IOException {
		return storage.delete(key) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}
}
