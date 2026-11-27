package ch.bbw.trips.SpringBootBusinessTripsv2;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Die Pipeline prüft nach jedem Deploy über /actuator/info, ob die neue Version läuft
 * (Smoke-Test). Dieser Test stellt sicher, dass Health und Version dort erreichbar sind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.storage.local.dir=target/test-images", "APP_VERSION=v9.9.9-test" })
class ActuatorVersionTest {

	@Value("${local.server.port}")
	int port;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void infoEndpointShowsDeployedVersion() throws Exception {
		HttpResponse<String> res = get("/actuator/info");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).contains("\"version\":\"v9.9.9-test\"").contains("biztrips-backend");
	}

	@Test
	void healthEndpointIsUp() throws Exception {
		HttpResponse<String> res = get("/actuator/health");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).contains("\"status\":\"UP\"");
	}
}
