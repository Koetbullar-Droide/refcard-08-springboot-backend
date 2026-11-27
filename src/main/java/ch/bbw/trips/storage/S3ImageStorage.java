package ch.bbw.trips.storage;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * RefCard 05: Bilder in einem Amazon-S3-Bucket. Alle ECS-Tasks teilen sich denselben Bucket,
 * Bilder überleben Deployments und Task-Neustarts.
 * <p>
 * Zugangsdaten stehen bewusst nicht in der Konfiguration: Der AWS SDK holt sie über die
 * Default Credential Chain – auf ECS aus der Task-Rolle ({@code LabRole}), lokal aus
 * {@code ~/.aws/credentials} oder den {@code AWS_*}-Umgebungsvariablen.
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3ImageStorage implements ImageStorage {

	/** Alle Bilder liegen unter diesem Prefix; die Bucket-Policy erlaubt nur Zugriff darauf. */
	static final String PREFIX = "images/";

	private static final Logger log = LoggerFactory.getLogger(S3ImageStorage.class);

	private final S3Client s3;
	private final String bucket;

	@Autowired // zwei Konstruktoren -> Spring muss wissen, welchen es verwenden soll
	public S3ImageStorage(@Value("${app.storage.s3.bucket:}") String bucket,
			@Value("${app.storage.s3.region:us-east-1}") String region) {
		this(S3Client.builder().region(Region.of(region)).build(), bucket);
	}

	/** Für Tests: S3Client von aussen (Mock). */
	S3ImageStorage(S3Client s3, String bucket) {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("app.storage.type=s3, aber APP_STORAGE_S3_BUCKET ist nicht gesetzt");
		}
		this.s3 = s3;
		this.bucket = bucket;
	}

	@Override
	public String store(String contentType, InputStream content, long size) {
		String key = ImageKeys.newKey(contentType);
		call("PutObject", key, () -> s3.putObject(b -> b.bucket(bucket).key(PREFIX + key).contentType(contentType),
				RequestBody.fromInputStream(content, size)));
		return key;
	}

	@Override
	public Optional<StoredImage> load(String key) {
		if (!ImageKeys.isValid(key)) {
			return Optional.empty();
		}
		try {
			ResponseBytes<GetObjectResponse> object = call("GetObject", key,
					() -> s3.getObjectAsBytes(b -> b.bucket(bucket).key(PREFIX + key)));
			return Optional.of(new StoredImage(object.asByteArray(), ImageKeys.contentTypeFor(key)));
		} catch (NoSuchKeyException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<String> list() {
		return call("ListObjectsV2", PREFIX, () -> s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(PREFIX))
				.contents().stream()
				.map(o -> o.key().substring(PREFIX.length()))
				.filter(ImageKeys::isValid)
				.sorted()
				.toList());
	}

	@Override
	public boolean delete(String key) {
		if (!ImageKeys.isValid(key)) {
			return false;
		}
		// S3 meldet beim Löschen nicht, ob das Objekt existierte -> vorher prüfen (für 404 im Controller)
		try {
			s3.headObject(b -> b.bucket(bucket).key(PREFIX + key));
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return false;
			}
			throw unavailable("HeadObject", key, e);
		} catch (SdkException e) {
			throw unavailable("HeadObject", key, e);
		}
		call("DeleteObject", key, () -> s3.deleteObject(b -> b.bucket(bucket).key(PREFIX + key)));
		return true;
	}

	/**
	 * Führt einen S3-Aufruf aus und übersetzt AWS-Fehler (fehlende Rechte, Netzwerk, abgelaufene
	 * Credentials) in eine {@link StorageUnavailableException}. "Objekt fehlt" (NoSuchKey) wird
	 * durchgereicht, weil das ein normaler 404-Fall ist.
	 */
	private <T> T call(String operation, String key, Supplier<T> s3Call) {
		try {
			return s3Call.get();
		} catch (NoSuchKeyException e) {
			throw e;
		} catch (SdkException e) {
			throw unavailable(operation, key, e);
		}
	}

	private StorageUnavailableException unavailable(String operation, String key, SdkException e) {
		String hint = "";
		if (e instanceof S3Exception s3e && s3e.statusCode() == 403) {
			hint = " – Zugriff verweigert: Bucket-Policy (scripts/s3-bucket-policy.json) und Task-Rolle prüfen";
		}
		log.error("S3 {} auf s3://{}/{}{} fehlgeschlagen{}: {}", operation, bucket,
				key.startsWith(PREFIX) ? "" : PREFIX, key, hint, e.getMessage());
		return new StorageUnavailableException("Bilder-Storage nicht verfügbar (S3 " + operation + ")", e);
	}
}
