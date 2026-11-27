package ch.bbw.trips.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Testet die S3-Anbindung ohne AWS: der S3Client wird gemockt. */
class S3ImageStorageTest {

	private final S3Client s3 = mock(S3Client.class);
	private final S3ImageStorage storage = new S3ImageStorage(s3, "test-bucket");

	@Test
	@SuppressWarnings("unchecked")
	void storePutsObjectUnderImagesPrefix() {
		byte[] jpg = {(byte) 0xFF, (byte) 0xD8};

		String key = storage.store("image/jpeg", new ByteArrayInputStream(jpg), jpg.length);

		ArgumentCaptor<Consumer<PutObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
		verify(s3).putObject(captor.capture(), any(RequestBody.class));
		PutObjectRequest.Builder request = PutObjectRequest.builder();
		captor.getValue().accept(request);
		assertThat(request.build().bucket()).isEqualTo("test-bucket");
		assertThat(request.build().key()).isEqualTo("images/" + key);
		assertThat(request.build().contentType()).isEqualTo("image/jpeg");
		assertThat(key).endsWith(".jpg");
	}

	@Test
	@SuppressWarnings("unchecked")
	void loadReturnsBytesOrEmptyWhenMissing() {
		String key = ImageKeys.newKey("image/png");
		when(s3.getObjectAsBytes(any(Consumer.class)))
				.thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {1, 2}))
				.thenThrow(NoSuchKeyException.builder().build());

		assertThat(storage.load(key)).hasValueSatisfying(img -> {
			assertThat(img.content()).containsExactly(1, 2);
			assertThat(img.contentType()).isEqualTo("image/png");
		});
		assertThat(storage.load(key)).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void invalidKeysNeverReachS3() {
		assertThat(storage.load("../../etc/passwd")).isEmpty();
		assertThat(storage.delete("other-prefix/x.png")).isFalse();
		verify(s3, never()).getObjectAsBytes(any(Consumer.class));
		verify(s3, never()).deleteObject(any(Consumer.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void deleteReturnsFalseWhenObjectDoesNotExist() {
		String key = ImageKeys.newKey("image/gif");
		when(s3.headObject(any(Consumer.class)))
				.thenThrow(S3Exception.builder().statusCode(404).build());

		assertThat(storage.delete(key)).isFalse();
		verify(s3, never()).deleteObject(any(Consumer.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void accessDeniedBecomesStorageUnavailable() {
		S3Exception denied = (S3Exception) S3Exception.builder().statusCode(403).message("Access Denied").build();
		when(s3.putObject(any(Consumer.class), any(RequestBody.class))).thenThrow(denied);
		when(s3.getObjectAsBytes(any(Consumer.class))).thenThrow(denied);
		when(s3.headObject(any(Consumer.class))).thenThrow(denied);

		assertThatThrownBy(() -> storage.store("image/png", new ByteArrayInputStream(new byte[] {1}), 1))
				.isInstanceOf(StorageUnavailableException.class)
				.hasMessageContaining("PutObject")
				.hasCause(denied);
		assertThatThrownBy(() -> storage.load(ImageKeys.newKey("image/png")))
				.isInstanceOf(StorageUnavailableException.class);
		assertThatThrownBy(() -> storage.delete(ImageKeys.newKey("image/png")))
				.isInstanceOf(StorageUnavailableException.class);
		verify(s3, never()).deleteObject(any(Consumer.class));
	}

	@Test
	void missingBucketFailsFast() {
		assertThatThrownBy(() -> new S3ImageStorage(s3, " "))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("APP_STORAGE_S3_BUCKET");
	}
}
