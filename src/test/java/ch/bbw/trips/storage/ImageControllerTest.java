package ch.bbw.trips.storage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageControllerTest {

	@TempDir
	Path dir;

	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		mvc = MockMvcBuilders.standaloneSetup(new ImageController(new LocalImageStorage(dir.toString()))).build();
	}

	@Test
	void uploadDownloadDelete() throws Exception {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

		String location = mvc.perform(multipart("/v1/images").file(new MockMultipartFile("file", "a.png", "image/png", png)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");

		mvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/png"))
				.andExpect(content().bytes(png));
		mvc.perform(delete(location)).andExpect(status().isNoContent());
		mvc.perform(get(location)).andExpect(status().isNotFound());
	}

	@Test
	void rejectsNonImagesAndEmptyFiles() throws Exception {
		mvc.perform(multipart("/v1/images").file(new MockMultipartFile("file", "x.html", "text/html", "<script>".getBytes())))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/v1/images").file(new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
				.andExpect(status().isBadRequest());
	}

	@Test
	void storageErrorsAre503WithMessage() throws Exception {
		ImageStorage broken = new LocalImageStorage(dir.toString()) {
			@Override
			public java.util.List<String> list() {
				throw new StorageUnavailableException("Bilder-Storage nicht verfügbar (S3 ListObjectsV2)", null);
			}
		};
		MockMvc brokenMvc = MockMvcBuilders.standaloneSetup(new ImageController(broken)).build();

		brokenMvc.perform(get("/v1/images"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("ListObjectsV2")));
	}

	@Test
	void unknownOrInvalidKeyIs404() throws Exception {
		mvc.perform(get("/v1/images/does-not-exist.png")).andExpect(status().isNotFound());
		mvc.perform(delete("/v1/images/does-not-exist.png")).andExpect(status().isNotFound());
	}
}
