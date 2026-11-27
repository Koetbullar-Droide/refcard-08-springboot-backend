package ch.bbw.trips.storage;

/**
 * Der Bilder-Storage ist nicht erreichbar oder verweigert den Zugriff (z. B. S3 antwortet mit
 * 403 AccessDenied, weil die Bucket-Policy die Task-Rolle nicht zulässt). Der Controller macht
 * daraus ein {@code 503 Service Unavailable} mit verständlicher Meldung statt eines rohen 500ers.
 */
public class StorageUnavailableException extends RuntimeException {

	public StorageUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
