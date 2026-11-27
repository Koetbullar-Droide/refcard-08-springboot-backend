package ch.bbw.trips.storage;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.ResponseBytes;
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
		s3.putObject(b -> b.bucket(bucket).key(PREFIX + key).contentType(contentType),
				RequestBody.fromInputStream(content, size));
		return key;
	}

	@Override
	public Optional<StoredImage> load(String key) {
		if (!ImageKeys.isValid(key)) {
			return Optional.empty();
		}
		try {
			ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(b -> b.bucket(bucket).key(PREFIX + key));
			return Optional.of(new StoredImage(object.asByteArray(), ImageKeys.contentTypeFor(key)));
		} catch (NoSuchKeyException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<String> list() {
		return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(PREFIX)).contents().stream()
				.map(o -> o.key().substring(PREFIX.length()))
				.filter(ImageKeys::isValid)
				.sorted()
				.toList();
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
			throw e;
		}
		s3.deleteObject(b -> b.bucket(bucket).key(PREFIX + key));
		return true;
	}
}
